package dev.nquangit.corestra.access.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateAnalyzer;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.template.RequestTemplateHasher;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Imports OpenAPI 3.x JSON and Swagger/OpenAPI 2.0 JSON documents into access templates.
 */
public final class OpenApiAccessTemplateImporter implements AccessTemplateImporter {
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    private final ObjectMapper objectMapper;
    private final ImportedTemplateFactory templateFactory;
    private final ImportVariableMapper variableMapper;

    /**
     * Creates an OpenAPI importer with default services.
     */
    public OpenApiAccessTemplateImporter() {
        this(new UuidAccessIdGenerator());
    }

    /**
     * Creates an OpenAPI importer with deterministic ID generation.
     *
     * @param idGenerator template ID generator
     */
    public OpenApiAccessTemplateImporter(AccessIdGenerator idGenerator) {
        this(
                new ObjectMapper(),
                new ImportedTemplateFactory(
                        idGenerator,
                        new AccessRequestTemplateAnalyzer(new TemplateRenderer(
                                new VariableResolver(new DefaultRuntimeValueProvider()))),
                        new RequestTemplateHasher()),
                new ImportVariableMapper());
    }

    OpenApiAccessTemplateImporter(
            ObjectMapper objectMapper,
            ImportedTemplateFactory templateFactory,
            ImportVariableMapper variableMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.templateFactory = Objects.requireNonNull(templateFactory, "templateFactory");
        this.variableMapper = Objects.requireNonNull(variableMapper, "variableMapper");
    }

    /**
     * Imports OpenAPI or Swagger JSON templates.
     *
     * @param source import source
     * @param options import options
     * @return import result
     */
    @Override
    public ImportResult importTemplates(ImportSource source, ImportOptions options) {
        Objects.requireNonNull(source, "source");
        ImportOptions resolvedOptions = options == null ? ImportOptions.defaults() : options;
        List<ImportWarning> warnings = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        if (source.format() == ImportFormat.OPENAPI_YAML) {
            errors.add(new ImportError(source.name(), "OpenAPI YAML import is deferred in PR 22; use JSON input."));
            return new ImportResult(List.of(), warnings, errors, 1);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(source.content());
        } catch (JsonProcessingException exception) {
            errors.add(new ImportError(source.name(), "Invalid OpenAPI JSON: " + exception.getOriginalMessage()));
            return new ImportResult(List.of(), warnings, errors, 1);
        }
        if (!root.has("openapi") && !root.has("swagger")) {
            errors.add(new ImportError(source.name(), "Document is not an OpenAPI or Swagger JSON document."));
            return new ImportResult(List.of(), warnings, errors, 1);
        }

        Endpoint endpoint = endpoint(root, resolvedOptions, warnings);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            errors.add(new ImportError(source.name(), "OpenAPI document has no paths object."));
            return new ImportResult(List.of(), warnings, errors, 1);
        }

