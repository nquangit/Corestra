# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
`AGENTS.md` holds the longer workspace guide; this file is the quick reference. When either
document disagrees with the code, **the code, `pom.xml`, and tests win**.

## Build and test

Build and test through **WSL (kali-linux)**, not native Windows:

```bash
wsl -d kali-linux -- bash -lc "cd /mnt/d/Me/PEN/Web/BurpsuiteHelp/Extensions/dev/Corestra && mvn test"
```

Inside that shell:

```bash
mvn test                                  # compile + all tests (~1000 tests; some Swing tests skip headless)
mvn -o test                               # offline, when dependencies are cached
mvn test -Dtest=FlowEngineTest            # one class
mvn test '-Dtest=FlowEngineTest#method'   # one method
mvn package                               # target/corestra-0.1.0-SNAPSHOT-all.jar (load this into Burp)
```

- Java 21, Maven. Montoya API is `provided` (supplied by Burp, `montoya.version` in `pom.xml`);
  Jackson is shaded. CI (`.github/workflows/ci.yml`) runs `mvn clean verify` plus
  `.github/scripts/check_jar.py`.
- **Verify Montoya APIs with `javap` before using them.** Local jar:
  `~/.m2/repository/net/portswigger/burp/extensions/montoya-api/<version>/montoya-api-<version>.jar`
  (e.g. `javap -cp <jar> burp.api.montoya.http.message.responses.HttpResponse`).
  Invented API shapes are the main source of rework.
- TDD: write the failing test first. `flow`, `http`, `integration`, `session`, `access` are
  unit-testable without Burp; tests use fake `HttpClient`s, dynamic proxies, injected clocks,
  sleepers and ID generators — reuse those seams.

## What the extension is

Corestra (`dev.nquangit.corestra`) is a Burp Suite extension with four feature areas:

1. **Flowise** — reusable multi-step HTTP flows: extract values into variables, inject them into
   later requests, conditions/actions (retry, sleep, break, trigger another flow), aggregation.
2. **Session Handler** — project-wide session profiles with scope rules, scheduled re-auth,
   scoped extraction/injection on live Burp traffic.
3. **Access Testing** — actor-isolated authorization testing: Auth Providers/Profiles, Data
   Profiles, request templates, flow targets, matrix generation, scenario setup/teardown, race
   runs, evaluation, evidence, findings.
4. **Manual Testing** — editable request templates (`{{var}}`) run with session selection.

Root UI (`CorestraRootPanel`) uses `JTabbedPane.TOP`: Flowise, Authentication, Session Handler,
Access Testing, Parameters, Project (+ Manual Testing).

## Wiring (read `bootstrap/CorestraBootstrap.java` first)

`CorestraExtension` only sets the name and calls `CorestraBootstrap.initialize()`, which builds
everything and registers tabs, context menus, the session-handling action and HTTP handlers.
`ServiceRegistry` holds closeables released on unload.

- Transport: `SessionAwareHttpClient → RoutingHttpClient → MontoyaHttpClient | ProxyPipelineHttpClient`
  (send mode from `ProjectSettings`, overridable per step).
- **Three `FlowEngine` instances**: main (session-aware), `accessFlowEngine` (uses
  `ActorIsolatedHttpClient` so project sessions never overwrite the tested actor), and the Manual
  Testing engine (own repository document).
- `SessionManager` is late-bound through an `AtomicReference` to break the engine ↔ session cycle.
- Three independent automatic paths on live traffic — keep them separate:
  `CorestraSessionHandlingAction` (Burp session rules), `CorestraSessionHttpHandler`
  (scoped session injection), `AutoTriggerHttpHandler` (per-step trigger rules; prerequisites →
  inject → send → **extract from live response before running downstream steps**).
  `CorestraTrafficOrigin` (per-send nonce + message IDs) stops Corestra reprocessing its own traffic.
- `GlobalRetryHttpHandler` (project **Global retry**, `http.retry`) is registered first among live
  handlers: host cooldown before session injection; retryable responses are resent
  (Corestra-origin) and replaced before Session Handler / Auto Trigger see them.

## Domain model (`flow`)

Immutable, Jackson-annotated. Rebuild the whole chain on change:
`new FlowStep(…) → new FlowDefinition(…) → controller.updateFlow(updated)`, preserving every field.

- `FlowStep` has ~15 fields (request template, extract/inject rules, preconditions, error
  strategy, `lastResponseRaw`, `triggerRule`, `includeInAggregation`, `conditionActionRules`,
  `sendModeOverride`, `manualInjectRulesEnabled`). Prefer the `with…` copy methods; if you use a
  constructor, use the full one so nothing is dropped.
- `ExecutionContext` is immutable — always use the returned instance.
- `FlowEngine` limits: retries ≤ 10, sleep ≤ 300 s, triggered-flow depth ≤ 5 with cycle detection.

