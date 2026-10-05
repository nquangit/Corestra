# Corestra Usage Guide

Corestra is a Burp Suite extension for building reusable request flows and access-control test plans. It is designed for authorized security testing of applications where a single useful request often depends on login, setup, object creation, token extraction, request mutation, and post-check verification.

This guide explains how to install the extension, configure flows, build Access Testing artifacts, run tests safely, and export evidence.

For step-by-step instructions covering every Access Testing tab, Data Profiles, request selection and Matrix runs, start with [Access Testing: basic to advanced](guides/ACCESS_TESTING.md). Detailed companion guides cover [authentication and actors](guides/ACCESS_AUTH_SETUP.md), [Flow Targets and selecting the request to evaluate](guides/ACCESS_FLOW_TARGETS.md), and [setup, post-checks, teardown, masking, race and imports](guides/ACCESS_ADVANCED.md). Ready-to-copy cookie, bearer, CSRF, API-key, refresh, and anonymous-user configurations are in [Auth recipes](guides/AUTH_RECIPES.md).

## 1. Prerequisites

- Burp Suite with Montoya extension support.
- Java 21.
- Maven for building the extension from source.
- Authorization to test the target application.
- A Burp project file if you want Corestra data to persist with the assessment.

Corestra stores project-scoped data in Burp project persistence. Use a dedicated Burp project per assessment when possible.

## 2. Build and Install

Build the extension:

```bash
mvn -q package
```

The Burp-loadable shaded JAR is created at:

```text
target/corestra-0.1.0-SNAPSHOT-all.jar
```

Install in Burp Suite:

1. Open Burp Suite.
2. Go to **Extensions > Installed > Add**.
3. Set **Extension type** to **Java**.
4. Select `target/corestra-0.1.0-SNAPSHOT-all.jar`.
5. Confirm the extension loads without errors.
6. Open the **Corestra** tab in Burp's main tab bar.

If Burp cannot load the JAR, confirm Burp is using a compatible Java runtime and rebuild with Java 21.

## 3. Core Concepts

Corestra has three major work areas:

- **Flowise**: Build reusable multi-step HTTP flows.
- **Authentication**: Manage shared login behavior, actors, credentials, and cached auth state.
- **Access Testing**: Build, run, and document authorization test cases.

Important terms:

- **Flow**: A named sequence of HTTP steps.
- **Flow step**: One stored request plus extract/inject rules and execution metadata.
- **Scoped variable**: A placeholder such as `{{auth.accessToken}}`, `{{data.accountId}}`, or `{{scenario.confirmToken}}`.
- **Access template**: A reusable target request template for access testing.
- **Auth Provider**: A configuration that knows how to obtain, refresh, validate, and inject auth state.
- **Auth Profile**: A specific actor identity, such as Alice, Bob, Admin, or Anonymous.
- **Data Profile**: A business object or record owned by an actor, such as an account, card, wallet, order, or customer.
- **Matrix row**: One access test case combining a target template, target actor, data object, setup, expected result, and optional post-checks or teardown.
- **Scenario**: Runtime variables produced by setup flows and consumed by a target request.

## 4. Recommended Assessment Workflow

Use this order for most access-control testing:

1. Capture important requests into Flowise.
2. Build login, refresh, validation, setup, target, post-check, and teardown flows.
3. Configure shared Auth Providers and Auth Profiles under **Authentication**.
4. Create Data Profiles for owned objects.
5. Convert requests or import OpenAPI/Postman definitions into Access Templates.
6. Generate or manually create Matrix rows.
7. Run Dry Run / Render Only to validate variables and config.
8. Run selected rows or a plan.
9. Review results, evidence, post-checks, and findings.
10. Export sanitized project data or Markdown finding drafts.

## 5. Flowise: Build Request Flows

Open **Corestra > Flowise**.

Create a flow:

