package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.Objects;
import java.util.Set;

/**
 * Creates access request templates from stored requests and flow steps.
 */
public final class AccessRequestTemplateFactory {
    private static final String TEMPLATE_PREFIX = "tmpl";

    private final AccessIdGenerator idGenerator;
    private final AccessRequestTemplateAnalyzer analyzer;
    private final RequestTemplateHasher hasher;
    private final RenderMode discoveryMode;

    /**
     * Creates a factory with default ID generation, placeholder analysis, and hashing.
     */
    public AccessRequestTemplateFactory() {
        this(
                new UuidAccessIdGenerator(),
                new AccessRequestTemplateAnalyzer(new TemplateRenderer(
                        new VariableResolver(new DefaultRuntimeValueProvider()))),
                new RequestTemplateHasher(),
                RenderMode.STRICT);
    }

    /**
     * Creates a factory.
     *
     * @param idGenerator identifier generator
     * @param analyzer placeholder analyzer
     * @param hasher request template hasher
     */
    public AccessRequestTemplateFactory(
            AccessIdGenerator idGenerator,
            AccessRequestTemplateAnalyzer analyzer,
            RequestTemplateHasher hasher) {
        this(idGenerator, analyzer, hasher, RenderMode.STRICT);
    }

    /**
     * Creates a factory.
     *
     * @param idGenerator identifier generator
     * @param analyzer placeholder analyzer
     * @param hasher request template hasher
     * @param discoveryMode render mode used for placeholder discovery
     */
    public AccessRequestTemplateFactory(
            AccessIdGenerator idGenerator,
            AccessRequestTemplateAnalyzer analyzer,
            RequestTemplateHasher hasher,
            RenderMode discoveryMode) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
    }

    /**
     * Creates an access request template from a stored request.
     *
     * @param name template display name
     * @param request stored request template
     * @param operationMetadata operation metadata, or null for unknown metadata
     * @return access request template
     */
    public AccessRequestTemplate fromStoredRequest(
            String name,
            StoredRequestTemplate request,
            OperationMetadata operationMetadata) {
        return createTemplate(name, request, operationMetadata, "", "");
    }

    /**
     * Creates an access request template from a flow step.
     *
     * @param flow source flow
     * @param step source step
     * @param operationMetadata operation metadata, or null for unknown metadata
     * @return access request template
     */
    public AccessRequestTemplate fromFlowStep(
            FlowDefinition flow,
            FlowStep step,
            OperationMetadata operationMetadata) {
        Objects.requireNonNull(flow, "flow");
        return fromFlowStep(flow.id(), step, operationMetadata);
    }

    /**
     * Creates an access request template from a flow step and source flow identifier.
     *
     * @param flowId source flow identifier
     * @param step source step
     * @param operationMetadata operation metadata, or null for unknown metadata
     * @return access request template
     */
    public AccessRequestTemplate fromFlowStep(
            String flowId,
            FlowStep step,
            OperationMetadata operationMetadata) {
        Objects.requireNonNull(step, "step");
        return createTemplate(step.name(), step.requestTemplate(), operationMetadata,
                normalizeOptionalText(flowId), step.id());
    }

    private AccessRequestTemplate createTemplate(
            String name,
            StoredRequestTemplate request,
            OperationMetadata operationMetadata,
            String sourceFlowId,
            String sourceStepId) {
        Objects.requireNonNull(request, "request");
        String id = idGenerator.newId(TEMPLATE_PREFIX);
        OperationMetadata metadata = operationMetadata == null ? OperationMetadata.unknown() : operationMetadata;
        String requestHash = hasher.hash(request);

        AccessRequestTemplate emptyTemplate = new AccessRequestTemplate(
                id,
                name,
                request,
                Set.of(),
                metadata,
                sourceFlowId,
                sourceStepId,
                requestHash,
                true);
        Set<ScopedVariableRef> placeholders = analyzer.discoverPlaceholders(emptyTemplate, discoveryMode);
        return new AccessRequestTemplate(
                id,
                name,
                request,
                placeholders,
                metadata,
                sourceFlowId,
                sourceStepId,
                requestHash,
                true);
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
