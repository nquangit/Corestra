package dev.nquangit.corestra.ui.watch;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.RuleType;
import dev.nquangit.corestra.manual.ManualRequestCodec;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchDisplayOptions;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchNumberFormat;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchProbe;
import dev.nquangit.corestra.watch.WatchScope;
import dev.nquangit.corestra.watch.WatchTiming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Swing-free conversion between {@link WatchDefinition} and the values shown in the Watch editor.
 * The panel only copies widget values in and out of {@link EditorState}; every rebuild of the
 * immutable records happens here so it can be unit-tested.
 */
public final class WatchEditorModel {
    /** Extractor types offered for request variables. */
    public static final List<ExtractRule.ExtractorType> VARIABLE_TYPES = List.of(
            ExtractRule.ExtractorType.JSON_PATH, ExtractRule.ExtractorType.REGEX,
            ExtractRule.ExtractorType.HEADER, ExtractRule.ExtractorType.COOKIE, ExtractRule.ExtractorType.BODY_TEXT);

    /** Help text for expectation expressions (mirrors the {@link WatchExpectation} contract). */
    public static final String EXPRESSION_HELP = """
            Key pattern: glob over snapshot keys (* any run, ? one char), e.g. balance[*] or balance[0123].
            Expressions: exact decimals with + - * / ( ), unary minus, min(a,b), max(a,b), abs(a), round(a,scale).
            Variables: request variables as {{name}} or bare names, plus before and after of the checked key.
            DELTA_EQUALS: after - before = expression (± tolerance).  DELTA_BETWEEN: expression <= delta <= expression 2.
            AFTER_AT_LEAST: after >= expression.  CHANGED / UNCHANGED need no expression.
            Example (transfer with 1.1% fee, minimum 5000):  -{{amount}} - max({{amount}} * 0.011, 5000)""";

    private WatchEditorModel() {
    }