1. Click **New Flow** or the add-flow control.
2. Give the flow a clear reusable name, for example `Auth - Login`, `Auth - Refresh`, `Create Transfer`, or `Read Account`.
3. Add steps from Burp traffic or manually.

Add a step from Burp:

1. Capture or select a request in Proxy history, Repeater, Logger, or another supported request view.
2. Use the Corestra context-menu action to add it to a flow.
3. Review the stored request in Flowise.

Choose how an individual request is sent:

1. Select its step in the flow.
2. Use **Send via** above the request/response editors:
   - **Use Project settings** (default): follows the current Project sending mode.
   - **Standard**: sends through Montoya, overriding a Project Proxy Pipeline default.
   - **Proxy Pipeline**: sends through the proxy listener configured in Project, overriding a Standard default.
3. The choice is saved immediately for that request and survives reload and project export/import.

Project proxy host/port remain editable even when the default is Standard, so per-request Proxy Pipeline overrides can use a custom listener without changing the global sending mode.

There is no flow-wide sending setting. To set the route for an entire flow, configure every request separately. A flow can mix Standard, Proxy Pipeline, and inherited requests. Overrides apply to extension-generated flow sends, including login/keep-alive, setup, Access Flow Targets, teardown, retries, and auto-trigger prerequisite/downstream steps. Each nested flow uses its own request settings. Sending to Repeater opens a Burp tab; subsequent Repeater sends and the original live request matched by Auto Trigger remain under Burp's control. Saved Access Flow Targets retain their snapshot's request settings; create a new snapshot to pick up later source-flow edits.

Add extract rules:

- Use extract rules when a response or request contains a value needed later.
- To generate a response regex, select the exact value in the native response editor before opening the extract-rule dialog, choose **Regex**, then click **Suggest from response selection**. Corestra chooses headers or body from the selection offset, creates capture group `1`, and verifies the match count against the stored response.
- For **Regex**, choose whether to search the body, the start line and headers, or the full HTTP message. Capture group `0` returns the full match; positive numbers return a capturing group. **Test regex** runs the rule against the step's stored request or response and shows the matched value or the exact failure.
- Optional transforms run in order after extraction. Put one operation per line, for example `trim`, `substring:0:16`, `stripPrefix:Bearer`, or `base64Decode`.
- Common examples:
  - Extract `accessToken` from login JSON into `accessToken`. Auth Provider stores that unscoped key in Auth State and exposes it as `{{auth.accessToken}}` when consumed.
  - Extract `confirmToken` from a setup response into `scenario.confirmToken`.
  - Extract `transferId` into `scenario.transferId`.

Add inject rules:

