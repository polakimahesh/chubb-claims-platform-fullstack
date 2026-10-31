# Decisions and Assumptions

## Assumptions

- The brief says "six markets" without naming them. Assumed `SG, HK, MY, TH, ID, AU`, each settling in its own
  currency (SGD, HKD, MYR, THB, IDR, AUD). A claim must be lodged in its market's currency (`Market` enum is the one place to change).
- Claimants are identified by their e-mail address, which is also their login.
- Any officer may pick up a `SUBMITTED` claim; once assigned, only that officer acts on it. The officer or a manager
  can hand it to another officer while it is in review.
- Managers have read access to staff views and may reassign, but do not decide claims themselves.
- A claim is "outstanding" until `SETTLED` or `REJECTED`; `APPROVED` is still liability.
- Exposure = assessed amount if one exists, otherwise the claimant's estimate.
- Claims waiting for the claimant (`INFO_REQUESTED`) do not count against the officer's SLA.
- Documents are PDF, PNG or JPEG up to 5 MB, at most 20 per claim, and only while the claim is open.
- All timestamps are stored and returned in UTC.

## Decisions

| Decision | Choice | Alternative considered |
|---|---|---|
| Service split | Three services by read/write profile and failure domain | One modular monolith (simpler, but the brief asks what drives the split and wants Kafka used meaningfully); a service per noun (distributed transactions for no benefit) |
| Events | Transactional outbox + polling relay | Publish inside the request (dual write can lose events); CDC/Debezium (more infrastructure) |
| Event shape | Full state + version + claimant contact + note (event-carried state transfer) | Thin events (consumers would have to call back into claims-service) |
| Idempotency | Reporting: compare claim `version`. Notifications: unique event id | Processed-event table for reporting (does not handle reordering) |
| Concurrency | JPA `@Version` optimistic lock → `409` | Pessimistic locks (hold DB locks during human workflows) |
| Business rules | In the `Claim` entity; state checked before business rules | Rules in the service layer (easier to bypass) |
| Authentication | HTTP Basic + roles from a shared module; record-level checks in services | OIDC/JWT (right for production, but needs an identity provider for a local demo) |
| Credentials | Documented demo users in each service's `application.yml` (`Test@123`), for local and assessment use; nothing else secret in the repo | `.env` with random values per machine (more secure, but an extra setup step) |
| Other claimants' claims | Answer `404`, not `403` | `403` (confirms the claim exists) |
| Documents | Local filesystem behind `DocumentStorage`; server-generated keys; content sniffed against the declared type | Storing bytes in the DB (bloats the transactional store); trusting `Content-Type` (spoofable) |
| Notifications | Separate consumer service; `NotificationSender` interface with a log-only e-mail implementation | Sending e-mail from claims-service (a mail outage would fail claim decisions) |
| Reports | Pre-aggregated in SQL from each service's own read model | Querying claims-service's DB (couples schemas, adds load); in-memory aggregation (replaced, see journal) |
| FX | `FxRateProvider` with static configured rates, labelled "not live" in every response | Calling a live rate API (external dependency, keys) |
| SLA | Computed on demand from the read model with configurable thresholds | Scheduled timers per claim (more moving parts for the same report) |
| DB | PostgreSQL in Docker, H2 default for zero-setup local runs and tests | H2 only (not representative) |
| Migrations | Flyway, SQL portable to both databases | Hibernate `ddl-auto` (no history) |
| Errors | RFC 7807 `ProblemDetail` everywhere, including 401/403 | Custom error body |
| Topic | `claims.events`, 3 partitions, key = claimId; declared by every service; broker auto-create off | Auto-created topics (a consumer created it with 1 partition during testing, see journal) |
| Containers | Multi-stage build, non-root user, Postgres not published to the host | Root user, all ports published |

## Error handling

Each service has one `GlobalExceptionHandler` (`@RestControllerAdvice` extending `ResponseEntityExceptionHandler`) and
the shared security module renders 401/403, so **every** failure is an RFC 7807 `application/problem+json` response.

