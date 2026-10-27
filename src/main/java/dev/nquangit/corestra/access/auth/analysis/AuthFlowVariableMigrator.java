package dev.nquangit.corestra.access.auth.analysis;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.InjectValueTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts legacy {@code auth.*} extractor outputs to canonical unscoped Auth State keys. */
public final class AuthFlowVariableMigrator {
    /**
     * Renames safe legacy extractor outputs and later Flowise injection references atomically.
     *
     * <p>A key is left unchanged when the flow already extracts its unscoped equivalent, because
     * automatically merging those variables could change overwrite behavior.</p>
     *
     * @param flow flow to migrate
     * @return migration result containing an immutable replacement flow
     */
    public MigrationResult migrate(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow");
        Set<String> existingTargets = new LinkedHashSet<>();
        flow.steps().forEach(step -> step.extractRules().forEach(rule ->
                existingTargets.add(rule.targetVariableName())));

        LinkedHashMap<String, String> safeRenames = new LinkedHashMap<>();
        ArrayList<String> warnings = new ArrayList<>();
        for (String target : existingTargets) {
            if (!target.startsWith("auth.") || target.length() == "auth.".length()) {
                continue;
            }
            String canonical = target.substring("auth.".length());
            if (existingTargets.contains(canonical)) {
                warnings.add("Skipped '" + target + "' because the flow already extracts '" + canonical + "'.");
            } else {
                safeRenames.put(target, canonical);
            }
        }
        if (safeRenames.isEmpty()) {
            return new MigrationResult(flow, 0, 0, warnings);
        }

        ArrayList<FlowStep> migratedSteps = new ArrayList<>();
        LinkedHashMap<String, String> availableRenames = new LinkedHashMap<>();
        int renamedExtractors = 0;
        int renamedReferences = 0;
        for (FlowStep step : flow.steps()) {
            List<InjectRule> injectRules = new ArrayList<>();
            for (InjectRule rule : step.injectRules()) {
                if (rule.valueSourceType() == InjectRule.ValueSourceType.TEMPLATE
                        && rule.referencedVariableKeys().stream().anyMatch(availableRenames::containsKey)) {
                    injectRules.add(new InjectRule(
                            rule.id(), rule.name(), rule.enabled(), rule.target(), rule.targetSelector(),
                            rule.valueSourceType(), "", InjectValueTemplate.rename(rule.staticValue(), availableRenames),
                            rule.writeStrategy(), rule.transformChain()));
                    renamedReferences++;
                    continue;
                }
                String replacement = rule.valueSourceType() == InjectRule.ValueSourceType.VARIABLE
                        ? availableRenames.get(rule.valueSourceKey())
                        : null;
                if (replacement == null) {
                    injectRules.add(rule);
                } else {
                    injectRules.add(copyWithVariable(rule, replacement));
                    renamedReferences++;
                }
            }

            List<ExtractRule> extractRules = new ArrayList<>();
            for (ExtractRule rule : step.extractRules()) {
                String replacement = safeRenames.get(rule.targetVariableName());
                if (replacement == null) {
                    extractRules.add(rule);
                } else {
                    extractRules.add(copyWithTarget(rule, replacement));
                    if (rule.enabled()) {
                        availableRenames.put(rule.targetVariableName(), replacement);
                    }
                    renamedExtractors++;
                }
            }
            migratedSteps.add(new FlowStep(
                    step.id(), step.name(), step.enabled(), step.notes(), step.requestTemplate(),
                    extractRules, injectRules, step.preconditions(), step.errorStrategy(), step.lastResponseRaw(),
                    step.triggerRule(), step.includeInAggregation(), step.conditionActionRules(), step.sendModeOverride(), step.manualInjectRulesEnabled()));
        }

        FlowDefinition migrated = new FlowDefinition(
                flow.id(), flow.name(), flow.description(), flow.enabled(), migratedSteps,
                flow.rules(), flow.aggregation(), flow.triggerConfig());
        return new MigrationResult(migrated, renamedExtractors, renamedReferences, warnings);
    }

    private static ExtractRule copyWithTarget(ExtractRule rule, String target) {
        return new ExtractRule(
                rule.id(), rule.name(), rule.enabled(), rule.sourceSide(), rule.extractorType(),
                rule.sourceSelector(), target, rule.transformChain(), rule.regexSource(), rule.regexGroupIndex(), rule.ruleType(), rule.jsonResultMode());
    }

    private static InjectRule copyWithVariable(InjectRule rule, String key) {
        return new InjectRule(
                rule.id(), rule.name(), rule.enabled(), rule.target(), rule.targetSelector(),
                rule.valueSourceType(), key, rule.staticValue(), rule.writeStrategy(), rule.transformChain());
    }

    /** Result of an atomic flow variable migration. */
    public record MigrationResult(
            FlowDefinition flow,
            int renamedExtractors,
            int renamedReferences,
            List<String> warnings) {
        /** Creates an immutable migration result. */
        public MigrationResult {
            Objects.requireNonNull(flow, "flow");
            warnings = List.copyOf(warnings);
        }

        /** Returns whether the replacement flow differs from the input contract. */
        public boolean changed() {
            return renamedExtractors > 0 || renamedReferences > 0;
        }
    }
}