- Use inject rules when a later request needs a value from an earlier step.
- For a variable-based Flowise inject rule, the editable selector groups known keys from earlier enabled extractors, Auth Profile credentials/auth state, and Data Profiles. Profile-backed choices store only a reusable key such as `credentials.username`, `auth.accessToken`, or `data.accountId`; they never bind the flow to Alice, Bob, or a particular Data Profile.
- The preview masks credentials, tokens, headers, and cookies. Data values may be shown in abbreviated form. Profile names identify where a known key was found, but the preview profile is not persisted in the flow.
- Enter any custom variable key when it does not exist in the catalog. Context guidance below the selector explains where that key must be supplied.
- Choose **TEMPLATE** as the Value source to combine text and variables. For a refresh request, set Target to `REQUEST_HEADER`, Selector to `Authorization`, and Value template to `Bearer {{auth.refreshToken}}`. Select a key in the variable picker and click **Insert selected variable** to insert its placeholder at the cursor. You can also type templates yourself, including several placeholders.
- **VARIABLE** reads one exact key such as `auth.refreshToken` (without braces). **STATIC** keeps the entered text literal, including any braces. **TEMPLATE** resolves `{{key}}` once against the current execution context; malformed placeholders are rejected when saving. A missing runtime variable skips that whole injection rule with a diagnostic, rather than injecting a partial value.
- For a new `credentials.*` or `data.*` key, click **Save value to profile…**. Choose an existing profile or explicitly create one, enter the value, and confirm. Existing profile state and unrelated values are preserved; saving an existing key replaces that key only. Closing the Inject Rule with **OK** never creates or changes a profile by itself.
- Click **Suggest** above the injection-rule table to scan the current request for header, cookie, query, form, and JSON field names matching earlier extractor variables. Review the suggestions and select only the rules to add.
- Session Handler value-template cells are editable dropdowns. A linked Auth Profile offers `{{auth.key}}`, `{{auth.header.Name}}`, and `{{auth.cookie.Name}}` from the selected provider's flow contract and current actor state. A standalone profile offers `{{session.key}}`. Enabled local extractors are included with the matching namespace.
- Optional transforms run after resolving the variable, static value, or complete template and before writing it into the request. Click **Syntax & test…** in either rule editor for the full syntax and a sample-value tester. **Add Bearer prefix** inserts `prefix:Bearer ` with the required trailing space; this also works with a VARIABLE source. Add that prefix only once.
- Query injection encodes only the newly written value (and the name when adding a new parameter). Unrelated query text stays unchanged, including raw `/`, percent-escape spelling, duplicate names, flags, and empty segments. `OVERWRITE` updates every matching occurrence in place; `APPEND` adds another occurrence; `SKIP_IF_PRESENT` leaves the request unchanged when the decoded name already exists. The same behavior applies to Auth Provider query injection in Access Testing and Session Handler.
- Common examples:
  - Use TEMPLATE `Bearer {{auth.accessToken}}` for the `Authorization` header.
  - Inject `{{scenario.confirmToken}}` into a JSON body.
  - Inject `{{data.accountId}}` into a request path or body.

To run a parameterized flow manually, open its **Execution** sub-tab and click **Run Context…**:

1. Select an enabled Auth Profile when the flow reads `credentials.*` or cached `auth.*` values.
2. Select an enabled Data Profile when the flow reads `data.*` values. If that Data Profile has an owner, Corestra rejects a different selected Auth Profile.
3. Add optional custom variables as one `key=value` pair per line. A value may contain additional `=` characters. Custom values override values supplied by the selected profiles.
4. Run the flow or selected step. Corestra checks required profile-scoped variables before sending a request. A whole-flow run recognizes values produced by earlier enabled extractors; a single-step run requires those values in Run Context because earlier steps are not executed.

Run Context is local UI state and does not change the flow definition or Access Matrix. Access Testing rows continue to obtain their actor and data bindings from the row configuration.

Supported value transforms are `trim`, `lowercase`, `uppercase`, `urlDecode`, `urlEncode`, `base64Decode`, `base64Encode`, `substring:START[:END]`, `prefix:TEXT`, `suffix:TEXT`, `stripPrefix:TEXT`, `stripSuffix:TEXT`, and `replaceRegex:PATTERN=>REPLACEMENT`.

See [Injection templates and transform syntax](guides/TRANSFORMS.md) for step-by-step Bearer setup, examples for every operation, whitespace handling, and error behavior.

Keep flows small and purpose-specific. It is usually better to have separate login, refresh, setup, target, post-check, and teardown flows than one large flow that does everything.

The **Flow Log** sub-tab keeps the newest 1,000 step exchanges in memory. It is searchable and opens the selected request and response in Burp-native viewers. The log is cleared when the extension unloads and can also be cleared manually.

The root **Parameters** tab passively builds a runtime parameter dictionary from Burp traffic. Collection is restricted to Burp scope by default and ignores extension-origin traffic. Example values are disabled by default; when enabled, they remain in memory and keys recognized as sensitive are stored as `<redacted>`. The dictionary is bounded to 5,000 scoped entries and can be filtered or cleared at any time.

### Reuse an Auth Profile in Session Handler

Use one parameterized login flow for Access Testing and normal Burp traffic:

