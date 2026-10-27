# Corestra workspace guidance

## Project and source of truth

Corestra is an implemented Burp Suite extension for multi-step HTTP flow orchestration,
session management, and access-control testing. It is not an empty workspace or a scaffold.
Package root: `dev.nquangit.corestra`.

Use current source code, `pom.xml`, and tests as the source of truth. `CLAUDE.md`,
`README.md`, and older architecture/planning documents provide context but contain stale
claims about UI placement, constructor signatures, feature scope, and Montoya isolation.
Check actual declarations before using APIs or reconstructing domain objects.

## Build and verification

- Java release target: 21.
- Maven project, Montoya API `2026.4` (provided), Jackson `2.20.2`, JUnit Jupiter `5.13.4`.
- Maven Shade packages Jackson dependencies; Montoya is supplied by Burp at runtime.
- Java 21 and Maven 3.9.12 were available in this workspace when checked on 2026-09-16.
  Check the current environment rather than assuming Maven is missing.

```bash
mvn compile
mvn test
mvn test -Dtest=FlowEngineTest
mvn test '-Dtest=FlowEngineTest#methodName'
mvn package
# Offline verification when dependencies are already cached:
mvn -o test
```

Load `target/corestra-0.1.0-SNAPSHOT-all.jar` into Burp. The thin JAR is
`target/corestra-0.1.0-SNAPSHOT.jar`. Build output belongs in ignored `target/`.

Tests mirror production packages under `src/test/java`; fixtures are in
`src/test/resources/fixtures`. They use fake transports, interface stubs/dynamic proxies,
injected clocks, sleepers, and ID generators. Reuse these seams instead of requiring a
live Burp runtime. Some Swing/bootstrap tests skip in headless environments; a passing
unit suite does not establish that the extension was manually tested in Burp.

For behavior changes, add a focused regression test and run the relevant tests, followed
by the full suite as appropriate. Documentation-only changes need no new unit tests.

## Runtime wiring and package map

`CorestraExtension.initialize()` sets the extension name and delegates to
`CorestraBootstrap.initialize()`. Bootstrap constructs services, repositories, controllers,
and panels, then registers the suite tab, context menus, session action, and HTTP handlers.
`ServiceRegistry` holds the API, flow repository, and engine and supports closeable services;
the bootstrap registers the session scheduler for cleanup through the unloading handler.

| Package | Responsibility |
| --- | --- |
| `bootstrap` | Object graph, registration, shared runtime lifecycle. |
| `config` | Persistence and preference keys. |
| `flow`, `flow.rule`, `flow.control` | Flow/step definitions, extraction/injection rules, triggers, conditions and actions. |
| `flow.execution` | Step execution, immutable context/results, dependencies, recovery and aggregation. |
| `http.extract`, `http.inject` | Snapshot-based extraction and request-template mutation. |
| `integration` | HTTP transport, live traffic handlers and Burp session-handling action. |
| `session` | Project session profiles, scoped selection, authentication, extraction/injection and scheduling. |
| `access` and subpackages | Access Testing domain, auth, scenarios, matrices, runners, evaluation, evidence and persistence. |
| `persistence` | Flow/project JSON, metadata, settings, migration and UI preferences. |
| `actions` | Burp context menus, request capture and flow execution actions. |
| `ui`, `ui.flowise`, `ui.project`, `ui.session`, `ui.access`, `ui.component` | Swing views/controllers, native editors and theme components. |

Keep the entrypoint minimal. Wire shared runtime collaborators in bootstrap and inject
them into consumers. Avoid adding business logic to Swing listeners or expanding already
large controllers/runners with unrelated responsibilities.

## Flow execution and HTTP transport

- `FlowDefinition`, `FlowStep`, rules and `StoredRequestTemplate` describe reusable flows.
  Prefer immutable updates; preserve all existing fields when replacing a step or flow.
  Current models include more fields and overloads than the original planning documents.
- `FlowWorkspaceController` owns flow selection, selected step, persistence updates and
  execution results; publish changes through its listener/update methods.
- `FlowEngine` delegates each enabled step to `StepExecutor`: inject variables into the
  request, send through `HttpClient`, record the response, extract variables and propagate
  a new `ExecutionContext`. Error strategies and condition/action rules control continuation,
  retries, sleeps and triggered flows. Preserve existing bounds on retries and trigger depth.
- `ExecutionContext` is immutable; use its returned replacement after each update.
- Transport is `SessionAwareHttpClient -> RoutingHttpClient -> MontoyaHttpClient` or
  `ProxyPipelineHttpClient`, selected by project settings. Preserve both sending modes.
- Bootstrap uses a late-bound `SessionManager` reference to resolve its dependency cycle
  with the engine and session-aware transport.
- The engine is independent of Swing and concrete transport, but the execution boundary
  is not wholly Montoya-free: `HttpClient` uses Montoya `HttpRequest`/`HttpResponse`, and
  `StepExecutor` bridges stored requests to these types. Test through the existing seams.

## Three distinct automatic integration paths

1. `CorestraSessionHandlingAction` uses `SessionHandlingFlowMapper` to run flows opted into
   Burp session handling. Keep failures isolated from the live request.
2. `CorestraSessionHttpHandler` and `SessionAwareHttpClient` use project `SessionManager`
   profiles for scoped authentication and extraction/injection. `SessionGuard` prevents
   recursive session work; `SessionSchedulerService` handles scheduled authentication.
3. `AutoTriggerHttpHandler` applies per-step trigger rules to matching Burp traffic. It runs
   prerequisites before the live request, applies the trigger step's request rules, then
   extracts from the live response before running downstream steps and aggregation.

