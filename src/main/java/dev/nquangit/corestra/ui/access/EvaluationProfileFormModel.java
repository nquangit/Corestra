package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.IndicatorType;
import dev.nquangit.corestra.access.evaluate.MaskingRule;
import dev.nquangit.corestra.access.evaluate.ResponseIndicator;
import dev.nquangit.corestra.access.evaluate.SensitiveFieldRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EvaluationProfileFormModel {
    private final String name;
    private final boolean enabled;
    private final List<ResponseIndicator> allowIndicators;
    private final List<ResponseIndicator> denyIndicators;
    private final List<SensitiveFieldRule> sensitiveFieldRules;
    private final List<MaskingRule> maskedFieldRules;

    EvaluationProfileFormModel(
            String name,
            boolean enabled,
            List<ResponseIndicator> allowIndicators,
            List<ResponseIndicator> denyIndicators,
            List<SensitiveFieldRule> sensitiveFieldRules,
            List<MaskingRule> maskedFieldRules) {
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.allowIndicators = copyList(allowIndicators, "allowIndicators");
        this.denyIndicators = copyList(denyIndicators, "denyIndicators");
        this.sensitiveFieldRules = copyList(sensitiveFieldRules, "sensitiveFieldRules");
        this.maskedFieldRules = copyList(maskedFieldRules, "maskedFieldRules");
    }

    static EvaluationProfileFormModel from(EvaluationProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new EvaluationProfileFormModel(
                profile.name(),
                profile.enabled(),
                profile.allowIndicators(),
                profile.denyIndicators(),
                profile.sensitiveFieldRules(),
                profile.maskedFieldRules());
    }

    static List<ResponseIndicator> parseIndicators(String text, String prefix) {
        ArrayList<ResponseIndicator> indicators = new ArrayList<>();
        for (String line : lines(text)) {
            String[] parts = line.split("\\|", -1);
            IndicatorType type;
            try {
                type = IndicatorType.valueOf(parts[0].strip());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid indicator type: " + parts[0].strip(), exception);
            }
            String selector = part(parts, 1);
            String expectedValue = part(parts, 2);
            String name = part(parts, 3);
            indicators.add(new ResponseIndicator(
                    prefix + "-" + (indicators.size() + 1),
                    name.isBlank() ? type.name() : name,
                    true,
                    type,
                    selector,
                    expectedValue));
        }
        return List.copyOf(indicators);
    }

    static List<SensitiveFieldRule> parseSensitiveRules(String text) {
        ArrayList<SensitiveFieldRule> rules = new ArrayList<>();
        for (String line : lines(text)) {
            String[] parts = line.split("\\|", -1);
            String jsonPath = part(parts, 0);
            String name = part(parts, 1);
            rules.add(new SensitiveFieldRule(
                    "sensitive-" + (rules.size() + 1),
                    name.isBlank() ? jsonPath : name,
                    true,
                    jsonPath));
        }
        return List.copyOf(rules);
    }

    static List<MaskingRule> parseMaskingRules(String text) {
        ArrayList<MaskingRule> rules = new ArrayList<>();
        for (String line : lines(text)) {
            String[] parts = line.split("\\|", -1);
            String jsonPath = part(parts, 0);
            String pattern = part(parts, 1);
            String name = part(parts, 2);
            rules.add(new MaskingRule(
                    "mask-" + (rules.size() + 1),
                    name.isBlank() ? jsonPath : name,
                    true,
                    jsonPath,
                    pattern.isBlank() ? "\\*+" : pattern));
        }
        return List.copyOf(rules);
    }

    static String indicatorsText(List<ResponseIndicator> indicators) {
        StringBuilder text = new StringBuilder();
        for (ResponseIndicator indicator : Objects.requireNonNullElse(indicators, List.<ResponseIndicator>of())) {
            if (!indicator.enabled()) {
                continue;
            }
            appendLine(text, indicator.type().name()
                    + "|" + indicator.selector()
                    + "|" + indicator.expectedValue()
                    + "|" + indicator.name());
        }
        return text.toString();
    }

    static String sensitiveRulesText(List<SensitiveFieldRule> rules) {
        StringBuilder text = new StringBuilder();
        for (SensitiveFieldRule rule : Objects.requireNonNullElse(rules, List.<SensitiveFieldRule>of())) {
            if (!rule.enabled()) {
                continue;
            }
            appendLine(text, rule.jsonPath() + "|" + rule.name());
        }
        return text.toString();
    }

    static String maskingRulesText(List<MaskingRule> rules) {
        StringBuilder text = new StringBuilder();
        for (MaskingRule rule : Objects.requireNonNullElse(rules, List.<MaskingRule>of())) {
            if (!rule.enabled()) {
                continue;
            }
            appendLine(text, rule.jsonPath() + "|" + rule.maskedValuePattern() + "|" + rule.name());
        }
        return text.toString();
    }

    EvaluationProfile toEvaluationProfile(String id) {
        return new EvaluationProfile(
                id,
                name,
                enabled,
                allowIndicators,
                denyIndicators,
                sensitiveFieldRules,
                maskedFieldRules);
    }

    String name() {
        return name;
    }

    boolean enabled() {
        return enabled;
    }

    List<ResponseIndicator> allowIndicators() {
        return allowIndicators;
    }

    List<ResponseIndicator> denyIndicators() {
        return denyIndicators;
    }

    List<SensitiveFieldRule> sensitiveFieldRules() {
        return sensitiveFieldRules;
    }

    List<MaskingRule> maskedFieldRules() {
        return maskedFieldRules;
    }

    private static List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ArrayList<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index].strip() : "";
    }

    private static void appendLine(StringBuilder text, String line) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(line);
    }

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<T> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, fieldName + " entry")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