1. Build a generic login flow whose request reads `credentials.*` and whose response extracts auth variables.
2. Configure that flow under a shared Auth Provider in **Authentication**.
3. Create one Auth Profile per real actor and put username, password, device ID, or other stable login inputs in **Credentials**.
4. Open **Session Handler**, add or select a Session Profile, and set **Authentication** to **Linked Auth Profile**.
5. Select the actor. Use **Reload** if the actor was created while the Session Handler tab was already open.
6. Configure scope and scheduler. Provider injection rules are reused automatically; local Session injection rules remain available as traffic-specific overrides.

For a linked profile, use `{{auth.accessToken}}` in a local Injection Rule. The Value Template dropdown suggests outputs from the provider's login/refresh/validate flows even before the first login, then also includes the actor's current Auth Variables, Headers, and Cookies. Header and cookie state use `{{auth.header.Header-Name}}` and `{{auth.cookie.CookieName}}`. Existing linked rules that use `{{session.accessToken}}` remain compatible, but `auth.*` is the clearer form because it matches the State tab and Authentication configuration.

Do not recreate `Authorization`, Cookie, or query injection in Session Handler when the Auth Provider already defines it. Provider rules run first automatically. Add a local rule only for a traffic/tool-specific addition or override.

TTL and Scheduler solve different problems. The provider TTL marks Auth State stale; it never sends a request by itself. With Scheduler disabled, matching traffic checks stale authentication on demand. With an optional **Keep-alive Flow** configured in the linked Auth Provider, Scheduler maintains the actor's current session and extends local TTL on success. Without that flow, scheduled runs retain the previous behavior of forcing the provider's auth policy. Set the interval safely below the server timeout without sending excessive traffic.

Configure keep-alive's independent **Success** and **Session Expired** rules in the provider's **Keep-alive** tab. Timeout/network/unknown failures keep state unchanged and retry with backoff; only an explicit expiry match permits re-authentication. The keep-alive step needs its own `auth.*` injection rules. **Maintain Session Now** tests maintenance immediately; **Run Auth Now** continues to run the original authentication policy. See [Keep-alive setup for one or multiple actors](guides/KEEP_ALIVE.md).

During a Session Handler renewal, matching requests wait until authentication finishes, then render all injection rules from the latest state. Linked provider rules and local overrides use the same Auth Profile snapshot, so cookies such as `JSESSIONID` and `token` are injected together. Internal login traffic through Proxy Pipeline is excluded from live session extraction even after its internal marker header is removed. Responses from requests sent before renewal cannot overwrite the new state. Responses without a tracked outgoing request (including expired or evicted tracking entries) are forwarded without extracting session values.

Refreshing authentication does not rebuild application form or transaction context. A queued request can still contain page IDs, processor IDs, or hidden fields from the previous session even when its cookies are correct. Use a setup flow to obtain those fields with the current authentication before sending the target. See [Session renewal and stale form context](guides/SESSION_RENEWAL.md) for an evidence-based example and the current auto-trigger input limitations.

Linked mode shares credentials, token/cookie state, provider login/refresh/validate flows, TTL, and injection rules with Access Testing. **Clear State** clears the linked actor's auth state while preserving credentials. **Standalone Flow (legacy)** remains available for existing projects and specialized session-only flows.

## 6. Scoped Variables

Corestra uses scoped placeholders:

```text
{{env.baseUrl}}
{{auth.accessToken}}
{{data.accountId}}
{{scenario.confirmToken}}
{{runtime.uuid}}
{{legacy.someOldVariable}}
```

Recommended scopes:

- `env.*`: Environment values such as `baseUrl`, tenant, region, or application version.
- `auth.*`: Tokens, CSRF values, session identifiers, and auth state.
- `data.*`: Stable business object values owned by a Data Profile.
- `scenario.*`: Runtime values produced by setup flows.
- `runtime.*`: Generated values such as `uuid`, `timestamp`, `nonce`, and `randomAlnum`.
- `legacy.*`: Compatibility fallback for imported or older unscoped names.

