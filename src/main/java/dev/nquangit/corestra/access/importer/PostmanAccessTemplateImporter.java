package dev.nquangit.corestra.access.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Imports Postman Collection v2.1 JSON documents into access templates.
 */
public final class PostmanAccessTemplateImporter implements AccessTemplateImporter {
    private final ObjectMapper objectMapper;
    private final ImportedTemplateFactory templateFactory;
    private final ImportVariableMapper variableMapper;

    /**
     * Creates a Postman importer with default services.
     */
    public PostmanAccessTemplateImporter() {
        this(new UuidAccessIdGenerator());
    }

    /**
     * Creates a Postman importer with deterministic ID generation.
     *
     * @param idGenerator template ID generator
     */
    public PostmanAccessTemplateImporter(AccessIdGenerator idGenerator) {
        this(
                new ObjectMapper(),
                new ImportedTemplateFactory(
                        idGenerator,
                        new AccessRequestTemplateAnalyzer(new TemplateRenderer(
                                new VariableResolver(new DefaultRuntimeValueProvider()))),
                        new RequestTemplateHasher()),
                new ImportVariableMapper());
    }

    PostmanAccessTemplateImporter(
            ObjectMapper objectMapper,
            ImportedTemplateFactory templateFactory,
            ImportVariableMapper variableMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.templateFactory = Objects.requireNonNull(templateFactory, "templateFactory");
        this.variableMapper = Objects.requireNonNull(variableMapper, "variableMapper");
    }

