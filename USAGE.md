# Corestra Usage Guide

Corestra is a Burp Suite extension for building reusable request flows and access-control test plans. It is designed for authorized security testing of applications where a single useful request often depends on login, setup, object creation, token extraction, request mutation, and post-check verification.

This guide explains how to install the extension, configure flows, build Access Testing artifacts, run tests safely, and export evidence.

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

Corestra has two major work areas:

- **Flowise**: Build reusable multi-step HTTP flows.
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
3. Configure Auth Providers and Auth Profiles.
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
2. Give the flow a clear name, for example `Login - Alice`, `Refresh Token`, `Create Transfer`, or `Read Account`.
3. Add steps from Burp traffic or manually.

Add a step from Burp:

1. Capture or select a request in Proxy history, Repeater, Logger, or another supported request view.
2. Use the Corestra context-menu action to add it to a flow.
3. Review the stored request in Flowise.

Add extract rules:

- Use extract rules when a response or request contains a value needed later.
- Common examples:
  - Extract `accessToken` from login JSON into `auth.accessToken`.
  - Extract `confirmToken` from a setup response into `scenario.confirmToken`.
  - Extract `transferId` into `scenario.transferId`.

Add inject rules:

- Use inject rules when a later request needs a value from an earlier step.
- Common examples:
  - Inject `{{auth.accessToken}}` into the `Authorization` header.
  - Inject `{{scenario.confirmToken}}` into a JSON body.
  - Inject `{{data.accountId}}` into a request path or body.

Keep flows small and purpose-specific. It is usually better to have separate login, refresh, setup, target, post-check, and teardown flows than one large flow that does everything.

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

## 7. Access Testing: Auth Providers

Open **Corestra > Access Testing > Auth Providers**.

An Auth Provider defines how Corestra obtains and applies authentication material.

When adding or editing a provider:

1. Enter a provider name.
2. Enable or disable the provider.
3. Select the **Login Flow** from the dropdown.
4. Select the **Refresh Flow** from the dropdown if refresh is supported.
5. Select the **Validate Flow** from the dropdown if Corestra should check auth state before refreshing or logging in.
6. Choose a fallback policy.
7. Choose a target retry policy.
8. Configure injection rules.
9. Configure success and failure rules.

The Login, Refresh, and Validate controls list flows already created in Flowise. Use **None** when a policy does not need that flow.

Typical provider setup:

- Login Flow: a flow that sends credentials and extracts `auth.accessToken`, cookies, CSRF tokens, or other auth variables.
- Refresh Flow: a flow that uses `auth.refreshToken` or cookies to obtain a new access token.
- Validate Flow: a low-impact request that confirms the current auth state is still valid.
- Injection rule: inject `Bearer {{auth.accessToken}}` into the `Authorization` header.
- Success rule: status code is 200, or body contains a success marker.
- Failure rule: status code is 401 or 403, or body contains an auth error code.

Do not store real production credentials in shared exports. Use sanitized export when sharing projects.

## 8. Access Testing: Auth Profiles

Open **Auth Profiles**.

Create one Auth Profile per actor:

- `alice-user`
- `bob-user`
- `branch-manager`
- `tenant-admin`
- `anonymous`

For each profile:

1. Select a provider, or use manual auth.
2. Set the role label.
3. Add credentials or auth state only when needed.
4. Keep profiles enabled only when they should be considered by matrix generation and plan runs.

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

Use full export only inside trusted storage or when the recipient is authorized to receive secrets.

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
