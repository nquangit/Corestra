package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.ui.access.AccessUiController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Supplies reusable variable keys to the Flowise injection editor. */
final class InjectionVariableCatalog {
    private static final RedactionService REDACTION = new RedactionService();
    private final AccessUiController accessController;

    InjectionVariableCatalog(AccessUiController accessController) {
        this.accessController = accessController;
    }

    List<VariableOption> options(FlowDefinition flow, FlowStep currentStep) {
        LinkedHashMap<String, VariableOption> options = new LinkedHashMap<>();
        addPreviousExtractors(options, flow, currentStep);
        if (accessController != null) {
            for (AuthProfile profile : accessController.authProfiles()) {
                if (!profile.enabled()) {
                    continue;
                }
                profile.credentials().forEach((key, value) -> add(options,
                        new VariableOption("credentials." + key, "Credentials", profile.name(), value, true)));
                profile.state().variables().forEach((key, value) -> add(options,
                        new VariableOption("auth." + key, "Auth state", profile.name(), value, true)));
                profile.state().headers().forEach((key, value) -> add(options,
                        new VariableOption("auth.header." + key, "Auth headers", profile.name(), value, true)));
                profile.state().cookies().forEach((key, value) -> add(options,
                        new VariableOption("auth.cookie." + key, "Auth cookies", profile.name(), value, true)));
            }
            for (DataProfile profile : accessController.dataProfiles()) {
                if (!profile.enabled()) {
                    continue;
                }
                profile.values().forEach((key, value) -> add(options,
                        new VariableOption("data." + key, "Data", profile.name(), value,
                                REDACTION.isSensitiveKey(key))));
            }
        }
        return List.copyOf(options.values());
    }

    private static void addPreviousExtractors(
            Map<String, VariableOption> options,
            FlowDefinition flow,
            FlowStep currentStep) {
        if (flow == null || currentStep == null) {
            return;
        }
        boolean found = false;
        for (FlowStep candidate : flow.steps()) {
            if (candidate.id().equals(currentStep.id())) {
                found = true;
                break;
            }
            if (!candidate.enabled()) {
                continue;
            }
            for (ExtractRule rule : candidate.extractRules()) {
                if (rule.enabled()) {
                    VariableOption option = new VariableOption(
                            rule.targetVariableName(), "Previous extracts",
                            candidate.name() + " / " + rule.name(), "", false);
                    // The latest extractor wins, matching execution-context behavior.
                    options.remove(option.key());
                    options.put(option.key(), option);
                }
            }
        }
        if (!found) {
            options.clear();
        }
    }

    private static void add(Map<String, VariableOption> options, VariableOption candidate) {
        VariableOption existing = options.get(candidate.key());
        if (existing == null) {
            options.put(candidate.key(), candidate);
            return;
        }
        if (!existing.group().equals(candidate.group()) || existing.source().contains(candidate.source())) {
            return;
        }
        options.put(candidate.key(), new VariableOption(
                existing.key(), existing.group(), existing.source() + ", " + candidate.source(),
                existing.previewValue(), existing.sensitive()));
    }

    record VariableOption(
            String key,
            String group,
            String source,
            String previewValue,
            boolean sensitive) {
        VariableOption {
            key = normalize(key);
            group = normalize(group);
            source = normalize(source);
            previewValue = Objects.requireNonNullElse(previewValue, "");
        }

        @Override
        public String toString() {
            return key;
        }

        String displayLabel() {
            return group + "  |  " + key + (source.isEmpty() ? "" : "  —  " + source);
        }

        String displayPreview() {
            if (previewValue.isEmpty()) {
                return "No stored preview value";
            }
            if (sensitive) {
                return "Stored value: " + "•".repeat(Math.min(12, Math.max(4, previewValue.length())));
            }
            String value = previewValue.replace("\r", "\\r").replace("\n", "\\n");
            return "Stored value: " + (value.length() <= 80 ? value : value.substring(0, 77) + "...");
        }

        private static String normalize(String value) {
            return Objects.requireNonNullElse(value, "").strip();
        }
    }
}