    /**
     * Imports Postman collection JSON templates.
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
        JsonNode root;
        try {
            root = objectMapper.readTree(source.content());
        } catch (JsonProcessingException exception) {
            errors.add(new ImportError(source.name(), "Invalid Postman collection JSON: " + exception.getOriginalMessage()));
            return new ImportResult(List.of(), warnings, errors, 1);
        }
        if (!root.path("item").isArray()) {
            errors.add(new ImportError(source.name(), "Postman collection has no item array."));
            return new ImportResult(List.of(), warnings, errors, 1);
        }
        List<AccessRequestTemplate> templates = new ArrayList<>();
        int[] skipped = new int[]{0};
        warnEvents(root, source.name(), warnings);
        importItems(root.path("item"), "", resolvedOptions, templates, warnings, errors, skipped);
        return new ImportResult(templates, warnings, errors, skipped[0]);
    }

    private void importItems(
            JsonNode items,
            String folder,
            ImportOptions options,
            List<AccessRequestTemplate> templates,
            List<ImportWarning> warnings,
            List<ImportError> errors,
            int[] skipped) {
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            String name = item.path("name").asText("Imported request");
            String location = folder.isBlank() ? name : folder + " / " + name;
            warnEvents(item, location, warnings);
            if (item.path("item").isArray()) {
                importItems(item.path("item"), location, options, templates, warnings, errors, skipped);
                continue;
            }
            JsonNode request = item.path("request");
            if (request.isMissingNode()) {
                warnings.add(new ImportWarning(location, "Skipped item without request."));
                skipped[0]++;
                continue;
            }
            try {
                templates.add(importRequest(location, request, options, warnings));
            } catch (RuntimeException exception) {
                errors.add(new ImportError(location, "Skipped invalid Postman request: " + exception.getMessage()));
                skipped[0]++;
            }
        }
    }

    private AccessRequestTemplate importRequest(
            String name,
            JsonNode request,
            ImportOptions options,
            List<ImportWarning> warnings) {
        if (request.isTextual()) {
            ParsedUrl parsedUrl = parseUrl(
                    variableMapper.replacePostmanVariables(request.asText(), warnings, name + " url"),
                    options,
                    warnings,
                    name);
            StoredRequestTemplate stored = new StoredRequestTemplate(
                    "GET",
                    parsedUrl.scheme(),
                    parsedUrl.host(),
                    parsedUrl.port(),
                    parsedUrl.pathAndQuery(),
                    List.of(),
                    "");
            return templateFactory.create(name, stored, metadata("GET"), !options.disabledByDefault());
        }
        if (!request.path("auth").isMissingNode() && !request.path("auth").isNull()) {
            warnings.add(new ImportWarning(name, "Postman auth helper was not executed or imported."));
        }
        String method = request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        ParsedUrl parsedUrl = parseUrl(request.path("url"), options, warnings, name);
        List<String> headers = headers(request.path("header"), warnings, name);
        Body body = body(request.path("body"), warnings, name);
        if (!body.contentType().isBlank() && headers.stream()
                .noneMatch(header -> header.toLowerCase(Locale.ROOT).startsWith("content-type:"))) {
            headers = new ArrayList<>(headers);
            headers.add("Content-Type: " + body.contentType());
        }
        StoredRequestTemplate stored = new StoredRequestTemplate(
                method,
                parsedUrl.scheme(),
                parsedUrl.host(),
                parsedUrl.port(),
                parsedUrl.pathAndQuery(),
                headers,
                body.value());
        return templateFactory.create(name, stored, metadata(method), !options.disabledByDefault());
    }

    private List<String> headers(JsonNode headers, List<ImportWarning> warnings, String location) {
        if (!headers.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode header : headers) {
            if (header.path("disabled").asBoolean(false)) {
                continue;
            }
            String key = header.path("key").asText("");
            if (key.isBlank()) {
                continue;
            }
            String value = variableMapper.replacePostmanVariables(
                    header.path("value").asText(""),
                    warnings,
                    location + " header " + key);
            result.add(key + ": " + value);
        }
        return List.copyOf(result);
    }

    private Body body(JsonNode body, List<ImportWarning> warnings, String location) {
        if (body.isMissingNode() || body.isNull()) {
            return Body.empty();
        }
        String mode = body.path("mode").asText("");
        if ("raw".equals(mode)) {
            String raw = variableMapper.replacePostmanVariables(body.path("raw").asText(""), warnings, location + " body");
            String contentType = body.path("options").path("raw").path("language").asText("").equalsIgnoreCase("json")
                    ? "application/json"
                    : "";
            return new Body(raw, contentType);
        }
        if ("urlencoded".equals(mode)) {
            List<String> pairs = new ArrayList<>();
            for (JsonNode item : body.path("urlencoded")) {
                if (item.path("disabled").asBoolean(false)) {
                    continue;
                }
                String key = item.path("key").asText("");
                String value = variableMapper.replacePostmanVariables(
                        item.path("value").asText(""),
                        warnings,
                        location + " body");
                if (!key.isBlank()) {
                    pairs.add(key + "=" + value);
                }
            }
            return new Body(String.join("&", pairs), "application/x-www-form-urlencoded");
        }
        warnings.add(new ImportWarning(location, "Unsupported Postman body mode was not imported: " + mode));
        return Body.empty();
    }

    private ParsedUrl parseUrl(JsonNode url, ImportOptions options, List<ImportWarning> warnings, String location) {
        if (url.isTextual()) {
            return parseUrl(variableMapper.replacePostmanVariables(url.asText(), warnings, location + " url"),
                    options, warnings, location);
        }
        if (!url.isObject()) {
            warnings.add(new ImportWarning(location, "URL missing; using import defaults."));
            return new ParsedUrl(options.defaultScheme(), options.defaultHost(), options.defaultPort(), "/");
        }
        String scheme = url.path("protocol").asText(options.defaultScheme());
        String host = joinUrlParts(url.path("host"), ".", warnings, location + " host");
        if (host.isBlank()) {
            host = options.defaultHost();
            warnings.add(new ImportWarning(location, "URL host missing; using import default host."));
        }
        String path = "/" + joinUrlParts(url.path("path"), "/", warnings, location + " path");
        if ("/".equals(path) && url.path("raw").isTextual()) {
            return parseUrl(variableMapper.replacePostmanVariables(url.path("raw").asText(), warnings, location + " url"),
                    options, warnings, location);
        }
        List<String> query = new ArrayList<>();
        for (JsonNode queryParam : url.path("query")) {
            if (queryParam.path("disabled").asBoolean(false)) {
                continue;
            }
            String key = queryParam.path("key").asText("");
            if (key.isBlank()) {
                continue;
            }
            String value = variableMapper.replacePostmanVariables(
                    queryParam.path("value").asText(""),
                    warnings,
                    location + " query");
            query.add(key + "=" + value);
        }
        int port = defaultPortForScheme(scheme);
        return new ParsedUrl(scheme, host, port, query.isEmpty() ? path : path + "?" + String.join("&", query));
    }

    private ParsedUrl parseUrl(String rawUrl, ImportOptions options, List<ImportWarning> warnings, String location) {
        String raw = Objects.requireNonNullElse(rawUrl, "").strip();
        if (raw.isBlank()) {
            warnings.add(new ImportWarning(location, "URL blank; using import defaults."));
            return new ParsedUrl(options.defaultScheme(), options.defaultHost(), options.defaultPort(), "/");
        }
        if (raw.contains("://") && !raw.contains("{{")) {
            try {
                URI uri = new URI(raw);
                String scheme = uri.getScheme() == null ? options.defaultScheme() : uri.getScheme();
                String host = uri.getHost() == null ? options.defaultHost() : uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : defaultPortForScheme(scheme);
                String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
                return new ParsedUrl(scheme, host, port, path + query);
            } catch (URISyntaxException exception) {
                warnings.add(new ImportWarning(location, "URL could not be parsed with URI parser; using fallback parser."));
            }
        }

        String scheme = options.defaultScheme();
        String remainder = raw;
        int schemeIndex = raw.indexOf("://");
        if (schemeIndex > 0) {
            scheme = raw.substring(0, schemeIndex);
            remainder = raw.substring(schemeIndex + 3);
        }
        if (remainder.startsWith("/")) {
            return new ParsedUrl(scheme, options.defaultHost(), options.defaultPort(), remainder);
        }
        int slashIndex = indexOfPathStart(remainder);
        String hostPort = slashIndex < 0 ? remainder : remainder.substring(0, slashIndex);
        String pathAndQuery = slashIndex < 0 ? "/" : remainder.substring(slashIndex);
        if (!pathAndQuery.startsWith("/") && !pathAndQuery.startsWith("?")) {
            pathAndQuery = "/" + pathAndQuery;
        }
        if (pathAndQuery.startsWith("?")) {
            pathAndQuery = "/" + pathAndQuery;
        }
        String host = hostPort;
        int port = defaultPortForScheme(scheme);
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < hostPort.length() - 1 && !hostPort.contains("{{")) {
            host = hostPort.substring(0, colonIndex);
            try {
                port = Integer.parseInt(hostPort.substring(colonIndex + 1));
            } catch (NumberFormatException exception) {
                warnings.add(new ImportWarning(location, "URL port invalid; using default port."));
            }
        }
        if (host.isBlank()) {
            host = options.defaultHost();
        }
        return new ParsedUrl(scheme, host, port, pathAndQuery);
    }

    private String joinUrlParts(JsonNode parts, String delimiter, List<ImportWarning> warnings, String location) {
        if (parts.isTextual()) {
            return variableMapper.replacePostmanVariables(parts.asText(), warnings, location);
        }
        if (!parts.isArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode part : parts) {
            String value = variableMapper.replacePostmanVariables(part.asText(""), warnings, location);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return String.join(delimiter, values);
    }

    private void warnEvents(JsonNode item, String location, List<ImportWarning> warnings) {
        JsonNode events = item.path("event");
        if (!events.isArray()) {
            return;
        }
        for (JsonNode event : events) {
            String listen = event.path("listen").asText("script");
            warnings.add(new ImportWarning(location, "Postman " + listen + " script was not executed or imported."));
        }
    }

    private OperationMetadata metadata(String method) {
        String normalized = method.toUpperCase(Locale.ROOT);
        OperationType type = switch (normalized) {
            case "GET", "HEAD", "OPTIONS" -> OperationType.READ_ONLY;
            case "POST", "PUT", "PATCH", "DELETE" -> OperationType.STATE_CHANGING;
            default -> OperationType.UNKNOWN;
        };
        return new OperationMetadata(type, type == OperationType.STATE_CHANGING, false, "");
    }

    private int indexOfPathStart(String value) {
        int slashIndex = value.indexOf('/');
        int queryIndex = value.indexOf('?');
        if (slashIndex < 0) {
            return queryIndex;
        }
        if (queryIndex < 0) {
            return slashIndex;
        }
        return Math.min(slashIndex, queryIndex);
    }

    private int defaultPortForScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }

    private record ParsedUrl(String scheme, String host, int port, String pathAndQuery) {
    }

    private record Body(String value, String contentType) {
        private static Body empty() {
            return new Body("", "");
        }
    }
}