Global HTTP handlers are already part of the implementation. Preserve scope matching,
recursion guards, Corestra-origin traffic exclusion in auto-trigger, execution stop bounds,
and pending request/response context. In particular, do not move downstream execution ahead
of trigger-response extraction: later steps can depend on those extracted variables.

Other extensions can explicitly opt into Auto Trigger through `EXTENSIONS` (`EXTENDER`
alias); legacy empty tool lists exclude this source. `CorestraTrafficOrigin` uses per-send
nonces and live message IDs across callback threads and strips its private header before
transmission. Bootstrap shares it with the native sender, trigger and session boundaries.
Preserve this guard; a thread-local alone is insufficient, and blanket EXTENSIONS exclusion
would disable foreign-extension triggers. Network failures have `ExecutionFailureType` in
`StepExecutionRecord`; cancellation/input/session-binding safeguards precede retry actions.
The live Burp target does not execute through the engine's send/retry loop.

Use `api.http().sendRequest(...)` through the transport for execution.
`sendToRepeater(...)` opens a prepared request; it does not automatically send it.
Verify unfamiliar Montoya signatures against the actual dependency JAR with `javap` or the
local Javadocs before implementation.

## Access Testing subsystem

- `AccessWorkspace` is an immutable snapshot; `InMemoryAccessRepository` owns updates and
  invokes the project persistence callback. `AccessUiController` connects it to the UI.
- Auth providers describe flow-backed authentication; auth profiles describe actors and
  bindings/manual auth. `access.auth.runtime.AuthManager` manages Access Testing auth.
  This is a different service from the project-wide `session.SessionManager`.
- Data profiles, request templates and saved flow targets feed `AccessTestCase` matrix rows.
  `MatrixGenerator` and dependency analysis assist setup and row generation.
- Scoped template variables use `env`, `auth`, `data`, `scenario`, `runtime`, and `legacy`.
  Reuse `TemplateRenderer`, `VariableResolver` and scoped stores; preserve strict rendering
  and unresolved-variable reporting.
- `SingleAccessTestRunner` supports request templates and flow targets, scenario setup,
  authentication, target execution, evaluation, post-checks, teardown and evidence.
  `AccessPlanRunner` orchestrates batches; `RaceRunner` runs synchronized parallel batches.
- Preserve dry-run/validation behavior, cancellation, concurrency limits, actor/scenario
  isolation, and distinctions between run status and evaluation verdict.
- `BasicResultEvaluator` applies response checks/evaluation profiles. `RedactionService`
  and export modes control evidence sanitization; finding drafts use `access.report`.
- `access.importer` supports OpenAPI and Postman request-template imports.

## UI conventions

The root `CorestraRootPanel` uses **`JTabbedPane.TOP`** with tabs in this order:
Flowise, Authentication, Session Handler, Access Testing, Parameters (when available), Project.
Do not restore the historical left-tab
layout unless the requested UI change calls for it.

Flowise contains per-flow tabs and a split editor: reorderable/toggleable step list on the
left, Burp-native request/response editors and detail controls on the right. Authentication
contains the shared Auth Providers and Auth Profiles. Access Testing contains Data Profiles,
Templates, Flow Targets, Evaluation Profiles, Matrix and Results tabs.

- Use native editors from `createHttpRequestEditor` / `createHttpResponseEditor`.
- Follow `ThemeCoordinator`, `UiTheme`, `StyledButton`, and Burp theme/font APIs.
- Keep network execution off the Swing event-dispatch thread. Follow existing `SwingWorker`
  usage and marshal UI updates back to the EDT.
- Keep controller state authoritative; avoid independent copies of selected flows in panels.
- `setActiveFlow()` clears step selection. Validate selected-step membership before
  refreshing step-specific fields.
- Step drag listeners must exclude interactive children such as enable/disable checkboxes.
- Prefer restrained colors, clear spacing, and dialogs/collapsible controls for advanced options.

## Persistence and compatibility

- Burp `extensionData()` stores project-scoped flows, metadata, settings, session profiles,
  and Access Testing state. UI preferences use `preferences()`.
- Flow JSON is versioned via `FlowSerializer` and `PersistenceMigrationService`.
  Captured step responses use Base64 `lastResponseRaw` for viewer restoration.
- `ProjectSerializer` / `ProjectDocument` handle project import/export across subsystems.
  Preserve secret exclusion and compatibility with existing files when adding fields.
- Access project persistence includes reusable configuration, matrix rows, results, evidence,
  latest summary and report drafts. Runtime scenarios are omitted by
  `AccessWorkspaceConfiguration.projectState()`.
- Session profiles include session state; do not assume all authentication/session values
  are transient. Respect sanitization when exporting project data.
- Access/session repositories quarantine malformed stored documents. Preserve these recovery
  paths and do not overwrite valid user data with constructor defaults.
- Round-trip and migration tests belong with serialization changes.

## Code conventions and reading guide

JavaDoc public classes, interfaces, enums and public methods. Prefer small focused classes,
explicit immutable data and structured HTTP/JSON parsing. Keep dependencies minimal and
follow the existing Java/JUnit style.

Start with the source entrypoint and bootstrap, then the relevant controller, service and
tests. `USAGE.md` covers the broader feature set. Supplemental local references:

- `CLAUDE.md` — useful orientation, but verify against current code.
- `docs/architecture.md` and `docs/user-guide.md` — older architectural/user context.
- `docs/plans/corestra-flowise-implementation-plan.md` — historical Flowise plan.
- `docs/research/montoya-reference.md` — historical API notes, not current environment facts.
- `docs/superpowers/` — later persistence and auto-trigger design/fix notes.
- `docs/burp-suite-montoya-javadoc/` — local API reference.

`docs/` and `screenshots/` are ignored by the current `.gitignore`; local documents may be
absent in another checkout. Do not assume they are tracked or force-add them incidentally.
