# Operations

## Ownership and recovery

Only the Job Hunter API changes durable scraping state. A source claim returns a
five-minute lease and a criteria snapshot. The worker renews its lease every
30 seconds, ingests pages in batches of at most 250 jobs, and advances a checkpoint
only with an acknowledged batch. Repeating a batch ID is idempotent; stale lease
tokens cannot write. An expired lease lets another worker resume the run.

Every new run uses `SCRAPING_LOOKBACK` on the API, defaulting to one hour before
its start. Retries retain that lower bound and their checkpoint. LinkedIn queries
the upstream rolling last-hour feed on each search, including retries; it does not
reconstruct an immutable historical snapshot. Sources with date-only timestamps
use calendar-day overlap. A new run does not backfill outages older than the
configured lookback.

LinkedIn returns the last collected page for acknowledged ingestion before its
next fetch reports the configured or upstream coverage cap. The checkpoint then
retains that boundary, so retries do not discard or refetch the acknowledged
portion. A capped run still fails explicitly rather than claiming full coverage.

The API schedules successful sources 15 minutes after completion. Failed runs retry with
bounded backoff before a fresh scheduled run. The scraper polls for claims every
15 seconds and runs sources independently. A failure in one source does not stop
the others. Do not reset checkpoints directly in PostgreSQL.

`GET /scraping/status` on the API exposes each source's enabled state, current run,
last success/error, and persistent counters. It requires `read:jobs`. Enable
sources through API deployment configuration `SCRAPING_ENABLED_SOURCES` and limit
workers with `SCRAPER_SOURCES`; both gates must permit a source.

## EUremotejobs source contract

The WordPress REST taxonomy and listing routes no longer allow anonymous access.
The adapter uses the public website search: it reads the available technology
values and public form nonce from the homepage, then posts URL-encoded form data
to `wp-admin/admin-ajax.php` with `action=erj_ajax_search`. Technology matching is
exact and case-insensitive; Java does not match Javascript. The nonce is fetched
for each page and never stored in checkpoints, jobs, or telemetry.

Every public request uses the source proxy and fingerprint supplied by the API.
One proxy is retained across the homepage, form request, and job details for a
page. An unavailable proxy pool fails the attempt without a direct fallback;
the API rotates the source proxy on the next page or retry. Proxy credentials and
fingerprints never enter checkpoints or job data.

The public result supplies HTML cards and a boolean `has_more`. Traverse all
reported pages because featured records can change ordering. A repeated page,
malformed response, failed detail, or coverage cap fails the run. A missing exact
technology or an explicitly exhausted empty result is a legitimate empty category.
The existing `categoryIndex` and `page` checkpoint keys remain compatible; a page
fingerprint detects a repeated result without retaining public form state.

Cards expose only a local calendar date. Apply a one-day timezone margin before
skipping older cards; accepted candidates are checked against the precise JSON-LD
`datePosted` after details. Unknown dates remain eligible. Detail URLs must belong
to the configured source origin and `/job/` path. Missing remote evidence stays
unknown. The default endpoint is the homepage origin, not `/wp-json/wp/v2`.

## Observability

The scraper exposes Actuator health probes and Prometheus metrics. Persistent
source health and ingestion counters are exposed by the API under
`jobhunter_scraping_*`. Alert on failed or stale runs, not the absence of new
vacancies: legitimate publishing volume differs substantially between sources.

Run and page spans propagate W3C trace context to API requests. ECS JSON logs carry
trace/span IDs. Production exports OTLP/HTTP directly to VictoriaTraces using the
cluster's existing endpoint. Export is asynchronous and best effort; it is not a
business-data queue. A telemetry backend outage must not block collection.
Tokens, proxy credentials, HTTP bodies, and user profiles must never appear in
logs, spans, or metric labels. Run IDs belong in traces and logs only.

## Rollout and rollback

1. Release the backwards-compatible API control plane with all sources disabled.
2. Deploy the scraper with collection disabled. Verify probes, authentication,
   source fixtures, current source responses, metrics, and stored traces.
3. Back up the dedicated Job Hunter n8n workflows and configuration. For each
   source, stop its legacy schedule before enabling its API-owned schedule.
4. Verify a complete run, accepted jobs, deduplication, category/remote/salary
   mapping, and correct checkpoint recovery before enabling the next source.
5. Retire dedicated n8n resources only after all eight replacements work and the
   rollback export has been verified. Preserve the unrelated shared n8n service.

For rollback, first disable the affected API source and wait for the active run to
stop or its lease to expire. Then restore its legacy schedule from the verified
export. Never run both schedulers for the same source during cutover.

All cluster configuration changes go through the deployment repository and Argo
CD. Read-only cluster checks are appropriate; manual edits are not a deployment
mechanism. Keep release image tags and source revisions available for rollback.