    /**
     * Splits a comma (or newline) separated list, trimming and dropping blanks.
     *
     * @param text text
     * @return values
     */
    public static List<String> parseList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,\\r\\n]+")).map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    /**
     * Joins a list for a single-line text field.
     *
     * @param values values
     * @return comma separated text
     */
    public static String joinList(List<String> values) {
        return String.join(", ", values);
    }

    /**
     * Everything the editor shows for one watch, as plain values.
     *
     * @param name           name
     * @param enabled        enabled
     * @param methods        comma separated methods
     * @param hostPattern    host regex
     * @param pathPattern    path regex
     * @param probeHost      probe target host ("" when no probe)
     * @param probePort      probe target port text
     * @param probeHttps     probe uses TLS
     * @param probeRaw       raw HTTP probe request ("" when no probe)
     * @param inheritHeaders comma separated inherited header names
     * @param variables      request variable rows
     * @param observations   observation rows
     * @param expectations   expectation rows
     * @param display        display options
     * @param timing         timing
     */
    public record EditorState(String name, boolean enabled, String methods, String hostPattern, String pathPattern,
                              String probeHost, String probePort, boolean probeHttps, String probeRaw,
                              String inheritHeaders, List<VariableDraft> variables,
                              List<WatchObservation> observations, List<WatchExpectation> expectations,
                              WatchDisplayOptions display, WatchTiming timing) {
        /**
         * Copies lists.
         */
        public EditorState {
            variables = List.copyOf(variables);
            observations = List.copyOf(observations);
            expectations = List.copyOf(expectations);
        }
    }

    /**
     * Converts a definition to editor values.
     *
     * @param watch definition
     * @return editor state
     */
    public static EditorState toState(WatchDefinition watch) {
        StoredRequestTemplate probe = watch.probe().request();
        return new EditorState(watch.name(), watch.enabled(), joinList(watch.scope().methods()),
                watch.scope().hostPattern(), watch.scope().pathPattern(),
                probe == null ? "" : probe.host(),
                probe == null ? "443" : Integer.toString(probe.port()),
                probe == null || "https".equalsIgnoreCase(probe.scheme()),
                probe == null ? "" : ManualRequestCodec.format(probe),
                joinList(watch.probe().inheritHeaders()),
                watch.requestVariables().stream().map(VariableDraft::fromRule).toList(),
                watch.observations(), watch.expectations(), watch.display(), watch.timing());
    }

    /**
     * Applies editor values on top of {@code base}, keeping its id and any field the editor does
     * not show (for example extract-rule transforms).
     *
     * @param base  definition being edited
     * @param state editor values
     * @return rebuilt definition
     * @throws IllegalArgumentException with a user-facing message when a value cannot be converted
     */
    public static WatchDefinition apply(WatchDefinition base, EditorState state) {
        List<ExtractRule> rules = new ArrayList<>();
        for (int i = 0; i < state.variables().size(); i++) {
            rules.add(state.variables().get(i).toRule(i + 1));
        }
        return base.withName(state.name())
                .withEnabled(state.enabled())
                .withScope(new WatchScope(parseList(state.methods()), state.hostPattern(), state.pathPattern()))
                .withProbe(new WatchProbe(probe(base.probe().request(), state), parseList(state.inheritHeaders())))
                .withRequestVariables(rules)
                .withObservations(state.observations())
                .withExpectations(state.expectations())
                .withDisplay(state.display())
                .withTiming(state.timing());
    }

    /**
     * Builds the probe template from host/port/TLS fields and raw HTTP text. Returns {@code previous}
     * unchanged when the editor still shows exactly what was loaded.
     *
     * @param previous loaded template or null
     * @param state    editor values
     * @return template, or null when the raw request is blank
     */
    static StoredRequestTemplate probe(StoredRequestTemplate previous, EditorState state) {
        String raw = state.probeRaw() == null ? "" : state.probeRaw();
        if (raw.isBlank()) {
            return null;
        }
        String host = state.probeHost() == null ? "" : state.probeHost().strip();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Probe: target host is required.");
        }
        int port;
        try {
            port = Integer.parseInt(state.probePort().strip());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Probe: port must be a number between 1 and 65535.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Probe: port must be a number between 1 and 65535.");
        }
        String scheme = state.probeHttps() ? "https" : "http";
        if (previous != null && raw.equals(ManualRequestCodec.format(previous))
                && previous.host().equalsIgnoreCase(host) && previous.port() == port
                && previous.scheme().equalsIgnoreCase(scheme)) {
            return previous;
        }
        String hostPart = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        try {
            return ManualRequestCodec.parse(scheme + "://" + hostPart + ":" + port, raw);
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage() == null ? "invalid target or request" : exception.getMessage();
            throw new IllegalArgumentException("Probe: " + message, exception);
        }
    }

    /**
     * Returns a disabled copy of {@code watch} with a new id and a "(copy)" name.
     *
     * @param watch source
     * @param newId id for the copy
     * @return copy
     */
    public static WatchDefinition duplicate(WatchDefinition watch, String newId) {
        return new WatchDefinition(newId, watch.name() + " (copy)", false, watch.scope(), watch.probe(),
                watch.requestVariables(), watch.observations(), watch.expectations(), watch.display(), watch.timing());
    }

    /**
     * Returns a new observation row.
     *
     * @param id id
     * @return observation
     */
    public static WatchObservation newObservation(String id) {
        return new WatchObservation(id, "value", WatchObservation.Source.JSON_SELECTOR, "$.value", "", "", "",
                WatchObservation.ValueType.NUMBER, WatchNumberFormat.automatic());
    }

    /**
     * Returns a new expectation row.
     *
     * @param id id
     * @return expectation
     */
    public static WatchExpectation newExpectation(String id) {
        return new WatchExpectation(id, true, "*", WatchExpectation.Type.CHANGED, "", "", "");
    }

    /**
     * Returns a new request variable row.
     *
     * @param id id
     * @return variable draft
     */
    public static VariableDraft newVariable(String id) {
        return new VariableDraft(id, true, "amount", ExtractRule.ExtractorType.JSON_PATH, "$.amount",
                ExtractRule.RegexSource.BODY, null, null);
    }

    /**
     * Editable request-variable row; converted to a REQUEST-side {@link ExtractRule} on save.
     *
     * @param id          rule id
     * @param enabled     enabled
     * @param variable    variable (and rule) name
     * @param type        extractor type
     * @param selector    selector, header/cookie name or regex
     * @param regexSource regex input section (REGEX only)
     * @param group       regex capture group, null for default
     * @param original    loaded rule whose hidden fields (transforms, JSON result mode) are kept, or null
     */
    public record VariableDraft(String id, boolean enabled, String variable, ExtractRule.ExtractorType type,
                                String selector, ExtractRule.RegexSource regexSource, Integer group,
                                ExtractRule original) {
        /**
         * Creates a row from a stored rule.
         *
         * @param rule rule
         * @return row
         */
        public static VariableDraft fromRule(ExtractRule rule) {
            return new VariableDraft(rule.id(), rule.enabled(), rule.targetVariableName(), rule.extractorType(),
                    rule.sourceSelector(), rule.regexSource(), rule.regexGroupIndex(), rule);
        }

        /**
         * Converts the row to a rule.
         *
         * @param position 1-based row number for messages
         * @return REQUEST-side extract rule
         */
        public ExtractRule toRule(int position) {
            String name = variable == null ? "" : variable.strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Request variable #" + position + ": variable name is required.");
            }
            ExtractRule.ExtractorType checkedType = Objects.requireNonNullElse(type, ExtractRule.ExtractorType.JSON_PATH);
            String checkedSelector = selector == null ? "" : selector.strip();
            if (checkedSelector.isEmpty() && checkedType != ExtractRule.ExtractorType.BODY_TEXT
                    && checkedType != ExtractRule.ExtractorType.BODY_RAW) {
                throw new IllegalArgumentException("Request variable #" + position + " (" + name
                        + "): selector is required for " + checkedType + ".");
            }
            if (group != null && group < 0) {
                throw new IllegalArgumentException("Request variable #" + position + " (" + name
                        + "): regex group must not be negative.");
            }
            List<String> transforms = original == null ? List.of() : original.transformChain();
            ExtractRule.JsonResultMode mode = original == null ? ExtractRule.JsonResultMode.SINGLE : original.jsonResultMode();
            return new ExtractRule(id, name, enabled, ExtractRule.SourceSide.REQUEST, checkedType, checkedSelector,
                    name, transforms, regexSource, group, RuleType.EXTRACT, mode);
        }
    }

    /**
     * One table column over an immutable row type.
     *
     * @param name   header
     * @param type   cell class (Boolean, Integer, String or an enum)
     * @param getter reads the cell
     * @param setter returns a copy of the row with the cell changed
     * @param <T>    row type
     */
    public record Column<T>(String name, Class<?> type, Function<T, Object> getter, BiFunction<T, Object, T> setter) {
    }

    /** Request variable columns. */
    public static final List<Column<VariableDraft>> VARIABLE_COLUMNS = List.of(
            new Column<>("On", Boolean.class, VariableDraft::enabled,
                    (r, v) -> new VariableDraft(r.id(), bool(v), r.variable(), r.type(), r.selector(), r.regexSource(), r.group(), r.original())),
            new Column<>("Variable", String.class, VariableDraft::variable,
                    (r, v) -> new VariableDraft(r.id(), r.enabled(), text(v), r.type(), r.selector(), r.regexSource(), r.group(), r.original())),
            new Column<>("Type", ExtractRule.ExtractorType.class, VariableDraft::type,
                    (r, v) -> new VariableDraft(r.id(), r.enabled(), r.variable(), (ExtractRule.ExtractorType) v, r.selector(), r.regexSource(), r.group(), r.original())),
            new Column<>("Selector / name / regex", String.class, VariableDraft::selector,
                    (r, v) -> new VariableDraft(r.id(), r.enabled(), r.variable(), r.type(), text(v), r.regexSource(), r.group(), r.original())),
            new Column<>("Regex source", ExtractRule.RegexSource.class, VariableDraft::regexSource,
                    (r, v) -> new VariableDraft(r.id(), r.enabled(), r.variable(), r.type(), r.selector(), (ExtractRule.RegexSource) v, r.group(), r.original())),
            new Column<>("Group", Integer.class, VariableDraft::group,
                    (r, v) -> new VariableDraft(r.id(), r.enabled(), r.variable(), r.type(), r.selector(), r.regexSource(), (Integer) v, r.original())));

    /** Observation columns (number format inline). */
    public static final List<Column<WatchObservation>> OBSERVATION_COLUMNS = List.of(
            new Column<>("Name", String.class, WatchObservation::name,
                    (o, v) -> new WatchObservation(o.id(), text(v), o.source(), o.selector(), o.itemsPath(), o.keyPath(), o.valuePath(), o.valueType(), o.numberFormat())),
            new Column<>("Source", WatchObservation.Source.class, WatchObservation::source,
                    (o, v) -> new WatchObservation(o.id(), o.name(), (WatchObservation.Source) v, o.selector(), o.itemsPath(), o.keyPath(), o.valuePath(), o.valueType(), o.numberFormat())),
            new Column<>("Selector / regex", String.class, WatchObservation::selector,
                    (o, v) -> new WatchObservation(o.id(), o.name(), o.source(), text(v), o.itemsPath(), o.keyPath(), o.valuePath(), o.valueType(), o.numberFormat())),
            new Column<>("Items path", String.class, WatchObservation::itemsPath,
                    (o, v) -> new WatchObservation(o.id(), o.name(), o.source(), o.selector(), text(v), o.keyPath(), o.valuePath(), o.valueType(), o.numberFormat())),
            new Column<>("Key path", String.class, WatchObservation::keyPath,
                    (o, v) -> new WatchObservation(o.id(), o.name(), o.source(), o.selector(), o.itemsPath(), text(v), o.valuePath(), o.valueType(), o.numberFormat())),
            new Column<>("Value path", String.class, WatchObservation::valuePath,
                    (o, v) -> new WatchObservation(o.id(), o.name(), o.source(), o.selector(), o.itemsPath(), o.keyPath(), text(v), o.valueType(), o.numberFormat())),
            new Column<>("Value type", WatchObservation.ValueType.class, WatchObservation::valueType,
                    (o, v) -> new WatchObservation(o.id(), o.name(), o.source(), o.selector(), o.itemsPath(), o.keyPath(), o.valuePath(), (WatchObservation.ValueType) v, o.numberFormat())),
            new Column<>("Auto number", Boolean.class, o -> o.numberFormat().auto(),
                    (o, v) -> withFormat(o, f -> new WatchNumberFormat(bool(v), f.decimalSeparator(), f.groupSeparator(), f.minorUnitScale(), f.stripPattern()))),
            new Column<>("Decimal sep", String.class, o -> o.numberFormat().decimalSeparator(),
                    (o, v) -> withFormat(o, f -> new WatchNumberFormat(f.auto(), rawText(v), f.groupSeparator(), f.minorUnitScale(), f.stripPattern()))),
            new Column<>("Group sep", String.class, o -> o.numberFormat().groupSeparator(),
                    (o, v) -> withFormat(o, f -> new WatchNumberFormat(f.auto(), f.decimalSeparator(), rawText(v), f.minorUnitScale(), f.stripPattern()))),
            new Column<>("Minor-unit scale", Integer.class, o -> o.numberFormat().minorUnitScale(),
                    (o, v) -> withFormat(o, f -> new WatchNumberFormat(f.auto(), f.decimalSeparator(), f.groupSeparator(), v == null ? 0 : (Integer) v, f.stripPattern()))),
            new Column<>("Strip regex", String.class, o -> o.numberFormat().stripPattern(),
                    (o, v) -> withFormat(o, f -> new WatchNumberFormat(f.auto(), f.decimalSeparator(), f.groupSeparator(), f.minorUnitScale(), rawText(v)))));

    /** Expectation columns. */
    public static final List<Column<WatchExpectation>> EXPECTATION_COLUMNS = List.of(
            new Column<>("On", Boolean.class, WatchExpectation::enabled,
                    (e, v) -> new WatchExpectation(e.id(), bool(v), e.keyPattern(), e.type(), e.expression(), e.expression2(), e.tolerance())),
            new Column<>("Key glob", String.class, WatchExpectation::keyPattern,
                    (e, v) -> new WatchExpectation(e.id(), e.enabled(), text(v), e.type(), e.expression(), e.expression2(), e.tolerance())),
            new Column<>("Type", WatchExpectation.Type.class, WatchExpectation::type,
                    (e, v) -> new WatchExpectation(e.id(), e.enabled(), e.keyPattern(), (WatchExpectation.Type) v, e.expression(), e.expression2(), e.tolerance())),
            new Column<>("Expression", String.class, WatchExpectation::expression,
                    (e, v) -> new WatchExpectation(e.id(), e.enabled(), e.keyPattern(), e.type(), text(v), e.expression2(), e.tolerance())),
            new Column<>("Expression 2", String.class, WatchExpectation::expression2,
                    (e, v) -> new WatchExpectation(e.id(), e.enabled(), e.keyPattern(), e.type(), e.expression(), text(v), e.tolerance())),
            new Column<>("Tolerance", String.class, WatchExpectation::tolerance,
                    (e, v) -> new WatchExpectation(e.id(), e.enabled(), e.keyPattern(), e.type(), e.expression(), e.expression2(), text(v))));

    private static WatchObservation withFormat(WatchObservation o, Function<WatchNumberFormat, WatchNumberFormat> change) {
        return new WatchObservation(o.id(), o.name(), o.source(), o.selector(), o.itemsPath(), o.keyPath(), o.valuePath(),
                o.valueType(), change.apply(o.numberFormat()));
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private static String rawText(Object value) {
        return value == null ? "" : value.toString();
    }
}
