# Corestra workspace guidance

Project: Corestra
Goal: Burp Suite extension for multi-step request-flow orchestration in pentesting, with a clean modern UI.

Current status
- Workspace starts empty.
- Reference extension: /mnt/d/Me/PEN/Web/BurpsuiteHelp/Extensions/dev/BurpHelper
- Local environment has Java 21 available.
- Maven is not currently installed in this WSL environment, so build verification is blocked until Maven or a wrapper is added.

Execution model
- Main Hermes agent acts as coordinator for research, planning, and verification.
- Coding work should be delegated to dev subagents.
- Before implementation, read docs/plans/corestra-flowise-implementation-plan.md and docs/research/montoya-reference.md.

Architecture rules
- Keep the BurpExtension entrypoint minimal. Use it only as a composition root.
- Separate packages by responsibility: bootstrap, config, flow, http, actions, ui, persistence, util.
- Do not put business logic in Swing listeners or tab classes.
- Keep request/response extraction and mutation code pure and unit-testable.
- Represent flow execution explicitly with immutable or mostly-immutable domain objects plus a per-run execution context.
- Separate project-scoped persistent flow definitions from transient run state.

Burp / Montoya rules
- Use Montoya 2026.4 APIs.
- Use api.userInterface().registerSuiteTab(...) for the root tab.
- Use JTabbedPane.LEFT for the outer Burp tab layout and a top-tab JTabbedPane for per-flow tabs.
- Use api.userInterface().createHttpRequestEditor(...) and createHttpResponseEditor(...) for embedded Burp-native viewers in the custom UI.
- Use api.userInterface().applyThemeToComponent(...) and currentTheme()/currentDisplayFont() to stay visually aligned with Burp.
- Avoid global request mutation via HttpHandler for MVP. Prefer explicit flow execution and session-handling integration first.
- Prefer SessionHandlingAction for automatic pre-request flow execution tied to Burp traffic.
- Use Repeater integration only to open prepared requests/tabs; sendToRepeater(...) does not auto-send.

UI / UX rules
- Modern flat look, restrained colors, clear spacing, low visual noise.
- Root structure: left-position JTabbedPane. First tab is Flowise.
- Inside Flowise: top tab strip for flows.
- Main editor split: left request-step list, right request/response viewers and rule details.
- Step list must support drag-and-drop reordering and enable/disable toggles.
- Keep advanced controls in collapsible sections or dialogs; do not clutter the main editor.

Code quality rules
- JavaDoc every public class, interface, enum, and public method.
- Favor small classes with obvious names over large handlers.
- Avoid string-fragile parsing when Montoya or structured parsers can be used.
- Keep dependencies minimal. Prefer Burp-native UI theming over external Swing look-and-feel libraries.
- Use JUnit 5 for tests.

Suggested package root
- dev.nquangit.corestra

Implementation references
- Plan: docs/plans/corestra-flowise-implementation-plan.md
- Research notes: docs/research/montoya-reference.md