Prefer explicit scoped placeholders. They make test rows easier to debug and avoid ambiguous substitutions.

## 7. Authentication: Auth Providers

Open **Corestra > Authentication > Auth Providers**.

An Auth Provider defines how Corestra obtains and applies authentication material.

When adding or editing a provider:

1. Enter a provider name.
2. Enable or disable the provider.
3. Select the **Login Flow** from the dropdown.
4. Select the **Refresh Flow** from the dropdown if refresh is supported.
5. Select the **Validate Flow** from the dropdown if Corestra should check auth state before refreshing or logging in.
6. Choose a fallback policy.
7. Choose a target retry policy.
8. Set **Auth State TTL**. This fallback lifetime prevents login from running for every target when the server does not return expiry metadata. Extracted `expiresAt`, `expiresIn`, or `expires_in` takes priority.
9. Review **Flow Contract**. It lists required inputs, the Auth State keys produced by enabled extractors, and configuration errors or warnings. Extractor outputs should be unscoped (`accessToken`); their provider form is shown as `{{auth.accessToken}}`.
10. Configure injection rules. The editable **Value Template** selector is populated from selected-flow extractors. **Generate from flow** adds conservative Bearer, Cookie, and CSRF suggestions without replacing existing rules; review their variable and cookie/header names.
11. Configure success and failure rules.

The Login, Refresh, and Validate controls list flows already created in Flowise. Use **None** when a policy does not need that flow.

**Produced Auth State** is the declared contract derived from enabled extractor rules. It does not mean that every extractor matched the latest live response. Only successful runtime extractions are written to the selected Auth Profile's State.

Corestra accepts legacy extractor names such as `auth.accessToken` and normalizes them to `accessToken` when Auth State is updated. Flow Contract reports these names as warnings. **Fix auth.* extractor names** performs an explicit atomic cleanup on the selected auth flows and updates later Flowise injection references; ambiguous collisions are skipped. If both forms are returned together at runtime, the canonical unscoped name takes precedence.

Typical provider setup:

- Login Flow: a flow that sends credentials and extracts unscoped Auth State keys such as `accessToken`, `sessionId`, and `csrfToken`.
- Refresh Flow: a flow that uses `auth.refreshToken` or cookies to obtain a new access token.
- Validate Flow: a low-impact request that confirms the current auth state is still valid.
- Injection rule: inject `Bearer {{auth.accessToken}}` into the `Authorization` header.
- Success rule: status code is 200, or body contains a success marker.
- Failure rule: status code is 401 or 403, or body contains an auth error code.

Provider injection currently supports headers, individual cookies, and query parameters. Put auth values required in a JSON/form body or path directly in an Access Template as `{{auth.variableName}}`, or use a Flow Target with Flowise injection rules.

Use `403` as an auth failure signal only when the application truly uses it for expired authentication. If `403` means a correct authorization denial, adding it as an auth failure rule can cause an unnecessary refresh/login and retry.

Do not store real production credentials in shared exports. Use sanitized export when sharing projects.

## 8. Authentication: Auth Profiles

Open **Corestra > Authentication > Auth Profiles**.

Create one Auth Profile per actor:

- `alice-user`
- `bob-user`
- `branch-manager`
- `tenant-admin`
- `anonymous`

For each profile:

1. Select a provider, or use manual auth.
2. Set the role label.
3. Put per-user login inputs such as username, password, device ID, or login hash in **Credentials**. Auth flows read them as `credentials.username`, `credentials.password`, and similar keys.
4. Use **Auth Variables**, **Headers**, and **Cookies** for existing cached/manual auth state. Editing a profile preserves its credentials and cached expiry.
5. Keep profiles enabled only when they should be considered by matrix generation and plan runs.

Role labels are used by matrix generation strategies such as owner-vs-others, maker-checker, cross-role, cross-tenant, and cross-branch.

