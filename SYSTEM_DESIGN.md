# System Design Document — Real-Time Soccer Dashboard

## Table of Contents
1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Tech Stack & Justifications](#tech-stack--justifications)
4. [Data Flow](#data-flow)
5. [Real-Time Architecture](#real-time-architecture)
6. [Streaming & Data Pipelines](#streaming--data-pipelines)
7. [Caching Strategy](#caching-strategy)
8. [Resilience Patterns](#resilience-patterns)
9. [Authentication](#authentication)
10. [AI/LLM Integration](#aillm-integration)
11. [System Design Panel (Star Feature)](#system-design-panel-star-feature)
12. [API Design](#api-design)
13. [Database Schema](#database-schema)
14. [Production Deployment (AWS)](#production-deployment-aws)
15. [Datadog Observability (Full-Stack)](#datadog-observability-full-stack)
16. [Special Features](#special-features)
17. [Challenges & Solutions](#challenges--solutions)
18. [Scalability Considerations](#scalability-considerations)
19. [Interview Q&A](#interview-qa) *(see [INTERVIEW_QA.md](INTERVIEW_QA.md))*

---

## Overview

A real-time soccer dashboard that streams live scores, standings, and fixtures from external APIs, pushes updates to connected clients via WebSocket, and visualizes the entire backend pipeline in a live **System Design Panel** via SSE. Integrates Claude AI for league analysis, news digests, and workflow narration. Deployed to production on AWS (ECS Fargate, RDS, ElastiCache, CloudFront).

**Key metrics:**
- 43 Java backend files, 23 frontend files
- 20+ workflow step types traced in real-time
- 30s polling interval (adaptive), 5min idle
- Sub-second cache reads, <500ms API responses
- 3 AI-powered features with token tracking
- Dual streaming protocols: SSE (panel) + WebSocket (live scores)
- Production-deployed on AWS with CDN, managed database, and container orchestration

---

## Architecture Diagram

```
                          ┌──────────────────────────┐
                          │       USERS (Browser)     │
                          └────────────┬─────────────┘
                                       │ HTTPS
                                       ▼
                    ┌──────────────────────────────────────┐
                    │     AWS CloudFront (CDN)              │
                    │     d3dj3wlvpn43fo.cloudfront.net     │
                    │                                      │
                    │  /* → S3 (React frontend)            │
                    │  /api/* → ALB (backend)              │
                    │  /ws/* → ALB (WebSocket upgrade)     │
                    └───────┬──────────────┬───────────────┘
                            │              │
               Static files │              │ /api/*, /ws/*
                            ▼              ▼
              ┌──────────────────┐  ┌─────────────────────────┐
              │  S3 Bucket       │  │  Application Load       │
              │  (React + Vite)  │  │  Balancer (ALB)         │
              │  Static hosting  │  │  idle timeout: 3600s    │
              └──────────────────┘  │  (SSE/WebSocket support)│
                                    └────────────┬────────────┘
                                                 │ port 8080
                                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                  AWS ECS Fargate (soccer-dashboard-cluster)                  │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              SPRING BOOT BACKEND (Docker, Java 21, 512 CPU/1GB)    │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                    CONTROLLER LAYER                         │   │   │
│  │  │  AuthController  LeagueController  LiveScoreController     │   │   │
│  │  │  SearchController  FavoriteController  NewsController      │   │   │
│  │  │  InsightController  WorkflowController  SystemController   │   │   │
│  │  └───────────────────────────┬─────────────────────────────────┘   │   │
│  │                              │                                     │   │
│  │  ┌───────────────────────────▼─────────────────────────────────┐   │   │
│  │  │                    SERVICE LAYER                             │   │   │
│  │  │                                                             │   │   │
│  │  │  ┌──────────────┐  ┌────────────────┐  ┌────────────────┐  │   │   │
│  │  │  │ FootballData  │  │ LiveScoreAggr- │  │ Polling        │  │   │   │
│  │  │  │ Service       │  │ egator (merge/ │  │ Scheduler      │  │   │   │
│  │  │  │ (API client)  │  │ failover)      │  │ (@Scheduled)   │  │   │   │
│  │  │  └──────┬────────┘  └───────┬────────┘  └───────┬────────┘  │   │   │
│  │  │         │                   │                    │           │   │   │
│  │  │  ┌──────▼────────┐  ┌──────▼─────────┐  ┌──────▼────────┐  │   │   │
│  │  │  │ CircuitBreaker│  │ DataDiffEngine │  │ WebSocket     │  │   │   │
│  │  │  │ (per-API)     │  │ (change detect)│  │ Broadcaster   │  │   │   │
│  │  │  └───────────────┘  └────────────────┘  └───────────────┘  │   │   │
│  │  │  ┌───────────────┐  ┌────────────────┐  ┌───────────────┐  │   │   │
│  │  │  │ RateLimiter   │  │ InsightService │  │ NarratorSvc   │  │   │   │
│  │  │  │ (10 req/min)  │  │ (Claude AI)    │  │ (AI explain)  │  │   │   │
│  │  │  └───────────────┘  └────────────────┘  └───────────────┘  │   │   │
│  │  │  ┌───────────────┐  ┌────────────────┐                     │   │   │
│  │  │  │ AuthService   │  │ NewsService    │                     │   │   │
│  │  │  │ (BCrypt+JWT)  │  │ (GNews+AI)     │                     │   │   │
│  │  │  └───────────────┘  └────────────────┘                     │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │                  WORKFLOW TRACING LAYER                     │   │   │
│  │  │  WorkflowTracer → WorkflowStep (20+) → WorkflowEmitter    │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────┬──────────────────────────────────┬──────────────────────────┘
              │                                  │
              ▼                                  ▼
┌──────────────────────────────┐   ┌──────────────────────────────┐
│  AWS ElastiCache Redis 7     │   │  AWS RDS MySQL 8             │
│  (cache.t3.micro)            │   │  (db.t3.micro)               │
│                              │   │                              │
│  standings:{code}  5min TTL  │   │  users (auth)                │
│  fixtures:{code}   1hr  TTL  │   │  favorite_teams              │
│  live:scores:all   30s  TTL  │   │                              │
│  insight:{code}    1hr  TTL  │   │  Encrypted at rest           │
│  news:soccer       15m  TTL  │   │  Auto-backup enabled         │
│  news:brief        30m  TTL  │   │                              │
│  teams:all         30m  TTL  │   │                              │
└──────────────────────────────┘   └──────────────────────────────┘

              │ (external APIs)
              ▼
┌──────────────────────────────────────────────────────────────┐
│                       EXTERNAL APIs                           │
│                                                              │
│  ┌──────────────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ Football-Data.org│  │ GNews    │  │ Claude API        │  │
│  │ 10 req/min free  │  │ 100/day  │  │ (Haiku 4.5)       │  │
│  │ 12 leagues       │  │ Soccer   │  │ Insights, Brief,  │  │
│  │ Standings,       │  │ news     │  │ Narrator          │  │
│  │ Fixtures, Live   │  │ articles │  │                   │  │
│  └──────────────────┘  └──────────┘  └───────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

---

## Tech Stack & Justifications

| Layer | Choice | Why this over alternatives |
|-------|--------|---------------------------|
| **Backend** | Java 21, Spring Boot 3.2 | Type safety for complex domain logic. Spring's ecosystem (Security, Data, WebSocket, SSE) avoids gluing libraries. Virtual threads in Java 21 for better concurrency. |
| **Frontend** | React 18 + Vite | Component model fits the many independent UI sections (standings, live scores, panel). Vite for sub-second HMR during development. |
| **Database** | MySQL 8 | Relational model for users and favorites (foreign keys, unique constraints). Free tier available on AWS RDS. |
| **Cache** | Redis 7 | Sub-millisecond reads for live score data. TTL-based expiry aligns with API polling intervals. Pub/sub capability for future horizontal scaling. |
| **Auth** | JWT (jjwt 0.12.5) + BCrypt | Stateless — no server-side session storage. Token in `Authorization` header works with both REST and WebSocket. BCrypt with strength 10 for password hashing. |
| **Real-time** | SSE + WebSocket (dual protocol) | **SSE** for System Design Panel (server→client only, auto-reconnect, works through proxies). **WebSocket** for live scores (bidirectional — clients send subscription messages). Using both demonstrates breadth. |
| **AI** | Claude Haiku 4.5 | Fast inference (~1-2s), low cost, sufficient quality for sports analysis. Cheaper than Sonnet/Opus for high-frequency summarization. |
| **Containerization** | Docker Compose (dev), ECS Fargate (prod) | Single `docker-compose up` for dev. Fargate for serverless container management in production — no EC2 instances to patch. |
| **CDN** | AWS CloudFront + S3 | Static React assets served from edge locations. CloudFront also proxies `/api/*` and `/ws/*` to the ALB, providing HTTPS termination. |
| **Load Balancer** | AWS ALB | HTTP/2, WebSocket upgrade support, health checks on backend. 3600s idle timeout for long-lived SSE/WebSocket connections. |
| **Managed DB** | AWS RDS MySQL (db.t3.micro) | Automated backups, encryption at rest, Multi-AZ option for HA. Free tier eligible. |
| **Managed Cache** | AWS ElastiCache Redis (cache.t3.micro) | Same Redis 7 as local dev, but managed — automatic failover, patching, monitoring via CloudWatch. |
| **Container Registry** | AWS ECR | Private Docker image registry in the same region as ECS. Eliminates Docker Hub rate limits. |
| **Logs** | AWS CloudWatch | Centralized log aggregation from ECS containers. Searchable, alertable. |
| **Observability** | Datadog (APM, RUM, Logs, Metrics) | Full-stack observability: distributed tracing, real user monitoring, structured logging with trace correlation, custom StatsD metrics. |
| **Metrics** | Micrometer + StatsD | Vendor-neutral metrics facade. StatsD registry pushes to Datadog Agent. Custom gauges for circuit breakers, rate limiters, WebSocket sessions. |
| **Structured Logging** | logstash-logback-encoder 7.4 | JSON log output in production with `dd.trace_id` and `dd.span_id` for log-to-trace correlation in Datadog. |

**Why not:**
- **Node.js backend?** — Java matches my resume and demonstrates strong typing, Spring ecosystem knowledge.
- **MongoDB?** — User/favorite data is relational. Redis handles the ephemeral cache layer.
- **GraphQL?** — REST is simpler for this use case. No deeply nested queries needed.
- **Kafka/RabbitMQ?** — Overkill for single-instance. WebSocket broadcaster handles fan-out directly. Documented as production upgrade path.
- **AWS Lambda?** — Stateful WebSocket/SSE connections need persistent processes. Fargate provides always-on containers without Lambda's cold start and 15min execution limit.
- **App Runner?** — Simpler but less control over networking (VPC, security groups). ECS Fargate allows fine-grained security group rules between ALB, backend, RDS, and Redis.

---

## Data Flow

### 1. Polling Cycle (Background — every 30s)

```
PollingScheduler.pollLiveScores()
  │
  ├─ Check: Any WS/SSE clients connected?
  │   └─ No → Skip cycle, emit POLL_CYCLE_END "skipped"
  │
  ├─ Check: API quota remaining?
  │   └─ 0/10 → Skip, emit RATE_LIMIT warning
  │
  ├─ Fetch from Football-Data.org
  │   ├─ Rate limiter: tryAcquire()
  │   ├─ Circuit breaker: execute()
  │   └─ HTTP GET /v4/matches (live scores)
  │
  ├─ DataDiffEngine.diff(cached, fresh)
  │   └─ Returns: scoreChanges, newEvents, statusChanges
  │
  ├─ Cache write: Redis SET live:scores:all (TTL 30s)
  │
  └─ WebSocketBroadcaster.broadcast(diff)
      └─ Send changes only to clients subscribed to affected leagues
```

### 2. User Request (e.g., GET /api/leagues/PL/standings)

```
Request
  │
  ├─ WorkflowTracer: start USER_ACTION trace
  ├─ Emit: API_GATEWAY step
  │
  ├─ Redis: GET standings:PL
  │   ├─ HIT → Emit CACHE_CHECK(HIT), return cached data
  │   └─ MISS → continue
  │
  ├─ RateLimiter: tryAcquire()
  │   └─ Denied → return stale cache or empty
  │
  ├─ CircuitBreaker: execute()
  │   ├─ OPEN → return fallback
  │   └─ CLOSED → HTTP GET football-data.org
  │
  ├─ Redis: SET standings:PL (TTL 5min)
  ├─ Emit: CACHE_WRITE, RESPONSE steps
  └─ Return JSON
```

### 3. AI Insight Request (GET /api/insights/PL)

```
Request
  │
  ├─ Fetch standings (reuses cache path above)
  │
  ├─ Redis: GET insight:PL
  │   ├─ HIT → Emit LLM_RESULT(cached) with preview, return
  │   └─ MISS → continue
  │
  ├─ Build prompt: standings table → analyst instruction
  ├─ Emit: PROMPT_BUILD step (team count, topics)
  │
  ├─ Claude API: POST /v1/messages (Haiku 4.5, max 500 tokens)
  ├─ Emit: LLM_INFERENCE (input/output tokens, duration)
  │
  ├─ Redis: SET insight:PL (TTL 1hr)
  └─ Return analysis text
```

---

## Real-Time Architecture

### Why Two Protocols?

| | SSE (System Design Panel) | WebSocket (Live Scores) |
|---|---|---|
| **Direction** | Server → Client only | Bidirectional |
| **Use case** | Append-only event stream | Subscribe/unsubscribe to leagues |
| **Reconnect** | Built-in (`EventSource` auto-reconnects) | Manual (exponential backoff) |
| **Proxy support** | Works through HTTP/1.1 proxies | Requires upgrade support |
| **Client messages** | Not needed | `{"action":"subscribe","leagues":["PL"]}` |

### Adaptive Polling

The `PollingScheduler` adjusts its behavior based on system state:

| Condition | Interval | Reason |
|-----------|----------|--------|
| Live matches + clients connected | 30s | Active viewing, scores changing |
| No live matches + clients connected | 5min | Check for newly started matches |
| No clients connected | Skip entirely | Save API quota |
| Demo mode active | 30s | Use synthetic data, no API calls |

### WebSocket Subscription Model

```
Client connects → session tracked in WebSocketBroadcaster
Client sends: {"action": "subscribe", "leagues": ["PL", "PD"]}
On data diff: only send changes to clients subscribed to affected leagues
Client disconnects → session removed, subscription cleared
```

This avoids broadcasting all data to all clients. A client watching only La Liga doesn't receive Premier League updates.

---

## Streaming & Data Pipelines

This project handles **three distinct streams of data**, each with different latency requirements and delivery mechanisms.

### Stream 1: Live Score Push (WebSocket — continuous)

```
Football-Data.org ──(HTTP poll 30s)──→ PollingScheduler
                                           │
                                    DataDiffEngine
                                    (compare cached vs fresh)
                                           │
                                    Only changes extracted
                                    (score, status, events)
                                           │
                                    WebSocketBroadcaster
                                    (fan-out to subscribed clients)
                                           │
                               ┌───────────┼───────────┐
                               ▼           ▼           ▼
                          Client A     Client B     Client C
                          (PL sub)     (PL,PD)      (SA sub)
```

**Flow:** External API → Backend poll → Diff → Selective push to subscribed clients. This is a **server-initiated streaming pipeline** — clients don't request updates, they receive them as data changes. The diff engine ensures only deltas are sent, minimizing bandwidth. Clients subscribed to different leagues only receive relevant updates.

**Backpressure:** If the WebSocket send buffer fills (slow client), the message is dropped for that client — live scores are ephemeral and the next cycle will send the latest state.

### Stream 2: System Design Panel (SSE — continuous)

```
Any backend operation
  │
  WorkflowTracer.emit(step)
  │
  WorkflowEmitter (CopyOnWriteArrayList<SseEmitter>)
  │
  Broadcast to ALL connected SSE clients
  │
  ┌──────────┐
  ▼          ▼
Browser 1   Browser 2
(System     (System
 Design      Design
 Panel)      Panel)
```

**Flow:** Every backend operation (API calls, cache reads, auth checks, LLM inference) emits a `WorkflowStep` event into a server-sent event stream. This is an **append-only event log** — events are never updated or deleted, only new events arrive. The frontend groups them by `traceId` and renders them as collapsible traces.

**Why this is a stream, not request/response:** The panel shows operations from ALL users and background tasks in real-time. A user watching the panel sees their own requests AND polling cycles AND other users' requests — it's a multiplexed, unbounded event stream.

### Stream 3: AI Analysis Pipeline (Request-triggered, cached)

```
User request → Check cache → [HIT] → Return cached
                    │
                  [MISS]
                    │
              Fetch standings data
                    │
              Build prompt (standings → text)
                    │
              Claude API (streaming-capable)
                    │
              Cache result (1hr TTL)
                    │
              Return to client
```

**Flow:** Unlike Streams 1 and 2 which are continuous, this is a **request-triggered pipeline with aggressive caching**. The AI insight for a given league is computed once and served to all subsequent requesters for 1 hour. This converts an expensive LLM call into an amortized cost shared across all users.

### How Streams Interact

The three streams are **independent but observable**. Stream 2 (System Design Panel) acts as a **meta-stream** that traces operations from Streams 1 and 3:

- When the polling scheduler runs (Stream 1), it emits `POLL_CYCLE_START`, `EXTERNAL_API`, `DATA_DIFF`, `WEBSOCKET_FANOUT` steps into Stream 2
- When a user requests AI analysis (Stream 3), it emits `CACHE_CHECK`, `PROMPT_BUILD`, `LLM_INFERENCE` steps into Stream 2
- Stream 2 is the unified observability layer for the entire system

### Data Volume Characteristics

| Stream | Frequency | Payload Size | Delivery | Persistence |
|--------|-----------|-------------|----------|-------------|
| Live Scores (WS) | Every 30s during live matches | ~2-5 KB (diff only) | Push to subscribed clients | Redis 30s TTL |
| Panel Events (SSE) | Per-operation (~5-20 events/request) | ~200-500 bytes each | Broadcast to all viewers | In-memory only |
| AI Analysis | On-demand, cached 1hr | ~2-3 KB (analysis text) | Request/response | Redis 1hr TTL |

---

## Caching Strategy

### Cache Keys & TTLs

| Key Pattern | TTL | Reason |
|---|---|---|
| `standings:{code}` | 5 min | Standings change infrequently during matches |
| `fixtures:{code}` | 1 hr | Fixture schedules rarely change |
| `live:scores:all` | 30s | Matches live data from polling interval |
| `insight:{code}` | 1 hr | AI analysis doesn't need real-time refresh |
| `news:soccer` | 15 min | News articles update moderately |
| `news:brief` | 30 min | AI digest regenerated less frequently |
| `teams:all` | 30 min | Team rosters rarely change |

### Cache-Aside Pattern

Every data access follows the same pattern:
1. Check Redis (CACHE_CHECK step emitted)
2. On HIT: return cached data, skip API call
3. On MISS: call external API, write to cache, return
4. On cache write failure: log warning, still return data (cache is optional, not critical)

### Why not write-through or write-behind?

- **Write-through** would couple every API response to a cache write, adding latency to the response path.
- **Write-behind** requires a queue and adds complexity for data that has natural TTLs anyway.
- **Cache-aside** is simpler and fits our pattern: reads are frequent, writes happen on cache miss or polling cycles.

---

## Resilience Patterns

### Circuit Breaker (per-API)

```
State Machine:
  CLOSED ──(3 consecutive failures)──→ OPEN
  OPEN ──(60s timeout)──→ HALF_OPEN
  HALF_OPEN ──(1 success)──→ CLOSED
  HALF_OPEN ──(1 failure)──→ OPEN
```

**Why per-API?** Football-Data.org going down shouldn't prevent serving cached data or demo mode. Each external dependency has its own circuit breaker.

**Fallback behavior:** When OPEN, the circuit breaker invokes a fallback supplier that returns empty/cached data. The UI shows "Data temporarily unavailable" rather than an error page.

### Rate Limiter (Sliding Window)

- **Football-Data.org:** 10 requests per 60-second window
- Implementation: tracks request timestamps in a list, removes expired entries on each check
- `tryAcquire()` returns boolean — never blocks or throws
- Remaining quota exposed via `/api/system/status` and shown in System Design Panel

### Key Design Decision: Rate Limiter OUTSIDE Circuit Breaker

```java
// WRONG — rate limit denial counts as circuit breaker failure
circuitBreaker.execute(() -> {
    rateLimiter.tryAcquire();  // throws → CB failure count++
    return callApi();
});

// CORRECT — rate limit checked independently
if (!rateLimiter.tryAcquire()) {
    return emptyResult();  // CB not affected
}
return circuitBreaker.execute(() -> callApi());
```

This was a real bug we discovered and fixed (see Challenges #1).

---

## Authentication

### Flow

```
Signup: POST /api/auth/signup
  → Validate email/username uniqueness
  → BCrypt hash password (strength 10)
  → Save to MySQL
  → Generate JWT (24hr expiry)
  → Return token + user info

Login: POST /api/auth/login
  → Find user by email
  → BCrypt verify password
  → Generate JWT
  → Return token + user info

Protected routes (e.g., /api/favorites):
  → JwtAuthenticationFilter extracts Bearer token
  → Validate signature + expiry
  → Set SecurityContext
  → Controller accesses authenticated user
```

### Why JWT over sessions?

- **Stateless:** No server-side session store needed. Scales horizontally without sticky sessions.
- **Works with WebSocket:** Token sent in initial HTTP upgrade request.
- **Frontend simplicity:** Store in `localStorage`, attach via Axios interceptor.

### Security Considerations

- JWT secret: 256-bit key (HS256 minimum requirement)
- Password hashing: BCrypt with strength 10 (~100ms per hash — prevents brute force)
- CORS: Configured per environment (dev: localhost:5173, prod: CloudFront domain)
- Public routes: `/api/auth/**`, `/api/leagues/**`, `/api/live/**`, `/api/search/**`, `/api/workflow/**`, `/api/news/**`
- Protected routes: `/api/favorites/**`

---

## AI/LLM Integration

### Three AI Features

| Feature | Endpoint | Model | Max Tokens | Cache TTL | Trigger |
|---------|----------|-------|------------|-----------|---------|
| **League Insight** | GET /api/insights/{code} | Haiku 4.5 | 500 | 1 hr | User views league |
| **News Brief** | GET /api/news/brief | Haiku 4.5 | 300 | 30 min | User opens News tab |
| **Panel Narrator** | POST /api/workflow/narrate | Haiku 4.5 | 150 | 2 min (in-memory) | User clicks "AI Explain" |

### Cost Control

- **Caching:** Every AI result is cached. Repeated requests for the same league/news don't hit the API.
- **Haiku model:** ~10x cheaper than Opus, sufficient for summarization tasks.
- **Token limits:** Max tokens capped per feature (150-500) to control output cost.
- **Narrator cooldown:** 2-minute in-memory cooldown prevents rapid re-requests.
- **User-triggered:** Narrator only fires on explicit user action, not automatically.

### Prompt Engineering

**League Insight prompt structure:**
```
System role: "football/soccer analyst"
Task: "Analyze current {league} standings, 3-4 paragraphs"
Topics: Title race, relegation, surprises, key storylines
Tone: "Concise, punchy, confident analyst tone"
Context: Full standings table (Pos, Team, P, W, D, L, GF, GA, GD, Pts)
```

**News Brief prompt structure:**
```
System role: "football journalist writing daily briefing"
Task: "Summarize {n} articles into Today's Soccer Brief"
Format: "3-4 punchy sentences, flowing paragraph"
Context: Article titles and descriptions
```

### Observability

Every LLM call emits workflow steps visible in the System Design Panel:
- **PROMPT_BUILD:** Shows team count, topics, data fed to the model
- **LLM_INFERENCE:** Shows model name, input/output tokens, latency, response preview
- **LLM_RESULT (cached):** Shows model, generation time, and preview without misleading token counts

---

## System Design Panel (Star Feature)

### What It Shows

The panel visualizes every operation the backend performs in real-time:

| Step Type | Icon | Color | Example |
|-----------|------|-------|---------|
| API_GATEWAY | Door | Blue | `GET /api/leagues/PL/standings` |
| CACHE_CHECK | Floppy | Amber | `Redis LOOKUP standings:PL → HIT` |
| CACHE_WRITE | Floppy | Amber | `Persisting to Redis (TTL: 5min)` |
| EXTERNAL_API | Globe | Blue | `Football-Data.org, 200, 340ms` |
| CIRCUIT_BREAKER | Lightning | Red | `State: OPEN (failures: 3/3)` |
| RATE_LIMIT | Timer | Orange | `Quota: 7/10 remaining` |
| POLL_CYCLE_START | Refresh | Indigo | `Polling cycle #47 started` |
| POLL_CYCLE_END | Refresh | Indigo | `2 APIs polled, 8 matches updated` |
| DATA_DIFF | Search | Cyan | `2 score changes, 1 new event` |
| WEBSOCKET_FANOUT | Satellite | Purple | `Broadcasting to 14 clients` |
| AUTH_CHECK | Lock | Grey | `JWT validated for user sarthak` |
| DB_WRITE | Floppy | Brown | `INSERT favorite_teams` |
| LLM_INFERENCE | Robot | Purple | `Haiku 4.5 — 847 in, 312 out tokens` |
| ERROR | X | Red | `Football-Data.org: 429 Too Many Requests` |

### Three Trace Types

1. **Poll Cycles (blue):** Background scheduler activity, collapsible since they repeat every 30s
2. **User Actions (green):** Search, login, view standings, request AI insight
3. **System Events (orange):** Circuit breaker transitions, demo mode toggle, narrator

### Implementation

```
WorkflowTracer.startUserAction("View standings: PL")
  → creates Trace with unique ID (e.g., "user-7d016768")
  → emits TRACE_START via SSE

trace.emitApiGateway("GET /api/leagues/PL/standings")
  → creates WorkflowStep with type, detail, metadata
  → WorkflowEmitter broadcasts to all SSE clients

Frontend useWorkflowStream hook:
  → EventSource connects to /api/workflow/stream
  → Groups events by traceId
  → Renders in WorkflowPanel with colors, icons, badges
```

### Why SSE, not WebSocket, for the Panel?

- The panel is **server→client only** (no client messages needed)
- SSE auto-reconnects on disconnect (EventSource built-in behavior)
- Works through HTTP/1.1 proxies without upgrade negotiation
- Simpler server-side (just write to `SseEmitter`, no session management)

---

## API Design

### REST Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/auth/signup | No | Create account, returns JWT |
| POST | /api/auth/login | No | Authenticate, returns JWT |
| GET | /api/leagues | No | List available leagues |
| GET | /api/leagues/{code}/standings | No | League table |
| GET | /api/leagues/{code}/fixtures | No | Upcoming/recent matches |
| GET | /api/live/today | No | Today's matches |
| GET | /api/search?q={query} | No | Search teams/leagues |
| GET | /api/favorites | JWT | User's favorite teams |
| POST | /api/favorites | JWT | Add favorite |
| DELETE | /api/favorites/{teamId} | JWT | Remove favorite |
| GET | /api/news | No | Soccer news articles |
| GET | /api/news/brief | No | AI news digest |
| GET | /api/insights/{code} | No | AI league analysis |
| GET | /api/workflow/stream | No | SSE event stream |
| POST | /api/workflow/narrate | No | AI panel explanation |
| GET | /api/system/status | No | System health + quotas |
| POST | /api/system/reset-circuits | No | Reset circuit breakers |
| POST | /api/demo/toggle | No | Toggle demo mode |

### WebSocket

| Direction | Message | Purpose |
|-----------|---------|---------|
| Client→Server | `{"action":"subscribe","leagues":["PL"]}` | Subscribe to league updates |
| Client→Server | `{"action":"unsubscribe","leagues":["PL"]}` | Unsubscribe |
| Server→Client | `{"type":"SCORE_UPDATE","matches":[...]}` | Live score push |

---

## Database Schema

```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP NULL
);

CREATE TABLE favorite_teams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    team_id INT NOT NULL,
    team_name VARCHAR(255) NOT NULL,
    league_id INT,
    league_name VARCHAR(255),
    team_logo_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY unique_user_team (user_id, team_id)
);
```

**Why MySQL and not just Redis for everything?**
- User data needs ACID guarantees (passwords, unique constraints)
- `ON DELETE CASCADE` for cleanup when users are deleted
- `UNIQUE KEY` on (user_id, team_id) prevents duplicate favorites at DB level
- Redis is for ephemeral cache — if it's flushed, the app still works (just slower)

---

## Production Deployment (AWS)

### Architecture

```
Internet
  │
  ▼
CloudFront (HTTPS termination, CDN)
  ├── /* ──────→ S3 Bucket (React SPA, gzip compressed)
  ├── /api/* ──→ ALB ──→ ECS Fargate (Spring Boot container)
  └── /ws/* ───→ ALB ──→ ECS Fargate (WebSocket upgrade)
                           │                  │
                           ▼                  ▼
                    ElastiCache Redis    RDS MySQL
                    (cache.t3.micro)     (db.t3.micro)
```

### AWS Services Used

| Service | Configuration | Purpose |
|---------|--------------|---------|
| **ECS Fargate** | 0.5 vCPU, 1 GB RAM, 1 task | Runs backend Docker container. Serverless — no EC2 management. Auto-restarts unhealthy tasks. |
| **ECR** | Private repository | Stores Docker images. Integrated with ECS for pull-on-deploy. |
| **ALB** | Internet-facing, 3 AZs | Routes traffic to Fargate tasks. Idle timeout 3600s for SSE/WebSocket. Health checks on `/api/leagues`. |
| **RDS MySQL** | db.t3.micro, single AZ | Managed MySQL 8. Automated backups, encryption at rest. Stores users and favorites. |
| **ElastiCache** | cache.t3.micro, Redis 7.1 | Managed Redis. Same behavior as local dev. Handles all caching. |
| **S3** | Static website hosting | Serves React frontend build artifacts. |
| **CloudFront** | PriceClass_100 (US/EU) | HTTPS, CDN caching for static assets. Proxies API/WS requests to ALB. Custom error page (404 → index.html for SPA routing). |
| **CloudWatch** | Log group `/ecs/soccer-dashboard-backend` | Container log aggregation. Searchable for debugging production issues. |

### Network Security (Security Groups)

```
Internet ──(80,443)──→ ALB SG
ALB SG ──(8080)──→ Fargate SG
Fargate SG ──(3306)──→ RDS SG
Fargate SG ──(6379)──→ ElastiCache SG (shared with RDS SG)
```

Three security groups enforce least-privilege:
- **ALB SG:** Only accepts HTTP/HTTPS from the internet
- **Fargate SG:** Only accepts traffic from the ALB on port 8080
- **RDS/Redis SG:** Only accepts connections from Fargate tasks

No service is directly exposed to the internet except the ALB. RDS and ElastiCache have no public IPs.

### Docker Multi-Stage Build

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN curl -Lo /app/dd-java-agent.jar https://dtdg.co/latest-java-tracer && chmod 644 /app/dd-java-agent.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/dd-java-agent.jar", "-Dspring.profiles.active=prod", "-Xmx768m", "-jar", "app.jar"]
```

**Stage 1 (build):** Full JDK + Maven to compile. ~800MB.
**Stage 2 (runtime):** JRE only + JAR + dd-java-agent. ~160MB. The Datadog Java agent is downloaded at build time and attached as a `-javaagent` JVM argument for zero-code APM instrumentation.

### Environment Configuration

Production uses `application-prod.yml` with all secrets injected via environment variables in the ECS task definition:

| Variable | Source | Purpose |
|----------|--------|---------|
| `DB_URL` | RDS endpoint | MySQL connection string |
| `DB_USERNAME` / `DB_PASSWORD` | ECS env | Database credentials |
| `REDIS_HOST` | ElastiCache endpoint | Redis connection |
| `JWT_SECRET` | ECS env | Token signing key |
| `FOOTBALL_DATA_API_KEY` | ECS env | External API auth |
| `GNEWS_API_KEY` | ECS env | News API auth |
| `CLAUDE_API_KEY` | ECS env | AI/LLM auth |
| `CORS_ORIGINS` | ECS env (`*`) | Allowed CORS origins |
| `DD_AGENT_HOST` | ECS env (`localhost`) | Datadog agent address (localhost for Fargate sidecar) |
| `DD_SERVICE` | ECS env | Service name for APM/logs (`soccer-dashboard-backend`) |
| `DD_ENV` | ECS env | Environment tag (`prod`) |
| `DD_VERSION` | ECS env | Version tag (`1.0.0`) |
| `DD_LOGS_INJECTION` | ECS env | Inject trace_id/span_id into logs (`true`) |
| `DD_API_KEY` | ECS env (agent container) | Datadog API key for agent authentication |
| `DD_SITE` | ECS env (agent container) | Datadog site (`us5.datadoghq.com`) |

No secrets in code or Docker images. All configuration is environment-driven.

### Deployment Process

```
1. docker build -t soccer-dashboard-backend .
2. docker tag → 123442381711.dkr.ecr.us-east-2.amazonaws.com/soccer-dashboard-backend:latest
3. docker push → ECR
4. aws ecs update-service --force-new-deployment → rolling update
5. npm run build → frontend static files
6. aws s3 sync dist/ s3://bucket/ → upload to S3
7. aws cloudfront create-invalidation → purge CDN cache
```

ECS performs **rolling deployments**: starts new task, waits for health check to pass, drains old task. Zero-downtime deploys.

### Cost Estimate

| Service | Monthly Cost |
|---------|-------------|
| ECS Fargate (0.5 vCPU, 1GB, always-on) | ~$15 |
| RDS MySQL (db.t3.micro) | Free tier / ~$15 |
| ElastiCache (cache.t3.micro) | ~$12 |
| ALB | ~$16 + data transfer |
| S3 + CloudFront | ~$1-2 |
| ECR | ~$1 |
| **Total** | **~$45-60/month** |

### CloudFront + WebSocket: Mixed Content Fix

CloudFront serves the frontend over HTTPS. WebSocket must use `wss://` (secure) when loaded from an HTTPS page — browsers block `ws://` connections from HTTPS origins (mixed content). Solution: route `/ws/*` through CloudFront to the ALB, so WebSocket uses the same `wss://cloudfront-domain/ws/live` endpoint. CloudFront natively supports WebSocket upgrade when forwarding all headers.

---

## Special Features

### 1. System Design Panel (Star Feature)

A live visualization of every backend operation. Every API call, cache read, rate limit check, circuit breaker decision, LLM inference, and WebSocket broadcast is traced and streamed to the browser in real-time via SSE. The panel groups operations by request (traceId), color-codes them by type, and shows timing information. This transforms an opaque backend into a transparent, observable system — ideal for demonstrating system design knowledge in interviews.

### 2. Dual Streaming Protocols

The project uses **both** SSE and WebSocket, each chosen for its strengths:
- **SSE** for the System Design Panel: server→client only, auto-reconnect, simpler
- **WebSocket** for live scores: bidirectional (clients subscribe to specific leagues)

Using two protocols in one project demonstrates understanding of when to use each, rather than defaulting to one.

### 3. Three AI-Powered Features

All powered by Claude Haiku 4.5 with cost-controlled caching:
- **League Insight:** AI analyzes current standings and generates analysis (title race, relegation battle, surprises)
- **News Brief ("Today's Soccer Brief"):** AI digests multiple news articles into a concise daily briefing
- **Panel Narrator:** AI watches System Design Panel events and explains what's happening in plain English

Each feature is fully traced in the System Design Panel, showing prompt construction, token usage, and caching behavior.

### 4. Adaptive Polling with Smart Quota Management

The polling scheduler adapts its behavior based on system state:
- Polls every 30s during live matches with connected clients
- Drops to 5min intervals when no live matches
- Skips entirely when no clients are connected
- Rate limiter tracks API quota independently of circuit breaker state

### 5. Demo Mode

A toggle that replays recorded match data with simulated live updates. Ensures the real-time experience works even when no live matches are playing — critical for interviews and demonstrations.

### 6. Full Observability Pipeline

Every backend operation flows through the workflow tracing layer:
```
Operation → WorkflowTracer → WorkflowStep → WorkflowEmitter → SSE → Browser Panel
```
This means any new feature automatically becomes visible in the System Design Panel by adding trace calls. The AI Narrator can explain any operation because it receives the same event stream.

---

## Challenges & Solutions

### 1. Rate Limiter Tripping Circuit Breakers

**Problem:** Rate limit checks were inside `circuitBreaker.execute()`. When the rate limit was hit, the thrown exception counted as a circuit breaker failure. With the poller consuming quota, user requests would get rate-limited → CB failure count incremented → CB tripped OPEN → all requests failed.

**Root cause:** Conflating "I chose not to call the API" with "the API failed."

**Fix:** Moved `rateLimiter.tryAcquire()` outside the circuit breaker block. Rate-limited calls return empty results gracefully without affecting CB state.

**Lesson:** Keep resilience patterns independent. Rate limiting is a client-side policy decision; circuit breaking is a failure detection mechanism.

### 2. Stale Empty Cache Poisoning

**Problem:** Before the API key was configured, all API calls returned `{}`. These empty responses were cached with long TTLs (1hr for fixtures, 5min for standings). After adding the API key, Redis still served stale `{}`.

**Fix:**
- Added guards: only cache responses that contain actual data (`standings.has("standings")`)
- Flushed Redis after the fix

**Lesson:** Never cache error/empty responses with production TTLs. Validate before caching.

### 3. Double-Serialized JSON

**Problem:** `LeagueController` called `.toString()` on a Jackson `JsonNode` before returning it. Spring then serialized the already-serialized string, producing `"{\"standings\":...}"` (a JSON string containing JSON) instead of `{"standings":...}` (a JSON object).

**Fix:** Return `JsonNode` directly from the controller. Let Spring's `MappingJackson2HttpMessageConverter` handle serialization once.

**Lesson:** In Spring, return objects/nodes — don't pre-serialize.

### 4. Cache Deserialization Type Mismatch

**Problem:** `LiveScoreAggregator` cached data as `standings.toString()` (stored as String in Redis) but on cache hit used `objectMapper.valueToTree(cached)` which expected a Java object, not a JSON string.

**Fix:** Added type check: `if (cached instanceof String) objectMapper.readTree((String) cached)` with fallback to `valueToTree`.

**Lesson:** Be explicit about serialization format at cache boundaries. Consider using a typed `RedisTemplate<String, String>` to make the contract clear.

### 5. GNews URL Double-Encoding

**Problem:** `RestTemplate.getForEntity(urlString, ...)` internally creates a URI and encodes it. The URL already contained `%20` (from manual encoding), which got re-encoded to `%2520`.

**Fix:** Use `URI.create(url)` to create a pre-encoded URI, then pass that to RestTemplate.

**Lesson:** Know whether your HTTP client encodes URLs automatically. Pass `URI` objects to avoid double-encoding.

### 6. Search Returning Wrong Teams

**Problem:** The Football-Data.org `/teams?limit=50` endpoint returned random teams from obscure leagues. Users searching "Arsenal" wouldn't find them.

**Fix:** Rewrote `SearchService` to fetch teams per-competition (`/competitions/PL/teams`, `/competitions/PD/teams`, etc.), cache the combined list, and filter client-side by name/shortName/TLA.

**Lesson:** Understand the API's data model. Generic endpoints may not return the data users expect.

### 7. Circuit Breaker Stuck OPEN

**Problem:** Once the CB tripped OPEN during development (due to bug #1), there was no way to recover without restarting the server.

**Fix:** Added `reset()` method to `CircuitBreaker` and `POST /api/system/reset-circuits` endpoint.

**Lesson:** Always provide operational escape hatches for stateful components. In production, this would be a protected admin endpoint.

### 8. CORS Wildcard + Credentials Conflict (Production)

**Problem:** Production backend used `CORS_ORIGINS=*` with `allowCredentials(true)`. Spring Security throws `IllegalArgumentException: When allowCredentials is true, allowedOrigins cannot contain the special value "*"`. Health checks returned 500, ECS tasks marked unhealthy.

**Root cause:** The CORS spec forbids `Access-Control-Allow-Origin: *` with `Access-Control-Allow-Credentials: true` because it would allow any site to make authenticated requests.

**Fix:** When `CORS_ORIGINS=*`, use `allowedOriginPatterns("*")` instead of `allowedOrigins("*")`. Pattern matching allows wildcard with credentials because the response header echoes back the specific requesting origin, not `*`.

```java
if ("*".equals(allowedOrigins.trim())) {
    mapping.allowedOriginPatterns("*");
} else {
    mapping.allowedOrigins(allowedOrigins.split(","));
}
```

**Lesson:** Test CORS configuration in production-like settings. Dev environments often don't trigger this because they use explicit origins (`http://localhost:5173`).

### 9. Mixed Content — WebSocket Over HTTPS

**Problem:** CloudFront serves the frontend over HTTPS. The frontend tried to connect to `ws://alb-host/ws/live` (insecure WebSocket). Browsers block mixed content — `ws://` from an `https://` page throws `SecurityError`.

**Root cause:** Frontend used `window.location.hostname:8080` as WebSocket host, bypassing CloudFront.

**Fix:** Two changes:
1. Added `/ws/*` cache behavior in CloudFront routing to the ALB origin (CloudFront natively supports WebSocket upgrade)
2. Changed frontend from `hostname:8080` to `window.location.host` so WebSocket uses the same CloudFront origin: `wss://d3dj3wlvpn43fo.cloudfront.net/ws/live`

**Lesson:** When fronting a backend with HTTPS (CloudFront, nginx), ALL protocols must route through the HTTPS proxy — REST, SSE, and WebSocket.

### 10. Git Bash Path Mangling on Windows (DevOps)

**Problem:** AWS CLI commands with Unix-style paths like `--log-group-name /ecs/soccer-dashboard-backend` were converted by Git Bash (MSYS2) to `C:/Program Files/Git/ecs/soccer-dashboard-backend`. CloudWatch, health check paths, and other AWS resources couldn't be created.

**Root cause:** MSYS2 (Git Bash's underlying layer) auto-converts any argument starting with `/` to a Windows path, thinking it's a Unix path reference.

**Fix:** Prefix commands with `MSYS_NO_PATHCONV=1` to disable path conversion:
```bash
MSYS_NO_PATHCONV=1 aws logs create-log-group --log-group-name /ecs/soccer-dashboard-backend
```

**Lesson:** Git Bash on Windows introduces subtle compatibility issues with tools that use `/` in arguments. For production deployments, use WSL, PowerShell, or CI/CD pipelines to avoid this class of issues.

### 11. ALB Idle Timeout for Long-Lived Connections

**Problem:** Default ALB idle timeout is 60 seconds. SSE connections (System Design Panel) and WebSocket connections (live scores) stay open for the entire browser session — minutes to hours.

**Fix:** Set ALB `idle_timeout.timeout_seconds` to 3600 (1 hour). Frontend hooks have auto-reconnect (SSE: built-in `EventSource`, WebSocket: exponential backoff) as a safety net.

**Lesson:** Long-lived HTTP connections (SSE, WebSocket, long-polling) require load balancer timeout configuration. The default 60s is designed for request/response patterns.

### 12. Correlating Frontend Latency with Backend Traces

**Problem:** Users reported "slow page loads" but backend APM showed fast responses. No way to see what the browser was actually experiencing — network latency, JS execution time, and resource loading were invisible.

**Fix:** Added Datadog RUM (`@datadog/browser-rum`) with `allowedTracingUrls: [/\/api\//]`. RUM injects `x-datadog-trace-id` headers into API calls, connecting browser sessions to backend APM traces. Now a slow user experience can be traced from the browser → through CloudFront → to the exact backend span that caused the delay.

**Lesson:** Backend APM alone gives you half the picture. RUM with trace linking provides the full request lifecycle from user click to database query.

### 13. Monitoring Circuit Breaker State Drift Silently

**Problem:** Circuit breakers would silently transition to OPEN state during off-peak hours. The System Design Panel only shows state when someone is watching it. No historical record or alerting.

**Fix:** Registered Micrometer gauges for circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN) and failure counts via `DatadogMetricsConfig`. These are pushed to Datadog via StatsD every flush interval. A Datadog monitor can alert when `circuit_breaker.state > 0` for more than 2 minutes.

**Lesson:** Resilience patterns need observability too. A circuit breaker that silently fails is worse than no circuit breaker — it masks the problem.

### 14. LLM Cost Visibility Across 3 AI Services

**Problem:** Three services call Claude API (InsightService, NewsService, NarratorService) but there was no aggregate view of token consumption. Cost surprises are possible if caching fails or a service loops.

**Fix:** Added Micrometer counters (`llm.tokens`) tagged by `direction` (input/output), `model`, and `operation`. Each service increments counters after every API call. A Datadog dashboard can show daily token burn rate per operation, and alerts can fire if hourly token usage exceeds a threshold.

**Lesson:** When using paid APIs (especially LLMs), instrument cost metrics from day one. Cache hit rates and token counters together tell you exactly how much each feature costs to operate.

### 15. Datadog Agent Not Receiving Traces in ECS Fargate

**Problem:** After deploying the Datadog agent as a sidecar container in ECS Fargate, the Datadog portal showed the containers in Infrastructure → Containers but APM → Services was empty. No traces were being received despite the backend having `dd-java-agent.jar` configured.

**Root cause:** Three issues compounding:
1. **Wrong `DD_SITE`:** The Datadog account was on `us5.datadoghq.com` but the agent defaulted to `datadoghq.com` (US1). All data was being sent to the wrong Datadog site.
2. **`DD_AGENT_HOST` set to `datadog-agent` instead of `localhost`:** In Docker Compose, the agent is a separate service reachable by container name. In ECS Fargate, sidecar containers share the same network namespace — the agent is at `localhost`.
3. **Dockerfile `-javaagent` issues:** The `ADD` instruction for downloading `dd-java-agent.jar` could silently fail. JVM system properties (`-Dspring.profiles.active=prod`) were placed after `-jar`, where they're treated as application arguments, not JVM flags.

**Fix:**
1. Added `DD_SITE=us5.datadoghq.com` to the datadog-agent container environment
2. Changed `DD_AGENT_HOST` from `datadog-agent` to `localhost` on the backend container
3. Replaced `ADD https://dtdg.co/latest-java-tracer` with `RUN curl -Lo dd-java-agent.jar` for reliable download
4. Reordered Dockerfile ENTRYPOINT: `-javaagent` and `-D` flags before `-jar`

**Lesson:** When moving from Docker Compose to ECS Fargate, networking changes fundamentally. Services reachable by container name in Compose become `localhost` in Fargate sidecars. Always verify the Datadog site matches your account region. And in Dockerfiles, use `RUN curl` over `ADD` for external URLs — `ADD` failures are silent and harder to debug.

### 16. Stale Docker Image in ECR — Missing Servlet Traces

**Problem:** After fixing the Fargate networking issues (Challenge #15), the Datadog APM service page only showed `scheduled.call` and `mysql.query` operations. `servlet.request` was completely absent despite HTTP requests successfully reaching the backend and returning data.

**Root cause:** The Docker image in ECR was stale — built before the `dd-java-agent.jar` was added to the Dockerfile. The running ECS task was using the old image without the Java agent. The `mysql.query` traces were misleading because they came from the **Datadog Agent's direct MySQL integration** (autodiscovery), not from the Java agent. The `scheduled.call` traces came from Spring Boot's built-in Micrometer observation support, also not from the Java agent.

**Diagnosis clues:**
- CloudWatch startup logs showed **no** `DATADOG TRACER CONFIGURATION` block (the dd-java-agent always prints this at startup when loaded)
- Production logs were in **plain text** format instead of JSON — proving the prod profile's logstash-logback-encoder wasn't active, which further confirmed the image was outdated
- Only `scheduled.call` and `mysql.query` in the Datadog operation dropdown — no `servlet.request`
- Everything appeared "green" — containers running, health checks passing, API responses working

**Fix:** Rebuilt the Docker image, pushed to ECR, and forced a new ECS deployment:
```bash
docker build --platform linux/amd64 -t <account>.dkr.ecr.us-east-2.amazonaws.com/soccer-dashboard-backend:latest ./backend
docker push <account>.dkr.ecr.us-east-2.amazonaws.com/soccer-dashboard-backend:latest
aws ecs update-service --cluster soccer-dashboard-cluster --service soccer-dashboard-service --force-new-deployment
```

**Lesson:** When debugging missing traces, always verify the deployed image is current. A stale image can produce partial observability (from infrastructure-level integrations) that masks the fact that application-level instrumentation is entirely absent. Check CloudWatch logs for the `DATADOG TRACER CONFIGURATION` block as the first verification step.

### 17. Dockerfile JVM Argument Ordering

**Problem:** The Dockerfile ENTRYPOINT was `["java", "-javaagent:...", "-Xmx768m", "-jar", "-Dspring.profiles.active=prod", "app.jar"]`. The `-Dspring.profiles.active=prod` flag was placed after `-jar`, so the JVM treated it as an argument passed to the application's `main()` method rather than as a JVM system property.

**Fix:** Moved all JVM flags before `-jar`: `["java", "-javaagent:...", "-Dspring.profiles.active=prod", "-Xmx768m", "-jar", "app.jar"]`

**Lesson:** In `java -jar` commands, everything after `-jar app.jar` is passed to the application, not to the JVM. All `-D`, `-X`, and `-javaagent` flags must come before `-jar`.

---

## Datadog Observability (Full-Stack)

### Why Datadog

The System Design Panel provides live, in-browser observability — but only while someone is watching. Datadog adds persistent, historical, alertable observability across all four pillars: traces, metrics, logs, and real user monitoring. Together, the System Design Panel shows *how the system works* and Datadog shows *how the system performs*.

### Architecture

#### Local Development (Docker Compose)

The Datadog Agent runs as a standalone container in `docker-compose.yml`, collecting data from all services via Docker socket and network:

```
┌─────────────┐     ┌──────────────────┐     ┌──────────────┐
│  Frontend   │────▶│  Datadog Intake   │     │  Datadog     │
│  (RUM SDK)  │     │  (us5.datadoghq)  │◀────│  Agent       │
└─────────────┘     └──────────────────┘     └──────┬───────┘
                                                     │
                              ┌──────────────────────┤
                              │                      │
                        ┌─────┴─────┐          ┌─────┴─────┐
                        │  Backend  │          │  MySQL /   │
                        │ (dd-java- │          │  Redis     │
                        │  agent)   │          │ (integr.)  │
                        └───────────┘          └───────────┘
```

#### Production (ECS Fargate)

The Datadog Agent runs as a **sidecar container** in the same ECS task definition. Sidecar containers share the same network namespace in Fargate, so the backend communicates with the agent via `localhost`:

```
┌─── ECS Fargate Task ──────────────────────────────────┐
│                                                        │
│  ┌──────────────┐    localhost:8126    ┌─────────────┐ │
│  │   backend    │ ──── traces ──────▶ │  datadog-   │ │
│  │  (dd-java-   │    localhost:8125    │   agent     │ │
│  │   agent.jar) │ ──── metrics ─────▶ │             │──────▶ Datadog Intake
│  │              │    stdout           │             │ │      (us5.datadoghq.com)
│  │              │ ──── logs ────────▶ │             │ │
│  └──────────────┘                     └─────────────┘ │
│                                                        │
└────────────────────────────────────────────────────────┘

┌──────────────┐
│  Frontend    │ ──── RUM events ──────▶ Datadog Intake (direct)
│  (CloudFront)│
└──────────────┘
```

**Key difference between local and production:** In Docker Compose, the agent is a separate service (`DD_AGENT_HOST=datadog-agent`). In ECS Fargate, it's a sidecar (`DD_AGENT_HOST=localhost`). The `application-prod.yml` defaults to `localhost` for production.

### Four Pillars of Observability

| Pillar | Technology | What It Captures | Code Changes Required |
|--------|-----------|------------------|----------------------|
| **APM (Traces)** | dd-java-agent (auto-instrumentation) | Every HTTP request, JPA query, Redis command, RestTemplate call | Zero — agent attached via `-javaagent` JVM flag |
| **Metrics** | Micrometer → StatsD → Datadog Agent | Circuit breaker state, rate limiter quota, cache hit/miss, LLM tokens, WebSocket sessions, SSE clients, poll duration | `DatadogMetricsConfig.java` + counters in services |
| **Logs** | logstash-logback-encoder → stdout → DD Agent | JSON-structured logs with `dd.trace_id` and `dd.span_id` | `logback-spring.xml` + `logstash-logback-encoder` dependency |
| **RUM** | @datadog/browser-rum | Page load times, user interactions, JS errors, resource loading, frontend-to-backend trace linking | `main.jsx` + `@datadog/browser-rum` npm package |

### What Datadog Gives Us

With all four pillars connected, Datadog provides:

1. **APM Service Map** — auto-discovered topology showing `soccer-dashboard-backend` → MySQL → Redis → Football-Data.org. No manual configuration.
2. **Request Traces** — every API request traced end-to-end: Spring MVC controller → JPA/Hibernate queries → Redis cache reads → external HTTP calls to Football-Data.org and Claude API. Each trace shows latency breakdown by component.
3. **Error Tracking** — exceptions captured with full stack traces, grouped by type, with affected trace context.
4. **Log-Trace Correlation** — click any log line with `dd.trace_id` to jump to the full APM trace. Click any trace to see inline logs. No manual searching.
5. **Frontend-to-Backend Correlation** — RUM captures browser experience (page load, API call timing, JS errors). `allowedTracingUrls` injects trace headers into `/api/` calls, linking frontend sessions to backend traces. A "slow page" complaint can be traced from browser → CloudFront → ALB → Spring controller → database query.
6. **Custom Business Metrics** — circuit breaker state, API quota remaining, LLM token burn rate, cache hit ratios. These go beyond infrastructure metrics to track application-level health.
7. **Infrastructure Monitoring** — container CPU, memory, network I/O for both backend and agent containers via ECS Fargate integration.
8. **Alerting** — Datadog monitors can fire when circuit breaker state > 0 for 2+ minutes, API quota drops below threshold, error rate spikes, or LLM token usage exceeds budget.

### Expected APM Trace Operations

When the dd-java-agent is correctly loaded, the Datadog APM service page (`soccer-dashboard-backend`) shows three operation types:

| Operation | Source | What Generates It |
|-----------|--------|-------------------|
| `servlet.request` | dd-java-agent auto-instrumentation | Any HTTP request to Spring MVC controllers (GET /api/leagues, GET /api/live, etc.) |
| `scheduled.call` | dd-java-agent auto-instrumentation | `@Scheduled` methods — `PollingScheduler.pollLiveScores` runs every 30s |
| `mysql.query` | Datadog Agent MySQL integration | Direct MySQL monitoring via autodiscovery (independent of Java agent) |

**Important:** If only `scheduled.call` and `mysql.query` appear but `servlet.request` is missing, the dd-java-agent is likely **not loaded**. See Challenge #16 (Stale Docker Image) for diagnosis steps.

### Post-Deployment Verification Checklist

After deploying a new backend image to ECS, verify observability is working:

1. **CloudWatch Logs:** Confirm the `DATADOG TRACER CONFIGURATION` JSON block appears in startup logs (printed by dd-java-agent before Spring Boot banner)
2. **Log Format:** Verify logs are in JSON format (e.g., `{"@timestamp":"...","message":"...","dd.service":"soccer-dashboard-backend"}`). Plain text logs indicate the prod profile or logstash-logback-encoder is not active.
3. **Datadog APM → Services:** The `soccer-dashboard-backend` service should appear with `servlet.request` in the operation dropdown
4. **Datadog APM → Traces:** After hitting any API endpoint, a trace should appear showing the full request waterfall (controller → cache → database → external API)
5. **Log-Trace Link:** Click a JSON log entry with `dd.trace_id` — it should link to the corresponding APM trace

### Integration — Code Changes Made

#### Backend (Java/Spring Boot)

| File | What Was Added | Purpose |
|------|---------------|---------|
| `pom.xml` | `micrometer-registry-statsd` dependency | Push metrics via StatsD protocol to Datadog Agent |
| `pom.xml` | `logstash-logback-encoder` dependency | JSON-structured logging with trace context |
| `Dockerfile` | `curl -Lo dd-java-agent.jar` + `-javaagent` flag | APM auto-instrumentation at JVM level |
| `application.yml` | `management.metrics.export.statsd.*` config | Configure StatsD host/port/flavor for Datadog |
| `application-prod.yml` | Same, with `DD_AGENT_HOST` variable | Production StatsD endpoint (localhost in Fargate) |
| `logback-spring.xml` | Two profiles: human-readable (dev) + JSON (prod) | Dev shows `[dd.trace_id=... dd.span_id=...]` inline; prod emits JSON with MDC fields for Datadog log pipeline |
| `DatadogMetricsConfig.java` | Micrometer gauge/counter registrations | Custom metrics: circuit breaker state, rate limiter quota, WebSocket sessions, SSE clients |
| `FootballDataService.java` | `meterRegistry.counter/timer` calls | API latency timer, rate limiter rejection counter, cache operation counters |
| `LiveScoreAggregator.java` | `meterRegistry.counter` calls | Cache hit/miss counters for live score data |
| `SearchService.java` | `meterRegistry.counter` calls | Cache hit/miss counters for team search |
| `NewsService.java` | `meterRegistry.counter` calls | Cache hit/miss counters for news, LLM token counters |
| `InsightService.java` | `meterRegistry.counter` calls | LLM token counters (input/output by operation) |
| `NarratorService.java` | `meterRegistry.counter` calls | LLM token counters for panel narration |
| `PollingScheduler.java` | `meterRegistry.timer/counter` calls | Poll cycle duration, data diff count |
| `WebSocketBroadcaster.java` | `meterRegistry.counter` calls | WebSocket broadcast count |

#### Frontend (React)

| File | What Was Added | Purpose |
|------|---------------|---------|
| `package.json` | `@datadog/browser-rum` dependency | Datadog RUM SDK |
| `main.jsx` | `datadogRum.init({...})` | Initialize RUM with app ID, client token, `allowedTracingUrls` for trace linking |
| `.env.example` | `VITE_DD_APPLICATION_ID`, `VITE_DD_CLIENT_TOKEN`, `VITE_DD_SITE` | RUM configuration via environment variables |

#### Infrastructure

| File | What Was Added | Purpose |
|------|---------------|---------|
| `docker-compose.yml` | `datadog-agent` service with DD env vars | Local Datadog Agent container |
| `docker-compose.yml` | Docker labels on mysql/redis/backend/frontend | Auto-discovery for Datadog integrations |
| `datadog/conf.d/mysql.d/conf.yaml` | MySQL integration config | Monitor MySQL metrics (connections, queries, replication) |
| `datadog/conf.d/redis.d/conf.yaml` | Redis integration config | Monitor Redis metrics (memory, commands, keyspace) |
| `data/init-datadog-user.sql` | `CREATE USER 'datadog'@'%'` | Dedicated MySQL user with SELECT, PROCESS, REPLICATION CLIENT grants |

#### ECS Task Definition (AWS Console — not in code)

| Container | Environment Variable | Value | Purpose |
|-----------|---------------------|-------|---------|
| `backend` | `DD_AGENT_HOST` | `localhost` | Agent sidecar address |
| `backend` | `DD_SERVICE` | `soccer-dashboard-backend` | Unified service tagging |
| `backend` | `DD_ENV` | `prod` | Environment tag |
| `backend` | `DD_VERSION` | `1.0.0` | Version tag |
| `backend` | `DD_LOGS_INJECTION` | `true` | Inject trace IDs into logs |
| `datadog-agent` | `DD_API_KEY` | `(secret)` | Agent authentication |
| `datadog-agent` | `DD_SITE` | `us5.datadoghq.com` | Datadog intake site |
| `datadog-agent` | `DD_APM_ENABLED` | `true` | Enable trace collection |
| `datadog-agent` | `DD_APM_NON_LOCAL_TRAFFIC` | `true` | Accept traces from other containers |
| `datadog-agent` | `DD_LOGS_ENABLED` | `true` | Enable log collection |
| `datadog-agent` | `ECS_FARGATE` | `true` | Fargate-specific optimizations |
| `datadog-agent` | Port mappings | `8126/tcp`, `8125/udp` | APM traces + StatsD metrics |

### Custom Metrics Inventory

| Metric | Type | Tags | Source |
|--------|------|------|--------|
| `circuit_breaker.state` | Gauge | name (matches/json) | DatadogMetricsConfig |
| `circuit_breaker.failure_count` | Gauge | name | DatadogMetricsConfig |
| `rate_limiter.remaining_quota` | Gauge | name | DatadogMetricsConfig |
| `rate_limiter.used_quota` | Gauge | name | DatadogMetricsConfig |
| `rate_limiter.rejections` | Counter | name | FootballDataService |
| `cache.operations` | Counter | operation (hit/miss), key_pattern | LiveScoreAggregator, SearchService, NewsService |
| `external_api.latency` | Timer | service | FootballDataService |
| `llm.tokens` | Counter | direction (input/output), model, operation | InsightService, NewsService, NarratorService |
| `websocket.active_sessions` | Gauge | — | DatadogMetricsConfig |
| `websocket.broadcasts` | Counter | — | WebSocketBroadcaster |
| `sse.active_clients` | Gauge | — | DatadogMetricsConfig |
| `polling.cycle_duration` | Timer | — | PollingScheduler |
| `polling.data_diff_count` | Counter | — | PollingScheduler |

### Trace Correlation (How the Pillars Connect)

The dd-java-agent automatically injects `dd.trace_id` and `dd.span_id` into the SLF4J MDC. The `logback-spring.xml` configuration includes these fields in log output. This creates three-way correlation:

- **Logs → Traces:** Click any log line with a trace ID to jump to the full APM trace
- **RUM → Traces:** Frontend RUM sessions include `allowedTracingUrls` for `/api/` routes, injecting `x-datadog-trace-id` headers. Click a RUM resource to see the backend trace
- **Traces → Logs:** APM trace view shows correlated logs inline
- **Metrics → Traces:** Spike in `circuit_breaker.state` or `rate_limiter.rejections` can be correlated with traces from the same time window

### Structured Logging Configuration

```xml
<!-- Dev: human-readable with trace IDs -->
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36}
  - [dd.trace_id=%X{dd.trace_id} dd.span_id=%X{dd.span_id}] %msg%n</pattern>

<!-- Prod: JSON for Datadog log pipeline -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>dd.trace_id</includeMdcKeyName>
    <includeMdcKeyName>dd.span_id</includeMdcKeyName>
    <includeMdcKeyName>dd.service</includeMdcKeyName>
    <includeMdcKeyName>dd.env</includeMdcKeyName>
    <includeMdcKeyName>dd.version</includeMdcKeyName>
</encoder>
```

Dev logs are readable in the terminal. Prod logs are JSON — Datadog's log pipeline parses them automatically, extracts trace IDs, and links to APM.

### Datadog Portal (us5.datadoghq.com)

| Section | What to Look For |
|---------|-----------------|
| **APM → Services** | `soccer-dashboard-backend` with request rate, error rate, latency percentiles |
| **APM → Traces** | Individual request traces with span waterfall (controller → cache → API → DB) |
| **APM → Service Map** | Auto-discovered topology: backend → MySQL, Redis, Football-Data.org, Claude API |
| **Infrastructure → Containers** | ECS Fargate containers with CPU, memory, network metrics |
| **Logs → Search** | JSON logs filterable by `service:soccer-dashboard-backend`, `@dd.trace_id` |
| **RUM → Sessions** | Frontend user sessions with page loads, API calls, errors |
| **Metrics → Explorer** | Custom metrics: `circuit_breaker.state`, `rate_limiter.*`, `llm.tokens`, etc. |

---

## Scalability Considerations

### Current Architecture (Production — Single Task)

Deployed on AWS ECS Fargate with a single task (0.5 vCPU, 1GB RAM). Works for portfolio demo scale (~50 concurrent users). Bottlenecks at scale:

| Component | Current | Limit | Solution at Scale |
|-----------|---------|-------|-------------------|
| ECS tasks | 1 Fargate task | ~1000 WS connections | Auto-scale to N tasks behind ALB |
| WebSocket | In-process broadcaster | Single JVM | STOMP + RabbitMQ message broker |
| SSE | In-process emitter | Single JVM | Redis Pub/Sub for cross-instance events |
| Polling | Single @Scheduled thread | One poller | Redis-based leader election |
| ElastiCache | cache.t3.micro | Low throughput | Upgrade node type or add replicas |
| RDS MySQL | db.t3.micro, single AZ | No failover | Multi-AZ deployment + read replicas |
| API quota | 10 req/min | 14,400/day | Multiple API keys, request pooling |

### What I Would Change for 10K+ Users

1. **ECS auto-scaling:** Add target tracking policy on CPU/connection count. ALB distributes across multiple Fargate tasks.

2. **Add a message broker (RabbitMQ/Kafka):** Decouple polling from WebSocket broadcasting. Poller publishes to a topic, multiple WebSocket servers consume and fan out. This is the single most important change for horizontal scaling.

3. **Horizontal WebSocket scaling:** Use Spring's STOMP over WebSocket with a message broker backing. Each Fargate task handles a subset of connections. ALB sticky sessions ensure WebSocket upgrades go to the same task.

4. **Redis Pub/Sub for SSE:** When one task receives a workflow event, publish to Redis channel. All tasks subscribe and broadcast to their local SSE clients.

5. **Rate limiting with Redis:** Move from in-memory sliding window to Redis-backed (`INCR` + `EXPIRE`). Shared across all Fargate tasks to prevent exceeding API quota.

6. **RDS Multi-AZ:** Enable Multi-AZ for automatic failover. Add read replicas if query load increases.

7. **ElastiCache cluster mode:** Shard cache keys across nodes for higher throughput.

---

## Interview Q&A

See [INTERVIEW_QA.md](INTERVIEW_QA.md) for the full list of system design interview questions and answers.
