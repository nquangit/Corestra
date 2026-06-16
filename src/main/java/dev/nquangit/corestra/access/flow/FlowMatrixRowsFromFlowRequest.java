package dev.nquangit.corestra.access.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request to create Matrix rows from multiple selected steps in one Flowise flow.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowMatrixRowsFromFlowRequest {
    private final String flowId;
    private final String targetAuthProfileId;
    private final String dataProfileId;
    private final List<FlowStepMatrixSelection> selections;
    private final boolean createDisabledRows;

    /**
     * Creates a request.
     *
     * @param flowId source Flowise flow id
     * @param targetAuthProfileId target auth profile id
     * @param dataProfileId optional data profile id
     * @param selections selected steps
     * @param createDisabledRows whether generated Matrix rows should be disabled
     */
    @JsonCreator
    public FlowMatrixRowsFromFlowRequest(
            @JsonProperty("flowId") String flowId,
            @JsonProperty("targetAuthProfileId") String targetAuthProfileId,
            @JsonProperty("dataProfileId") String dataProfileId,
            @JsonProperty("selections") List<FlowStepMatrixSelection> selections,
            @JsonProperty("createDisabledRows") boolean createDisabledRows) {
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.targetAuthProfileId = normalizeRequiredText(targetAuthProfileId, "targetAuthProfileId");
        this.dataProfileId = normalizeOptionalText(dataProfileId);
        this.selections = copySelections(selections);
        this.createDisabledRows = createDisabledRows;
    }

    /**
     * Returns the source Flowise flow id.
     *
     * @return source flow id
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the target auth profile id.
     *
     * @return target auth profile id
     */
    public String targetAuthProfileId() {
        return targetAuthProfileId;
    }

    /**
     * Returns the optional data profile id.
     *
     * @return data profile id, or empty
     */
    public String dataProfileId() {
        return dataProfileId;
    }

    /**
     * Returns selected flow-step entries.
     *
     * @return immutable selections
     */
    public List<FlowStepMatrixSelection> selections() {
        return selections;
    }

    /**
     * Returns whether generated rows should be disabled.
     *
     * @return true when generated rows should be disabled
     */
    public boolean createDisabledRows() {
        return createDisabledRows;
    }

    private static List<FlowStepMatrixSelection> copySelections(List<FlowStepMatrixSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        ArrayList<FlowStepMatrixSelection> copied = new ArrayList<>();
        for (FlowStepMatrixSelection selection : selections) {
            copied.add(Objects.requireNonNull(selection, "selections must not contain null entries"));
        }
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
