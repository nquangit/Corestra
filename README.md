# Corestra

Corestra is a Burp Suite extension for reusable multi-step HTTP flows, project session handling, and actor-isolated access-control testing.

Features:
- Capture requests (with responses) from anywhere in Burp into reusable **flows**.
- Ordered, drag-reorderable steps with enable/disable and metadata badges.
- Extract values (header, cookie, JSON path, regex, body, status) into variables and inject them
  into later requests (header, cookie, JSON path, regex).
- Run a whole flow or a single step on demand, with a per-step result table.
- Automatic prerequisite execution via Burp **session-handling** integration.
- Optional response **aggregation** into a synthetic final response.
- Burp-native request/response editors and theme-aware UI.
- Flow-backed Auth Providers, per-actor Auth Profiles, matrix generation, dry-run, evaluation, evidence, and findings.

Documentation:
- [Usage guide](USAGE.md)
- [Access Testing setup](guides/ACCESS_TESTING.md)
- [Authentication recipes](guides/AUTH_RECIPES.md)
- [Architecture](docs/architecture.md)
- [User guide](docs/user-guide.md)
- Full task plan: `docs/plans/corestra-flowise-implementation-plan.md`

Architecture intent:
- Keep the Burp extension entrypoint thin and use it only as a composition root.
- Keep flow execution, extraction, injection, and persistence logic domain-first and unit-testable.
- Keep Swing and Montoya integration at the edges, with Burp-native editors and theme integration used by the UI.

Build baseline:
- Java release target: 21
- Intended Java package root: `dev.nquangit.corestra`
- Montoya API: `2026.4`
- Test framework: JUnit 5
- JSON handling: Jackson
- Packaging: Maven Shade produces the Burp-loadable extension JAR

Verification status:
- Local verification uses Maven.

Verification commands:
```bash
mvn -q test
mvn -q package
```

Expected package output:
- Thin JAR: `target/corestra-0.1.0-SNAPSHOT.jar`
- Shade-packaged scaffold artifact: `target/corestra-0.1.0-SNAPSHOT-all.jar`

Key workspace documents:
- `AGENTS.md`
- `docs/plans/corestra-flowise-implementation-plan.md`
- `docs/research/montoya-reference.md`