## 9. Access Testing: Data Profiles

Open **Data Profiles**.

Create Data Profiles for owned objects:

```text
Owner auth profile: alice-user
Values:
accountId = acct-alice-001
customerId = cust-alice
tenantId = tenant-a
branchId = branch-01
```

Create equivalent objects for other users and roles. This allows Corestra to generate comparisons such as:

- owner accessing own data
- same-role peer accessing another user's data
- cross-tenant actor accessing data outside their tenant
- cross-branch actor accessing another branch's data
- admin access requiring review

Use stable values. Avoid values that expire quickly unless a setup flow refreshes them.

## 10. Access Templates

Access Templates are target requests used by Access Testing.

Create templates in several ways:

- Convert a FlowStep to an Access Template.
- Add a FlowStep directly as a Matrix Target.
- Import OpenAPI JSON.
- Import Swagger/OpenAPI 2.0 JSON.
- Import Postman Collection v2.1 JSON.
- Add a manual template.

Imported templates are disabled by default and must be reviewed before use.

Template review checklist:

- Confirm method, scheme, host, port, path, headers, and body.
- Confirm placeholders use the correct scopes.
- Remove or replace hard-coded secrets.
- Mark operation metadata correctly:
  - `READ_ONLY` for safe reads.
  - `STATE_CHANGING` for create, update, delete, transfer, approve, submit, or similar actions.
  - `CONSUMABLE` for one-time or irreversible operations.
- Preview with representative auth and data profiles.

## 11. Import OpenAPI or Postman Templates

Open **Access Testing > Templates** and click **Import Templates**.

Supported formats:

- OpenAPI 3.x JSON
- Swagger/OpenAPI 2.0 JSON
- Postman Collection v2.1 JSON
- Auto-detect for supported JSON formats

YAML OpenAPI files are not imported in the current implementation. Convert YAML to JSON first.

Import steps:

1. Choose the import format or use Auto Detect.
2. Select the source file.
3. Set default scheme, host, port, and optional base path.
4. Keep **Disabled by default** enabled unless you have a controlled reason not to.
5. Choose placeholder generation options.
6. Confirm to parse.
7. Review the preview table.
8. Leave unsafe or unclear templates unchecked.
9. Save selected templates.

Variable mapping examples:

- `{{baseUrl}}` becomes `{{env.baseUrl}}`.
- `{{token}}` becomes `{{auth.accessToken}}`.
- `{{accountId}}` becomes `{{data.accountId}}`.
- `{{requestId}}` becomes `{{runtime.uuid}}`.
- Unknown variables become `{{legacy.name}}` with a warning.

Postman pre-request scripts, tests, and auth helpers are not executed. Corestra records warnings instead.

## 12. Matrix Rows

Open **Matrix**.

A matrix row combines:

- target template
- target auth profile
- target data profile
- setup config
- expected result
- optional evaluation profile
- optional post-checks
- optional teardown

Expected results:

- `ALLOW`: the actor should be allowed.
- `DENY`: the actor should be denied.
- `MASKED`: the actor may access the response, but sensitive fields must be hidden.
- `READ_ONLY`: the actor may read but should not cause mutation.
- `REVIEW`: policy is uncertain and requires manual review.
- `CUSTOM`: use custom assertions or an evaluation profile.

Generated rows are suggestions. They are not run automatically.

## 13. Generate Matrix Rows

Use **Generate Matrix** for suggested rows.

Basic strategies:

- Owner vs Others
- Anonymous Access
- Role Escalation

Advanced strategies:

- Maker Checker
- Cross Tenant
- Cross Branch
- Cross Role
- Object Ownership Variants

Before saving generated rows:

1. Review every row.
2. Edit expected results when policy differs.
3. Confirm setup configuration.
4. Keep generated rows disabled until reviewed.

Duplicate detection uses template, target auth profile, data profile, scenario instance, and setup config.

