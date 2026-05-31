# Corestra

Corestra is a Burp Suite extension workspace for building reusable multi-step request flows for penetration testing. The intended capability set includes variable extraction from earlier traffic, injection into later requests, prerequisite execution, and optional aggregation of multi-step output.

Features:
- Capture requests (with responses) from anywhere in Burp into reusable **flows**.
- Ordered, drag-reorderable steps with enable/disable and metadata badges.
- Extract values (header, cookie, JSON path, regex, body, status) into variables and inject them
  into later requests (header, cookie, JSON path, regex).
- Run a whole flow or a single step on demand, with a per-step result table.
- Automatic prerequisite execution via Burp **session-handling** integration.
- Optional response **aggregation** into a synthetic final response.
- Burp-native request/response editors and theme-aware UI.

Documentation:
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
- Packaging: Maven Shade baseline artifact for scaffold packaging; not yet a functioning Burp extension

Verification status:
- Local verification in this WSL environment currently depends on Maven availability.
- Maven is not installed here at the moment, so Task 0 defines the build and verification gate but does not claim a successful local build.
- Do not treat later implementation work as verified until Maven is available locally or another agreed build path exists.

Verification commands once Maven is available:
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
