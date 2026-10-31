# Claims Platform

A full-stack motor and property claims platform for six APAC markets, built for the Chubb APAC full-stack take-home
assessment. **One Angular application** serves claimants and claims staff, on top of **three Spring Boot services**
that communicate over **REST and Kafka**.

- **Claimants** report an incident, track their claim, answer information requests, upload documents and receive
  decisions.
- **Claims officers** pick up claims from an intake queue, review and assess them, and settle or reject them.
- **Managers** see team workload and performance, SLA breaches and outstanding liability exposure.

> **Quick start:** `docker compose up --build`, then open **http://localhost:8080** and sign in as a
> [demo user](#demo-users), for example `officer-1` / `Test@123`.

---

## Contents

- [Technology stack](#technology-stack)
- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Demo users](#demo-users)
- [Testing](#testing)
- [API overview](#api-overview)
- [Documentation](#documentation)
- [Assessment and AI-assisted development](#assessment-and-ai-assisted-development)

---

## Technology stack

### Frontend

| Category | Technology |
|---|---|
| Framework | **Angular 22.2**: standalone components, signals, zoneless change detection, lazy-loaded routes |
| UI library | **Angular Material 22.2** (Material 3) with a custom light/dark theme and design tokens |
| Language | TypeScript 6.0, SCSS |
| State and data | Angular signals, RxJS 7.8, `HttpClient` with a functional interceptor |
| Forms | Typed reactive forms with validators that mirror the backend's |
| Unit tests | **Vitest 5** (`ng test`, jsdom) with V8 coverage |
| End-to-end tests | **Playwright 1.64** (Chromium desktop and Pixel 7) with **axe-core** accessibility scans |
| Serving | Angular dev server and proxy (development); **nginx** (production container) |

### Backend

| Category | Technology |
|---|---|
| Language and runtime | **Java 17** |
| Framework | **Spring Boot 3.3.5**: Web MVC, Data JPA, Validation, Security, Actuator |
| Build | **Gradle 8.10** (wrapper), multi-module |
| Messaging | **Apache Kafka 3.8** (KRaft) with Spring for Apache Kafka |
| Database | **PostgreSQL 16** (Docker) or H2 (local and tests), schema managed by **Flyway** |
| API | REST + JSON, **OpenAPI 3** (springdoc 2.6, Swagger UI), RFC 7807 error responses |
| Security | Spring Security, HTTP Basic, roles `CLAIMANT` / `OFFICER` / `MANAGER` |
| Tests | JUnit 5, AssertJ, MockMvc, embedded Kafka, **JaCoCo** coverage |

### Infrastructure and tooling

Docker and Docker Compose · Node.js 22+ · npm · Postman / newman · Claude Code

Full details with versions and what each one is used for: **[docs/technology-stack.md](docs/technology-stack.md)**

---

## Architecture

```
                       ┌───────────────────────────────────────────┐
  Claimant / Officer / │  claims-web  (Angular 22 SPA)             │
  Manager  (browser) ─►│  served by nginx :8080 (dev: ng serve)    │
                       └───────────────┬───────────────────────────┘
                                       │ REST, one origin; nginx routes by path
          ┌────────────────────────────┼─────────────────────────────┐
          │ /api/claims /api/staff     │ /api/reports                │ /api/notifications
          ▼ /api/me                    ▼                             ▼
 ┌──────────────────┐       ┌────────────────────┐       ┌──────────────────────┐
 │  claims-service  │       │ reporting-service  │       │ notification-service │
 │  :8081           │       │ :8082              │       │ :8083                │
 │  system of record│       │ read model, reports│       │ claimant e-mails     │
 └───────┬──────────┘       └─────────▲──────────┘       └──────────▲───────────┘
         │ transactional outbox       │                             │
         └────────► Kafka topic `claims.events` ─────────────────────┘
         │                            │                             │
     PostgreSQL: claims        PostgreSQL: reporting      PostgreSQL: notifications
```

- The **frontend** talks to the backend only over **REST**, through one origin, so no CORS is needed.
- **Commands** (submit, assess, approve, and so on) go to `claims-service`, which answers immediately with the updated
  claim.
- Every change is published to **Kafka** through a transactional outbox. `reporting-service` and
  `notification-service` consume the events independently (eventually consistent, typically within a second).

More detail, including the frontend and backend internals, security and reliability:
**[docs/architecture.md](docs/architecture.md)**

---

## Project structure

```
chubb-claims-platform/
├── claims-web/             Angular frontend (core · layout · shared · features), e2e tests, nginx config
├── claims-service/         Spring Boot: claims lifecycle, REST API, documents, outbox → Kafka
├── reporting-service/      Spring Boot: Kafka consumer, read model, manager reports
├── notification-service/   Spring Boot: Kafka consumer, claimant e-mail notifications
├── claims-events/          Shared library: Kafka event contract
├── claims-security/        Shared library: HTTP Basic security, roles, error responses
├── docs/                   Architecture, stack, structure, guides, testing, decisions
├── docker/                 Database init
├── docker-compose.yml      Full stack: Postgres, Kafka, three services, web
├── ai-journal/ · prompts/  AI working journal and the prompts used
└── assessment-brief/       The assessment brief and repository guidelines
```

Every folder and its responsibilities: **[docs/project-structure.md](docs/project-structure.md)**

---

## Getting started

### Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker Desktop | recent, at least 4 GB of memory | the one-command setup |
| JDK | 17 | running the services outside Docker |
| Node.js | 22 or newer | running the frontend outside Docker |

### Option 1: Docker (recommended)

```bash
docker compose up --build      # Postgres, Kafka, the three services and the web app
```

| URL | What |
|---|---|
| http://localhost:8080 | **Web application** |
| http://localhost:8081/swagger-ui.html | Claims API (Swagger UI) |
| http://localhost:8082/swagger-ui.html | Reporting API |
| http://localhost:8083/swagger-ui.html | Notification API |

**AI assistant (optional).** Claimants and officers can ask an AI assistant to do claim work in plain language (menu:
*AI assistant*). It uses Google Gemini and needs a free key from [aistudio.google.com](https://aistudio.google.com).
Without one, everything else works and the assistant answers 503.

```bash
# put the key in claims-service/src/main/resources/application.yml (app.ai.gemini.api-key), or:
export GEMINI_API_KEY=...      # PowerShell: $env:GEMINI_API_KEY="..."
docker compose up --build
```

Never commit a real key.

### Option 2: Local development

```bash
docker compose up -d kafka                              # Kafka only
./gradlew :claims-service:bootRun                       # terminal 1 → :8081
./gradlew :reporting-service:bootRun                    # terminal 2 → :8082
./gradlew :notification-service:bootRun                 # terminal 3 → :8083
cd claims-web && npm ci && npm start                    # terminal 4 → http://localhost:4200
```

> Don't run Docker copies and local copies of the same service at the same time. They clash on ports and Kafka
> consumer groups.

Configuration, database, production build and troubleshooting: **[docs/developer-guide.md](docs/developer-guide.md)**

---

## Demo users

All demo users share the password **Test@123**. It is set as `app.security.demo-password` in each service's
`src/main/resources/application.yml`; to change it, edit it in all three. These are demo credentials for local
development and assessment only, not real secrets.

| Username | Role | Lands on |
|---|---|---|
| `tan@example.com`, `lee@example.com` | Claimant | My claims |
| `officer-1`, `officer-2` | Claims officer | Intake queue |
| `manager-1` | Claims manager | Dashboard |

**A two-minute tour:**
1. As `tan@example.com`, report an incident with a PDF attached and note the reference number.
2. As `officer-1`, pick the claim up from the intake queue, then request information.
3. As `tan@example.com`, answer the request (it's also under Notifications).
4. As `officer-1`, assess, approve and settle the claim.
5. As `manager-1`, open the Dashboard, Team performance and SLA breaches.

---

## Testing

```bash
./gradlew test                               # backend: unit, integration, embedded Kafka (+ JaCoCo)
cd claims-web
npm test                                     # frontend unit and component tests (Vitest)
npm run coverage                             # with coverage
npm run e2e                                  # end-to-end, against the running application
```

| Suite | Tests | Result | Coverage |
|---|---|---|---|
| Backend (JUnit, MockMvc, embedded Kafka) | 64 | ✅ all passed | instructions 95.5% / 97.6% / 96.9% per service |
| Frontend unit and component (Vitest) | 91 | ✅ all passed | statements 87%, lines 90% |
| End-to-end (Playwright: workflows, accessibility, mobile) | 37 | ✅ all passed (dev server and Docker) | – |
| API collection (newman) | 51 requests, 93 assertions | ✅ 0 failures | – |

Test catalogue: [docs/testing.md](docs/testing.md) · Measured results: [docs/test-report.md](docs/test-report.md)

---

## API overview

| Role | Endpoints |
|---|---|
| Any signed-in user | `GET /api/me` |
| Claimant | `POST/GET /api/claims` · `GET /api/claims/{id}` · `…/history` · `…/info-requests` · `POST …/info-requests/{rid}/response` · `POST/GET …/documents` · `GET /api/notifications` |
| Officer | `GET /api/staff/claims/queue` · `GET /api/staff/claims` · `GET /api/staff/officers` · `POST /api/staff/claims/{id}/assign \| info-requests \| assess \| approve \| reject \| settle \| reassign` |
| Claimant, officer | `POST /api/agent/chat` (AI assistant; acts as the signed-in user; needs a Gemini API key) |
| Manager | `GET /api/reports/summary \| exposure \| exposure/total \| workload \| performance \| sla-breaches` · read-only staff views · reassign |

- **Errors** use RFC 7807 problem details: 400, 401, 403, 404, 409, 413, 415, 422, 500.
- **Contracts:** the OpenAPI files in each service's `src/main/resources/openapi/`.
- **Postman collection:** `docs/postman/`.

---

## Documentation

| Document | Contents |
|---|---|
| [Technology stack](docs/technology-stack.md) | Every frontend and backend technology, with versions and purpose |
| [Architecture](docs/architecture.md) | System design, frontend ↔ backend interaction, REST vs Kafka, security, reliability |
| [Project structure](docs/project-structure.md) | Folder tree and the responsibility of each component |
| [Developer guide](docs/developer-guide.md) | Setup, configuration, running, database, testing, deployment, troubleshooting |
| [Frontend](docs/frontend.md) | Frontend design, visual design system, feature traceability |
| [Testing](docs/testing.md) | Test strategy, catalogue, API payloads, error scenarios |
| [Test report](docs/test-report.md) | Measured results and coverage for every suite |
| [Decisions and assumptions](docs/decisions-and-assumptions.md) | Interpretation of the brief, decisions and alternatives, limitations |
| [Walkthrough](docs/walkthrough.md) | Presentation plan, the brief's questions answered, "why not X?", future work |
| [Approach and key prompts](docs/approach.md) | How the backend and frontend were built, challenges and resolutions, key prompts |

---

## Assessment and AI-assisted development

- **Brief:** [assessment-brief/V2_FullStack_candidate_assessment_brief.docx](assessment-brief/V2_FullStack_candidate_assessment_brief.docx),
  with the [repository guidelines](assessment-brief/take-home-assessment-repository-guidelines.md).
- **Approach:** the backend first, as the brief advises, then the Angular frontend. How each part was built, the
  challenges met, and the key prompts: [docs/approach.md](docs/approach.md).
- **AI:** built with **Claude Code**. The prompts are in [prompts/](prompts/), and the
  [AI journal](ai-journal/claude-journal.md) records what was accepted, challenged and overridden, and why.
