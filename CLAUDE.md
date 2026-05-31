# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Compile and run all tests
mvn test

# Run a single test class
mvn test -Dtest=FlowEngineTest

# Run a single test method
mvn test -Dtest=FlowEngineTest#someMethodName

# Build the deployable fat JAR (excludes montoya-api; shades Jackson)
mvn package
# Output: target/corestra-0.1.0-SNAPSHOT-all.jar

# Compile only (no tests)
mvn compile
```

The Montoya API jar is `provided` — it is on Burp's classpath at runtime but not bundled. Jackson is the only shaded dependency.

## Architecture overview

Corestra is a **Burp Suite extension** (Montoya API 2026.4, Java 21) for defining and executing multi-step HTTP flows. The design is domain-first: the engine, extraction, injection, and execution model are plain Java with no Swing or Burp dependency. Montoya touches only the edges — bootstrapping, transport, context menus, session handling, and native editors.

### Object graph construction

`CorestraExtension` (the Montoya entrypoint) calls `CorestraBootstrap`, which builds the object graph and stores shared singletons in `ServiceRegistry(api, flowRepository, flowEngine)`. No Swing panel constructs services directly.

### Domain model (`flow` package)

All domain objects are immutable (Jackson-annotated records/final classes):

- **`FlowDefinition`** — a named, reusable flow containing steps, rules, aggregation plan, and trigger config.
- **`FlowStep`** — `(id, name, enabled, notes, requestTemplate, extractRules, injectRules, preconditions, errorStrategy[, lastResponseRaw])`. Always construct via the 9-arg or 10-arg constructor; there is no builder.
- **`FlowTriggerConfig`** — 5-arg constructor `(prerequisiteStepIds, prerequisiteFlowIds, recoveryFlowId, sessionHandlingIntegrationEnabled, followUpHooks)`.
- **`AggregationPlan`**, **`FlowRuleSet`**, **`StoredRequestTemplate`** — similarly immutable.

When mutating a step or flow (e.g. toggling `enabled`), reconstruct the whole chain: `new FlowStep(…) → new FlowDefinition(…) → controller.updateFlow(updated)`.

### Execution (`flow.execution` package)

```
FlowEngine.execute(flow, context)
  └─ for each enabled step:
       InjectionService → HttpClient.send() → ExtractionService → ExecutionContext.withVariable()
```

`ExecutionContext` is immutable; each mutation returns a new instance. `FlowEngine` depends on the `HttpClient` interface (not Montoya directly) so tests use a fake client.

### UI (`ui` / `ui.flowise` packages)

```
CorestraRootPanel (left JTabbedPane)
└─ FlowisePanel
   └─ FlowTabsPanel  (one tab per flow)
      └─ FlowWorkspacePanel
         ├─ StepListPanel          (drag-reorderable, enable/disable checkbox per row)
         └─ right JSplitPane
            ├─ RequestResponsePanel  (step-header bar + Burp-native editors)
            └─ detail JTabbedPane: Rules · Execution · Aggregation · Notes
```

**`FlowWorkspaceController`** is the central state owner. It holds `activeFlow`, `selectedStep`, `lastExecutionResult`, and `flowEngine`, and notifies all panels through `addFlowChangeListener(Runnable)`. Never pass raw domain objects between panels — go through the controller.

**`StyledButton`** — use instead of plain `JButton`. Factory methods: `StyledButton.primary(text)`, `.success(text)`, `.danger(text)`, `.neutral(text)`.

**`UiTheme.isDark()`** / **`UiTheme.pick(lightColor, darkColor)`** — use for any hardcoded color to stay aligned with Burp's theme.

### Persistence (`persistence` package)

Flow definitions serialize to Burp `extensionData()` as versioned JSON via `FlowSerializer`. `PersistenceMigrationService` upgrades old schema versions on load. UI preferences use `preferences()`. Captured responses persist per-step as Base64 (`lastResponseRaw`) so the viewer survives extension reload.

### Session handling (`integration` package)

`CorestraSessionHandlingAction` is registered via `api.http().registerSessionHandlingAction(...)`. `SessionHandlingFlowMapper` selects enabled flows whose `FlowTriggerConfig.sessionHandlingIntegrationEnabled()` is `true`. Failures in one flow are isolated and never affect the user's live request.

## Key constraints

- **Verify Montoya APIs against the actual jar** before writing code: `javap -classpath <burp.jar> -p <ClassName>`. Invented or misremembered API shapes are the main source of rework.
- **`StepListPanel`** installs a `MouseAdapter` on all child components for drag-and-drop. Any interactive child (e.g. `JCheckBox`) must be passed as `excluded` to `installOnTree(c, ma, excluded)` to prevent the drag listener from swallowing its events.
- **`FlowWorkspaceController.setActiveFlow()`** sets `selectedStep = null`. Step-header change listeners must validate that the selected step belongs to the active flow before populating fields.
- **No business logic in Swing listeners** — keep extraction/injection code in `http.extract` / `http.inject`; keep domain mutations as plain method calls on immutable objects.
- **TDD**: write a failing test before any production code. The `flow`, `http`, and `integration` packages are fully unit-testable without a Burp runtime.

## Reference documents

- `docs/architecture.md` — package layout, execution flow, boundary isolation diagram
- `docs/plans/corestra-flowise-implementation-plan.md` — original 18-task implementation plan (Tasks 0–17 complete)
- `docs/research/montoya-reference.md` — verified Montoya API shapes and gotchas
- `docs/user-guide.md` — end-user documentation