## Extraction / injection rules (`http.extract`, `http.inject`)

- `ExtractRule` side REQUEST|RESPONSE; types HEADER, COOKIE, JSON_PATH, REGEX, BODY_TEXT,
  BODY_RAW, STATUS_CODE. REGEX has `RegexSource` BODY | HEADERS (start line + headers) |
  FULL_MESSAGE (start line + headers + blank line + body) on **both** sides.
- `HttpMessageSnapshot.regexInput(source)` is the single source of truth for the text a regex
  searches; `ExtractionService` and `RegexSuggestionService` must both use it.
- Build snapshots from Montoya messages with `integration.HttpMessageSnapshots` so every path
  (engine, auto-trigger, session handler, manual, editor preview) renders start lines identically.
- `RegexSelection.fromRawMessage(...)` maps request/response editor selections to
  BODY/HEADERS/FULL_MESSAGE coordinates for "Suggest from editor selection".
- `InjectRule.Target`: HEADER, COOKIE, QUERY_PARAMETER, FORM_FIELD, JSON_PATH,
  REGEX_REPLACEMENT (body only), FULL_REGEX_REPLACEMENT (request line + headers + body; must keep
  the CRLFCRLF separator), RAW_BODY_APPEND, RAW_BODY_REPLACE. Regex replacements: STATIC/TEMPLATE
  use `$1` syntax; VARIABLE/INTERACTIVE values are inserted literally. Neither regex target is
  allowed for session-binding injections.
- Condition sources include `REQUEST_FULL_MESSAGE` / `RESPONSE_FULL_MESSAGE` for regex over the
  whole message.

## Intelligence (`intel`)

Deterministic, explainable heuristics only — no LLM, never sends traffic, reads captured data.
- `intel.flow.ValueCorrelationService` matches values from earlier steps' `lastResponseRaw` /
  existing extractors against a later request (header incl. `Bearer` prefix, cookie, query, form,
  JSON leaf, path segment) and proposes extract+inject pairs; new extractors are re-verified with
  `ExtractionService`. `CorrelationApplier` applies them immutably. Used by Flowise "Suggest".
- `intel.access`: `ObjectIdentifierDetector`, `OperationClassifier`, `AccessInsightService`
  (score + strategy readiness), `TemplateParameterizer` (literal ids → `{{data.*}}`). Used by
  Access Testing > Templates > Insights.
- Access runs strip captured identity first (`access.runner.CapturedIdentityScrubber`); JSON paths
  in evaluation use `access.evaluate.JsonPathSelector`; `BaselineVerdictReconciler` downgrades
  DENY→OK rows whose ALLOW baseline failed in the same plan run.
- Live re-login: `SessionManager.recoverFromLiveFailure` checks each Session Handler response against
  the linked provider's failure rules + `targetRetryPolicy` (or a standalone profile's re-login
  status/body conditions), re-authenticates once per generation with a 30 s failure cooldown, and
  `CorestraSessionHttpHandler` resends the request once via a Corestra-origin sender.
- `apply(...)` skips unresolved rules with diagnostics; `applyRequired(...)` throws. Paths that
  must not send half-rendered requests (auth flows, auto-trigger, teardown) use the required mode
  or `FlowInputValidator.missingStepVariables`.

## UI conventions (`ui.*`)

- `FlowWorkspaceController` owns active flow / selected step / results; publish via its listeners.
  `setActiveFlow()` clears `selectedStep` — validate step membership before refreshing fields.
- `StepListPanel` drag listener: pass interactive children (checkboxes) as `excluded` to
  `installOnTree(c, ma, excluded)`.
- Use Burp-native editors, `StyledButton.primary/success/danger/neutral`, `UiTheme.pick(light, dark)`.
- Network work off the EDT (`SwingWorker`); no business logic in Swing listeners.

## Persistence (`persistence`, `access.persistence`)

Versioned JSON in `extensionData()` (`FlowSerializer` + `PersistenceMigrationService`);
preferences in `preferences()`; `lastResponseRaw` is Base64. Project import/export
(`ProjectSerializer`) must keep secret sanitization (`FlowExportSanitizer`). New enum values are
appended so existing documents keep loading; add round-trip tests for schema changes. Malformed
stored documents are quarantined — don't overwrite valid user data with defaults.

## References

- `AGENTS.md` — detailed workspace guide (more current than `docs/`).
- `USAGE.md`, `guides/*.md` — user documentation (guides are in Vietnamese).
- `BUG_REVIEW.md`, `PERFORMANCE_REVIEW.md` — past review reports.
- `docs/` is git-ignored (local only): `architecture.md`, `research/montoya-reference.md`,
  `plans/`, `superpowers/`, `burp-suite-montoya-javadoc/` — historical, may be stale.
