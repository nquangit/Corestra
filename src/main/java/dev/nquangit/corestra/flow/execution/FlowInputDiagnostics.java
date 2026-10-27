package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explains missing injection inputs using producer metadata from this run, without response or variable values. */
public final class FlowInputDiagnostics {
    private FlowInputDiagnostics() { }

    /** Returns producer names and statuses for missing keys; never includes captured bodies or resolved values. */
    public static String describe(FlowDefinition flow, FlowStep consumer, ExecutionContext context) {
        List<String> descriptions = new ArrayList<>();
        for (String key : FlowInputValidator.missingStepVariables(consumer, context.variables())) {
            descriptions.add(describeKey(flow, consumer, context, key));
        }
        return String.join("; ", descriptions);
    }

    private static String describeKey(FlowDefinition flow, FlowStep consumer, ExecutionContext context, String key) {
        FlowStep producer = null;
        ExtractRule extractor = null;
        for (FlowStep candidate : flow.steps()) {
            if (candidate.id().equals(consumer.id())) break;
            if (!candidate.enabled()) continue;
            for (ExtractRule rule : candidate.extractRules()) {
                if (rule.enabled() && FlowInputValidator.scopedOutputs(Map.of(rule.targetVariableName(), "declared"),
                        context.metadata().get(FlowInputValidator.OUTPUT_SCOPE)).containsKey(key)) {
                    producer = candidate;
                    extractor = rule;
                }
            }
        }
        if (producer == null) return key + ": no earlier enabled extractor declares this key; check input bindings and variable names";
        String source = key + ": producer '" + producer.name() + "' (" + producer.id() + "), extractor " + extractor.extractorType();
        var history = context.stepHistory().get(producer.id());
        if (history == null || history.isEmpty()) return source + " has no recorded execution in this context";
        var latest = history.getLast();
        if (latest.responseStatusCode() <= 0) return source + " has no HTTP response";
        String status = ", HTTP " + latest.responseStatusCode();
        String produced = FlowInputValidator.scopedOutputs(latest.extractedVariables(),
                context.metadata().get(FlowInputValidator.OUTPUT_SCOPE)).get(key);
        return source + status + (produced == null || produced.isBlank()
                ? ": did not extract a nonblank value; check this response and extractor, and the producer's retry condition"
                : ": earlier output is no longer available in the current context");
    }
}
