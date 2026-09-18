---
root: true
---
# Job Hunter Scraper

Stateless Kotlin service collecting vacancies through typed source adapters.
The Job Hunter API and PostgreSQL own schedules, runs, leases, checkpoints, and jobs.

## Working contract

- Keep source collection separate from matching and user workflow policy.
- Follow the existing Kotlin API style and keep changes small and cohesive.
- Never log credentials, proxy URLs, tokens, personal profiles, or response bodies.
- Use English for source, comments, documentation, and Conventional Commits.
- `.rulesync/` is canonical; generated instruction files are derived outputs.

## Commands

```sh
./gradlew build
./gradlew test
./gradlew ktlintFormat
./gradlew bootRun
npm ci
npm run rulesync:verify
```

Use Java 21. The worker is disabled unless explicitly configured.

## Structure

- `application/` owns collection execution and its typed contracts.
- `infrastructure/source/` contains one adapter per source.
- `infrastructure/api/` implements the scoped Job Hunter API client.
- `infrastructure/http/` owns transport, bounded retries, and safe observations.
- `infrastructure/config/` owns configuration and runtime beans.
- Keep adapters composed from shared services; avoid an inheritance framework.
- Use injected clocks and HTTP clients for deterministic tests.

## Source correctness

- Persist a checkpoint only with acknowledged API ingestion.
- Distinguish malformed responses, incomplete coverage, and legitimate empty data.
- Preserve unknown remote status; missing evidence is not an onsite decision.
- Keep each source's category attached to its request and parsed output.
- Add captured-response regression fixtures for parser changes.
- Verify cursor termination, duplicates, failed details, and schema changes.

## Operations

Read the README and operations guide before changing configuration or deployment.
Use the existing GitOps deployment and observability stack. Keep trace context
across execution and HTTP boundaries. Run IDs belong in logs and traces, not
metric labels. Update source behavior and operator instructions with code.
