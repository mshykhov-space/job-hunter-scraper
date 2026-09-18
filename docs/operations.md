# Operations

## Ownership and recovery

Only the Job Hunter API changes durable scraping state. A source claim returns a
five-minute lease and a criteria snapshot. The worker renews its lease every
30 seconds, ingests pages in batches of at most 250 jobs, and advances a checkpoint
only with an acknowledged batch. Repeating a batch ID is idempotent; stale lease
tokens cannot write. An expired lease lets another worker resume the run.

The API schedules successful sources every 15 minutes. Failed runs retry with
bounded backoff before a fresh scheduled run. The scraper polls for claims every
15 seconds and runs sources independently. A failure in one source does not stop
the others. Do not reset checkpoints directly in PostgreSQL.

`GET /scraping/status` on the API exposes each source's enabled state, current run,
last success/error, and persistent counters. It requires `read:jobs`. Enable
sources through API deployment configuration `SCRAPING_ENABLED_SOURCES` and limit
workers with `SCRAPER_SOURCES`; both gates must permit a source.

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