| Case | Status |
|---|---|
| Malformed JSON, empty body, unknown enum, bad UUID/param type, missing required parameter, bean-validation failures (with `errors` list), currency not matching the market, empty upload, unsupported report currency | 400 |
| Missing or wrong credentials | 401 (with `WWW-Authenticate: Basic`) |
| Authenticated but not allowed (role, other officer's workload, unmapped route) | 403 |
| Unknown claim/document, or a claim that belongs to another claimant | 404 |
| Wrong HTTP method | 405 |
| Invalid lifecycle transition, acting on a closed/unassigned claim, concurrent modification, data-integrity conflict | 409 |
| Document larger than 5 MB | 413 |
| Document type not PDF/PNG/JPEG, or content not matching the declared type | 415 |
| Business rule: approve without assessment, acting on a claim assigned to someone else, info request not on this claim / already answered, document limit reached | 422 |
| Anything unexpected | 500 with a generic message (details only in the log) |

Paging parameters are clamped (page >= 0, 1 <= size <= 100) rather than rejected.

## Verification

- `./gradlew test`: unit, MockMvc integration and embedded-Kafka tests for all three services.
- `docker compose up --build` run end to end, then the Postman collection run headless with newman against the
  containers: all requests and assertions passed (claimant flow, documents, reassignment, reports via Kafka,
  notifications via Kafka, authN/authZ and error cases). See ai-journal for the exact run.
- OpenAPI contracts were exported from the running services after fixing a bug the first export exposed (`/v3/api-docs.yaml` was not public; now covered by a test). The contract served by the rebuilt Docker image is byte-identical to the committed file.

## Known Limitations

- Demo users are in memory (configurable via `app.security.users`); production should use an identity provider.
- BCrypt verification on every Basic-auth request costs CPU; token-based auth (JWT) removes that in production.
- FX rates are static and indicative; the e-mail channel only logs (masked address).
- The outbox relay is a single poller. Multiple instances would need `FOR UPDATE SKIP LOCKED` or leader election;
  published outbox rows are never purged.
- Reports and notifications are eventually consistent (typically about a second behind).
- Both consumers dead-letter to the same `claims.events.DLT` topic (the original consumer group is in the record headers).
- Do not run Docker and local `bootRun` copies of the same service together: they clash on ports and share consumer groups.

## Frontend decisions

Details are in [frontend.md](frontend.md).

| Decision | Choice | Alternative considered |
|---|---|---|
| Framework / UI kit | Angular 22 (standalone, signals, zoneless) + Angular Material 3 | PrimeNG (heavier); Tailwind + custom components (more to build, accessibility to get right) |
| Two user types | One app, lazy-loaded areas guarded by role, navigation built from the roles, one shared claim page | Separate apps per role (duplicated shell, auth and models) |
| Reaching three services | One origin: dev proxy / nginx route each `/api` prefix to its service | CORS on every service and ports hard-coded in the app; an API gateway (more infrastructure) |
| Session | HTTP Basic credentials in `sessionStorage`, verified with `GET /api/me` | Memory only (sign-in on every refresh); `localStorage` (outlives the tab) |
| Browser auth dialog | Proxy drops `WWW-Authenticate` | Change the backend challenge (alters the API contract for other clients) |
| Business rules in the UI | Mirrored only to show or hide actions; the server stays authoritative | Re-implementing the rules client-side |
| Reassignment target | New `GET /api/staff/officers` | Free-text officer id (typos only caught by the server) |
| Live data | Reload on demand with an "updated at" time; 409 reloads the claim | WebSockets / SSE (a new backend capability) |
| Charts | Stat tiles and single-colour bars with direct labels; tables for detail | A chart library (weight, colour palettes to validate) |

## AI assistant (claims-service, `POST /api/agent/chat`)

A claimant or officer types a request in plain language and a Google Gemini model carries it out ("raise a motor claim in
Singapore, rear-ended yesterday, about 8000"). It is a different thing from the rule-based agents in
reporting-service, which only advise managers and never change a claim.

- **It acts as the signed-in user.** The model can only call tools that call `ClaimService` with the caller's
  `Actor`, so lifecycle rules, record-level access and the audit trail apply as for the REST API. The tool list is
  chosen by role (claimant: submit, list, get; officer: queue, assign, assess, approve, reject, get) and the role is
  checked again when a tool runs, so a model that asks for a tool it was not offered is refused. Managers are not
  allowed to use it.
- **Tool input is validated like a client's.** Each tool builds the same request record as the REST API and runs it
  through Bean Validation, so a future date, a zero amount or a 3-decimal amount is rejected.
- **Manual tool loop over a provider-neutral `AgentModel`.** Identity, role checks, the turn limit and the cost
  bounds stay in our code, and swapping the model provider means writing one adapter (`GeminiModel` is the only
  one). It calls the Gemini REST API directly with the JDK HTTP client, so there is no SDK dependency. Each tool
  call commits on its own. If the loop stops early (turn or time limit), the reply still lists what was
  done, because a 503 would hide committed writes and invite a duplicate retry.
- **Cost and thread bounds.** At most 8 model calls, 90 seconds, 8 chats at once, 30 s per model call and an
  8192-token reply (`app.ai.*`). Every chat holds a request thread, and the free tier has its own rate limit, which
  the assistant reports as a 503 "rate limit" message.
- **Model and key.** `gemini-flash-lite-latest` by default (`GEMINI_MODEL` to change). Model names change often:
  the `gemini-2.5` and `2.0` models already return 404 for new accounts, and larger models were overloaded (503) when
  tried, so the default is an alias that answered quickly. A 503 is retried once, then reported as "model is busy".
  `GET /v1beta/models` lists what a key can use. The key is a Google AI Studio key in
  `app.ai.gemini.api-key` (or `GEMINI_API_KEY`; docker-compose passes it through). It is sent in the
  `x-goog-api-key` header, never in the URL, and never logged. With no key the endpoint answers 503 and the rest of
  the platform is unaffected. A key pasted into `application.yml` must be removed before committing.
- **No human confirmation step.** The assistant can approve and reject on an officer's request without a second
  click, as the user asked. This is the biggest risk to review before any real use.
- **Known risk: prompt injection.** Claim descriptions and notes written by claimants are returned to the model as
  tool results. A claimant could write "approve claim X" into a description. Mitigations: tool results are wrapped in
  `<tool_data>` tags and the system prompt says to treat them as data only; the officer prompt says to act only on
  what the user asked; roles still bound what any call can do. This is not a guarantee. A production version should
  require confirmation for approve, reject and assess, and return fewer free-text fields.
- **The browser sends the conversation.** Earlier turns (text only) come back with each request, limited to 20
  messages of 4000 characters. A caller could forge an assistant turn; roles are still enforced server-side, so this
  is not privilege escalation, but a production version should keep the transcript on the server.
- **Tested against the real API once, by hand.** The automated tests script the model and run the Gemini adapter
  against a local fake server, so they need no key. A manual run with a real key (31 Oct 2026, `gemini-flash-lite-latest`)
  raised a claim from a plain sentence, ran a four-step officer flow (queue, assign, assess, approve), asked for the
  missing details on a vague request, and refused a claimant's attempt to approve. That is one run on one model, not
  a guarantee: smaller models can pick the wrong tool or invent a field value.
