# Job Hunter Scraper

Typed vacancy collection for [Job Hunter](https://github.com/mshykhov-space/job-hunter).
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
`read:jobs`, `write:jobs`, and `read:proxies`. Production secrets come from Doppler through External
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

Sources apply the API-provided publication window before detail enrichment:

| Source | Publication value | Window and pagination behavior |
| --- | --- | --- |
| `justjoinit` | ISO `publishedAt` | Requests `sortBy=publishedAt&orderBy=descending`, stops after a wholly old page, and fetches details only for accepted jobs. |
| `nofluffjobs` | `posted` epoch milliseconds | Search results use relevance ordering, so all reported listing pages are checked; old listings never trigger detail requests. |
| `landingjobs` | ISO `published_at` | The active catalogue is unordered and filtered locally; pagination continues until a short page. |
| `euremotejobs` | WordPress UTC `date_gmt` | Sends `after`, `orderby=date`, and `order=desc`; normalizes UTC to an instant and filters the response defensively. |
| `linkedin` | JobSpy date or timestamp | Requests a rolling `hours_old=1` window and filters precise candidates before enrichment. |
| `dou` | RFC 1123 timestamp | Normalizes publication time to an instant and filters each listing before enrichment. |
| `web3career` | ISO offset timestamp | Normalizes offsets and stops after a latest-first page contains only precise timestamps older than the window. |
| `djinni` | Offsetless local timestamp | Uses UTC calendar-day overlap because sub-day precision is unavailable; pagination is not stopped early. |

Unknown or malformed timestamps remain eligible to avoid silently losing jobs. A
date-only value is eligible only when its UTC calendar day intersects the claimed
window.

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
build and publish `ghcr.io/mshykhov-space/job-hunter-scraper:X.Y.Z` through GitHub Actions.