        List<AccessRequestTemplate> templates = new ArrayList<>();
        int[] skipped = new int[]{0};
        paths.fields().forEachRemaining(pathEntry -> importPath(
                source,
                resolvedOptions,
                endpoint,
                pathEntry.getKey(),
                pathEntry.getValue(),
                templates,
                warnings,
                errors,
                skipped));
        return new ImportResult(templates, warnings, errors, skipped[0]);
    }

    private void importPath(
            ImportSource source,
            ImportOptions options,
            Endpoint endpoint,
            String apiPath,
            JsonNode pathItem,
            List<AccessRequestTemplate> templates,
            List<ImportWarning> warnings,
            List<ImportError> errors,
            int[] skipped) {
        List<JsonNode> inheritedParameters = parameters(pathItem.path("parameters"));
        for (Map.Entry<String, JsonNode> entry : iterable(pathItem.fields())) {
            String method = entry.getKey().toLowerCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                continue;
            }
            JsonNode operation = entry.getValue();
            String location = method.toUpperCase(Locale.ROOT) + " " + apiPath;
            if (operation.path("deprecated").asBoolean(false) && !options.includeDeprecatedOperations()) {
                warnings.add(new ImportWarning(location, "Skipped deprecated operation."));
                skipped[0]++;
                continue;
            }
            try {
                Endpoint operationEndpoint = operationEndpoint(endpoint, operation, options, warnings, location);
                List<JsonNode> operationParameters = new ArrayList<>(inheritedParameters);
                operationParameters.addAll(parameters(operation.path("parameters")));
                String pathAndQuery = buildPathAndQuery(apiPath, operationParameters, options, warnings, location);
                RequestBody body = requestBody(operation, options, warnings, location);
                List<String> headers = new ArrayList<>();
                body.contentTypeHeader().ifPresent(contentType -> headers.add("Content-Type: " + contentType));
                StoredRequestTemplate request = new StoredRequestTemplate(
                        method.toUpperCase(Locale.ROOT),
                        operationEndpoint.scheme(),
                        operationEndpoint.host(),
                        operationEndpoint.port(),
                        combinePaths(operationEndpoint.basePath(), pathAndQuery),
                        headers,
                        body.body());
                templates.add(templateFactory.create(
                        templateName(method, apiPath, operation),
                        request,
                        metadata(method, operation),
                        !options.disabledByDefault()));
            } catch (RuntimeException exception) {
                errors.add(new ImportError(location, "Skipped invalid operation: " + exception.getMessage()));
                skipped[0]++;
            }
        }
    }

    private Endpoint endpoint(JsonNode root, ImportOptions options, List<ImportWarning> warnings) {
        if (root.has("swagger")) {
            String scheme = firstScheme(root.path("schemes"), options.defaultScheme());
            String host = textOrDefault(root.path("host"), options.defaultHost());
            int port = defaultPortForScheme(scheme);
            String basePath = options.basePathOverride().isBlank()
                    ? normalizeBasePath(root.path("basePath").asText(""))
                    : options.basePathOverride();
            if (!root.has("host")) {
                warnings.add(new ImportWarning("servers", "Swagger host missing; using import defaults."));
                port = options.defaultPort();
            }
            return new Endpoint(scheme, host, port, basePath);
        }
        JsonNode servers = root.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            return endpointFromServerUrl(servers.get(0).path("url").asText(""), options, warnings, "servers[0]");
        }
        warnings.add(new ImportWarning("servers", "OpenAPI server URL missing; using import defaults."));
        return new Endpoint(options.defaultScheme(), options.defaultHost(), options.defaultPort(),
                options.basePathOverride());
    }

    private Endpoint operationEndpoint(
            Endpoint fallback,
            JsonNode operation,
            ImportOptions options,
            List<ImportWarning> warnings,
            String location) {
        JsonNode servers = operation.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            return endpointFromServerUrl(servers.get(0).path("url").asText(""), options, warnings, location + " servers[0]");
        }
        if (!options.basePathOverride().isBlank()) {
            return new Endpoint(fallback.scheme(), fallback.host(), fallback.port(), options.basePathOverride());
        }
        return fallback;
    }

    private Endpoint endpointFromServerUrl(
            String serverUrl,
            ImportOptions options,
            List<ImportWarning> warnings,
            String location) {
        if (serverUrl == null || serverUrl.isBlank()) {
            warnings.add(new ImportWarning(location, "Server URL blank; using import defaults."));
            return new Endpoint(options.defaultScheme(), options.defaultHost(), options.defaultPort(),
                    options.basePathOverride());
        }
        if (serverUrl.contains("{")) {
            warnings.add(new ImportWarning(location, "Templated server URL is not expanded; using import defaults."));
            return new Endpoint(options.defaultScheme(), options.defaultHost(), options.defaultPort(),
                    options.basePathOverride());
        }
        try {
            URI uri = new URI(serverUrl.strip());
            if (uri.getHost() == null) {
                String basePath = options.basePathOverride().isBlank()
                        ? normalizeBasePath(uri.getPath())
                        : options.basePathOverride();
                return new Endpoint(options.defaultScheme(), options.defaultHost(), options.defaultPort(), basePath);
            }
            String scheme = uri.getScheme() == null ? options.defaultScheme() : uri.getScheme();
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPortForScheme(scheme);
            String basePath = options.basePathOverride().isBlank()
                    ? normalizeBasePath(uri.getPath())
                    : options.basePathOverride();
            return new Endpoint(scheme, uri.getHost(), port, basePath);
        } catch (URISyntaxException exception) {
            warnings.add(new ImportWarning(location, "Server URL could not be parsed; using import defaults."));
            return new Endpoint(options.defaultScheme(), options.defaultHost(), options.defaultPort(),
                    options.basePathOverride());
        }
    }

    private String buildPathAndQuery(
            String apiPath,
            List<JsonNode> parameters,
            ImportOptions options,
            List<ImportWarning> warnings,
            String location) {
        String path = options.generatePlaceholdersForPathParams()
                ? replacePathParams(apiPath, warnings, location)
                : apiPath;
        List<String> query = new ArrayList<>();
        for (JsonNode parameter : parameters) {
            String in = parameter.path("in").asText("");
            String name = parameter.path("name").asText("");
            if ("query".equals(in)) {
                if (options.generatePlaceholdersForQueryParams()) {
                    query.add(name + "=" + variableMapper.placeholderFor(name, warnings, location + " query"));
                } else {
                    warnings.add(new ImportWarning(location, "Query parameter omitted: " + name));
                }
            }
        }
        if (query.isEmpty()) {
            return path;
        }
        return path + "?" + String.join("&", query);
    }

    private String replacePathParams(String apiPath, List<ImportWarning> warnings, String location) {
        String result = apiPath;
        int start = result.indexOf('{');
        while (start >= 0) {
            int end = result.indexOf('}', start + 1);
            if (end < 0) {
                break;
            }
            String paramName = result.substring(start + 1, end);
            String replacement = variableMapper.placeholderFor(paramName, warnings, location + " path");
            result = result.substring(0, start) + replacement + result.substring(end + 1);
            start = result.indexOf('{', start + replacement.length());
        }
        return result;
    }

    private RequestBody requestBody(
            JsonNode operation,
            ImportOptions options,
            List<ImportWarning> warnings,
            String location) {
        JsonNode requestBody = operation.path("requestBody");
        if (requestBody.isMissingNode()) {
            JsonNode bodyParameter = parameters(operation.path("parameters")).stream()
                    .filter(parameter -> "body".equals(parameter.path("in").asText("")))
                    .findFirst()
                    .orElse(null);
            if (bodyParameter == null) {
                return RequestBody.empty();
            }
            JsonNode example = bodyParameter.path("schema").path("example");
            if (!example.isMissingNode()) {
                return new RequestBody(writeJson(placeholderizeExample(example, options, warnings, location)), "");
            }
            warnings.add(new ImportWarning(location, "Body parameter has no example; imported empty body."));
            return RequestBody.empty();
        }
        JsonNode content = requestBody.path("content");
        if (!content.isObject() || !content.fields().hasNext()) {
            warnings.add(new ImportWarning(location, "Request body content is missing or unsupported."));
            return RequestBody.empty();
        }
        Map.Entry<String, JsonNode> contentEntry = content.fields().next();
        String contentType = contentEntry.getKey();
        JsonNode media = contentEntry.getValue();
        JsonNode example = media.path("example");
        if (example.isMissingNode()) {
            example = firstExampleValue(media.path("examples"));
        }
        if (!example.isMissingNode()) {
            return new RequestBody(writeJson(placeholderizeExample(example, options, warnings, location)), contentType);
        }
        warnings.add(new ImportWarning(location, "Request body has no example; imported empty body."));
        return new RequestBody("", contentType);
    }

    private JsonNode placeholderizeExample(
            JsonNode example,
            ImportOptions options,
            List<ImportWarning> warnings,
            String location) {
        if (!options.generatePlaceholdersForRequestBodyExamples()) {
            return example;
        }
        if (example.isObject()) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            example.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    object.put(entry.getKey(), variableMapper.placeholderFor(entry.getKey(), warnings, location + " body"));
                } else {
                    object.set(entry.getKey(), placeholderizeExample(entry.getValue(), options, warnings, location));
                }
            });
            return object;
        }
        if (example.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            example.forEach(item -> array.add(placeholderizeExample(item, options, warnings, location)));
            return array;
        }
        return example;
    }

    private JsonNode firstExampleValue(JsonNode examples) {
        if (!examples.isObject()) {
            return MissingNode.getInstance();
        }
        Iterator<JsonNode> values = examples.elements();
        if (!values.hasNext()) {
            return MissingNode.getInstance();
        }
        return values.next().path("value");
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Could not serialize request body example", exception);
        }
    }

    private List<JsonNode> parameters(JsonNode parameters) {
        if (!parameters.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        parameters.forEach(result::add);
        return result;
    }

    private String templateName(String method, String apiPath, JsonNode operation) {
        String operationId = operation.path("operationId").asText("");
        if (!operationId.isBlank()) {
            return operationId;
        }
        return method.toUpperCase(Locale.ROOT) + " " + apiPath;
    }

    private OperationMetadata metadata(String method, JsonNode operation) {
        String normalized = method.toUpperCase(Locale.ROOT);
        OperationType type = switch (normalized) {
            case "GET", "HEAD", "OPTIONS" -> OperationType.READ_ONLY;
            case "PUT", "DELETE" -> OperationType.STATE_CHANGING;
            case "POST", "PATCH" -> OperationType.STATE_CHANGING;
            default -> OperationType.UNKNOWN;
        };
        boolean stateChanging = type == OperationType.STATE_CHANGING || type == OperationType.CONSUMABLE;
        String description = operation.path("summary").asText(operation.path("description").asText(""));
        return new OperationMetadata(type, stateChanging, false, description);
    }

    private String firstScheme(JsonNode schemes, String fallback) {
        if (schemes.isArray() && !schemes.isEmpty()) {
            return schemes.get(0).asText(fallback);
        }
        return fallback;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        String value = node.asText("");
        return value.isBlank() ? fallback : value;
    }

    private String combinePaths(String basePath, String pathAndQuery) {
        String base = normalizeBasePath(basePath);
        if (base.isBlank() || "/".equals(base)) {
            return pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        }
        String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private String normalizeBasePath(String value) {
        if (value == null || value.isBlank() || "/".equals(value.strip())) {
            return "";
        }
        String normalized = value.strip();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private int defaultPortForScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }

    private Iterable<Map.Entry<String, JsonNode>> iterable(Iterator<Map.Entry<String, JsonNode>> iterator) {
        return () -> iterator;
    }

    private record Endpoint(String scheme, String host, int port, String basePath) {
    }

    private record RequestBody(String body, String contentType) {
        private static RequestBody empty() {
            return new RequestBody("", "");
        }

        private java.util.Optional<String> contentTypeHeader() {
            return contentType == null || contentType.isBlank()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(contentType);
        }
    }
}