## 14. Setup and Scenario Variables

Some targets require runtime state, such as a confirmation token or object ID created earlier.

Use setup config:

- `NONE`: no setup.
- `RUN_PREFIX`: run steps before the target step.
- `RUN_SETUP_FLOW`: run a separate setup flow.
- `REUSE_SCENARIO`: reuse an existing Scenario Instance.

Use **Suggest Setup from Flow** when a target uses `scenario.*` variables. Corestra can inspect flow dependencies and suggest a prefix setup when earlier steps produce the required variables.

Scenario variable convention:

- Extract `scenario.confirmToken` to create `confirmToken` in the scenario variable map.
- Target templates can render `{{scenario.confirmToken}}`.

## 15. Evaluation Profiles

Use Evaluation Profiles when status codes are not enough.

Examples:

- HTTP 200 with body error code `ACCESS_DENIED` should count as expected DENY.
- HTTP 200 with body `success: true` should count as ALLOW.
- MASKED responses must not leak sensitive JSON fields.

Indicator types include:

- status code sets
- body contains or does not contain
- header exists
- header value contains
- JSON path equals
- JSON path exists or does not exist

Deny indicators take precedence over allow indicators.

## 16. Post-checks

Use post-checks to confirm whether a side effect happened.

Examples:

- Expected DENY but response is 200: read the object again and verify it was not modified.
- Transfer attempt: verify balance or transaction history.
- Delete attempt: verify the object still exists.
- Create attempt: verify the object was not created.

Post-checks run after the target request only when the target was sent successfully.

## 17. Teardown Flows

Use teardown when setup or target execution creates temporary objects.

Teardown can run after success, failure, or both depending on row configuration. Teardown warnings are added to the result, but teardown should not hide the main target verdict.

Dry-run mode renders teardown requests but does not send them.

## 18. Dry Run and Plan Safety

Before sending requests to sensitive systems, use **Dry Run / Render Only**.

Dry run:

- renders setup, target, post-check, and teardown requests
- reports unresolved variables
- reports missing auth/data/template/scenario config
- sends zero HTTP requests

Before starting a plan, Corestra shows a safety summary:

- enabled row count
- rows with setup
- rows with post-checks
- state-changing or consumable templates
- invalid diagnostics

Treat state-changing and consumable operations with extra care. Use limited test accounts and non-production data whenever possible.

## 19. Running Tests

Run one row:

1. Select a matrix row.
2. Click **Run Selected**.
3. Review verdict, response details, warnings, and evidence.

Run a plan:

1. Enable the rows you want to run.
2. Optionally enable Dry Run first.
3. Click **Start Plan**.
4. Review the confirmation summary.
5. Start the run.
6. Use **Stop** if needed.

Plan runs use a snapshot of configuration captured at start. Mid-run edits do not alter queued rows.

## 20. Race / Parallel Testing

Use Race Run only for authorized testing of race-condition hypotheses.

Race runs can send multiple target requests with configurable concurrency. Use dry-run first, configure teardown where needed, and avoid production systems unless explicitly authorized.

Each race batch has a worker-completion deadline. Workers that do not finish before the deadline are cancelled and reported as failed, so a stalled HTTP request cannot block the race coordinator indefinitely.

## 21. Results and Evidence

The Results panel shows:

- expected result
- actual verdict
- evaluation reason
- warnings
- request evidence
- response evidence
- post-check evidence
- teardown evidence

Evidence is redacted by default. Raw evidence export should be treated as sensitive and only used when explicitly required.

## 22. Finding Drafts

Use **Create Finding Draft** from a selected result to generate Markdown evidence.

The draft includes:

- suggested title
- affected endpoint/template
- target auth profile
- data profile and owner
- expected vs actual outcome
- evaluation reason
- redacted request and response evidence
- reproduction steps
- placeholders for impact and remediation

Review and edit before delivering to a client or internal team.

