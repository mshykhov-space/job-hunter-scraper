# Job Hunter Scraper

Typed vacancy collection for [Job Hunter](https://github.com/mshykhov/job-hunter).
The service runs source adapters and submits normalized jobs to the Kotlin API.
PostgreSQL behind the API owns schedules, leases, retries, checkpoints, and ingestion
receipts. The scraper has no database or persistent volume.

## Stack

Java 21, Kotlin, Spring Boot, Jackson, Jsoup, Micrometer/OpenTelemetry, and JUnit 5.
LinkedIn discovery and enrichment use a separate Python JobSpy HTTP process.
Production deployment uses Docker, Helm, and Argo CD.

## Run locally

```sh
./gradlew build
./gradlew bootRun
```

The worker defaults to disabled and starts without credentials. Health and metrics
are available on port 8080 at `/actuator/health` and `/actuator/prometheus`.
To collect jobs, configure an API with the scraping control-plane endpoints, enable
the desired sources on that API, and supply these environment variables:

| Variable | Purpose |
| --- | --- |
| `SCRAPER_ENABLED` | Enable collection, default `false` |
| `SCRAPER_SOURCES` | Comma-separated source IDs; defaults to all eight |
| `JOB_HUNTER_API_URL` | Job Hunter API base URL |
| `AUTHENTIK_TOKEN_URL` | OAuth token endpoint |
| `JOB_HUNTER_CLIENT_ID` | Machine client ID |
| `JOB_HUNTER_USERNAME` | Machine account username |
| `JOB_HUNTER_PASSWORD` | Machine account application password |
| `TRACING_ENABLED` | Enable OTLP export, default `false` |
| `OTLP_TRACES_ENDPOINT` | Complete OTLP/HTTP traces URL |

Keep credentials in the environment or a secret store. The machine identity needs
`read:jobs` and `write:jobs`. Production secrets come from Doppler through External
Secrets Operator. See [operations](docs/operations.md) for recovery and rollout.

## Sources

| ID | Transport |
| --- | --- |
| `djinni` | HTML listings |
| `dou` | RSS feeds |
| `euremotejobs` | WordPress JSON API |
| `justjoinit` | Candidate JSON API |
| `landingjobs` | JSON API |
| `linkedin` | JobSpy search and enrichment |
| `nofluffjobs` | Search and detail JSON APIs |
| `web3career` | HTML and JSON-LD |

Adapters preserve source categories, original salary units, and unknown remote
status. Pagination and schema failures fail the run instead of reporting an
incomplete traversal as successful. Source response fixtures cover parser behavior;
production egress and current source schemas must also be checked before cutover.

## Structure and verification

`application/` contains execution and contracts; `infrastructure/` contains source
adapters, HTTP transport, API authentication, and configuration. Source adapters
compose shared clients and do not own scheduling or matching policy.

```sh
./gradlew test ktlintCheck
npm ci
npm run rulesync:verify
```

Rulesync manages repository instructions from `.rulesync/`. Release tags `vX.Y.Z`
build and publish `ghcr.io/mshykhov/job-hunter-scraper:X.Y.Z` through GitHub Actions.