## 23. Project Import, Export, and Sanitization

Use sanitized export when sharing a project.

Sanitized export redacts:

- credentials
- auth state
- sensitive data-profile keys
- Authorization and Cookie headers
- common token fields in bodies and evidence
- hardcoded values in auth/session injection rules, flow overrides, and assertions when their target is sensitive
- Flowise and Access flow request snapshots

Captured step responses are omitted from sanitized and exclude-secrets project exports because they can contain arbitrary application secrets. Placeholder templates such as `Bearer {{auth.accessToken}}` are retained so an imported sanitized project remains configurable. Full export retains raw request/response and configured values and must be handled as sensitive data.

Access Testing sends actor-prepared authentication directly through the selected project transport. Project-wide Session Handler rules are not applied again to Access auth, setup, target, post-check, race, or teardown requests, which prevents an overlapping session profile from replacing the selected actor's credentials.

Use full export only inside trusted storage or when the recipient is authorized to receive secrets.

**Project > Scheduler Jobs** shows Session Handler maintenance jobs, actor/flow names, current phase, interval, last start/end, duration, last outcome, and estimated next run including backoff. Worker and queue counts help explain delayed runs. The monitor refreshes once per second only while Project is visible; hiding it does not stop scheduling. It keeps only runtime metadata in memory, adds no job history to Burp project persistence, and shows no HTTP messages or auth values. Manual or traffic-triggered authentication can move the next due time without appearing as a scheduler attempt. See [Keep-alive guide](guides/KEEP_ALIVE.md) for setup and state explanations.

The **Danger Zone** displays the exact UTF-8 size of Corestra's serialized string documents and the number of extension project entries. Burp container overhead is not included. **Clean Burp Project Data** removes all project-scoped Corestra flows, Authentication and Access Testing configuration/results/evidence, Session Handler profiles/state, metadata, settings, and quarantine data. Export anything needed before confirming. UI preferences stored outside project data are preserved.

## 24. Troubleshooting

No flows appear in Auth Provider dropdowns:

- Create the flow in Flowise first.
- Reopen the Auth Provider editor.
- If an existing provider references a deleted flow, the dropdown shows a missing-flow option so you can fix it.

Unresolved variables in Dry Run:

- Check placeholder spelling and scope.
- Confirm the selected Auth Profile and Data Profile provide required values.
- Confirm setup produced required `scenario.*` variables.

Auth retry does not happen:

- Confirm the Auth Profile uses a non-manual provider.
- Confirm failure rules match the target failure response.
- Confirm login or refresh flow is selected and enabled.

Proxy Pipeline reports a connection failure:

- Confirm **Project > Settings > Proxy host/port** points to an active Burp Proxy listener. The default is `127.0.0.1:8080`.
- For HTTPS targets, Corestra opens an HTTP CONNECT tunnel and then negotiates TLS through Burp. Current builds keep that CONNECT socket open and mark the CONNECT request so Corestra's own Session Handler/auto-trigger rules cannot process it recursively.
- The error includes both the configured listener and target authority. A connection-refused error indicates the listener address, while a CONNECT status or TLS-handshake error occurs after reaching the listener.
- Hover over the failed-run summary to see the complete error when the label is wider than the available panel space.

Unexpected ALLOW or DENY verdict:

- Review evaluation profile indicators.
- Check whether a business-deny response uses HTTP 200.
- Add post-checks for state-changing operations.

Imported template looks wrong:

- Review default scheme/host/base path.
- Convert OpenAPI YAML to JSON before import.
- Check import warnings for unsupported scripts, auth helpers, missing servers, or unknown variables.

## 25. Safety Notes

- Only test systems where you have permission.
- Prefer dry-run before sending real requests.
- Keep generated matrix rows disabled until reviewed.
- Mark state-changing and consumable templates accurately.
- Use post-checks for denied state-changing attempts.
- Use teardown for dynamic test data.
- Share sanitized exports by default.
