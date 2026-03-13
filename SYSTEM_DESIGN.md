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
15. [Special Features](#special-features)
16. [Challenges & Solutions](#challenges--solutions)
17. [Scalability Considerations](#scalability-considerations)
18. [Interview Q&A](#interview-qa)

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
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx768m", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
```

**Stage 1 (build):** Full JDK + Maven to compile. ~800MB.
**Stage 2 (runtime):** JRE only + JAR file. ~150MB. No build tools, source code, or dependencies in production image.

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

---

## Observability & Monitoring

### Architecture

The Datadog Agent runs as a sidecar container in Docker Compose, receiving data from all services:

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│  Frontend   │────▶│  Datadog    │     │   Datadog    │
│  (RUM SDK)  │     │   Intake    │◀────│   Agent      │
└─────────────┘     └─────────────┘     └──────┬───────┘
                                               │
                          ┌────────────────────┤
                          │                    │
                    ┌─────┴─────┐        ┌─────┴─────┐
                    │  Backend  │        │  MySQL /   │
                    │ (dd-java- │        │  Redis     │
                    │  agent)   │        │ (integr.)  │
                    └───────────┘        └───────────┘
```

### Four Pillars of Observability

| Pillar | Technology | What it captures |
|--------|-----------|------------------|
| **APM (Traces)** | dd-java-agent (auto-instrumentation) | Every HTTP request, JPA query, Redis command, RestTemplate call. Zero code changes. |
| **Metrics** | Micrometer → StatsD → Datadog Agent | Circuit breaker state, rate limiter quota, cache hit/miss ratios, LLM token usage, WebSocket sessions, SSE clients, poll cycle duration. |
| **Logs** | logstash-logback-encoder → stdout → DD Agent | JSON-structured logs with `dd.trace_id` and `dd.span_id`. Click from any log line → APM trace. |
| **RUM** | @datadog/browser-rum | Page load times, user interactions, JS errors, resource loading. `allowedTracingUrls` connects frontend sessions to backend APM traces. |

### Custom Metrics Inventory

| Metric | Type | Tags | Source |
|--------|------|------|--------|
| `circuit_breaker.state` | Gauge | name | DatadogMetricsConfig |
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

### Trace Correlation

The dd-java-agent automatically injects `dd.trace_id` and `dd.span_id` into the SLF4J MDC. The logback-spring.xml configuration includes these in log output. In Datadog:
- **Logs → Traces:** Click any log line with a trace ID to jump to the full APM trace
- **RUM → Traces:** Frontend RUM sessions include `allowedTracingUrls` for `/api/` routes, injecting trace context headers. Click a RUM resource to see the backend trace
- **Traces → Logs:** APM trace view shows correlated logs inline

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

### "Why did you choose SSE over WebSocket for the System Design Panel?"

SSE is server→client only, which is exactly what the panel needs — the server pushes workflow events, the client never sends messages back. SSE has built-in auto-reconnect via the `EventSource` API, works through HTTP/1.1 proxies without upgrade negotiation, and is simpler server-side (just write to an `SseEmitter`). I used WebSocket for live scores because clients need to send subscription messages (which leagues they're watching).

### "How do you handle the Football-Data.org API going down?"

Three layers: (1) **Circuit breaker** detects consecutive failures and stops calling the API for 60s, preventing cascading timeouts. (2) **Cache** serves stale data — standings cached for 5min, fixtures for 1hr. Even if the API is down, users see recent data. (3) **Demo mode** as a fallback for demonstrations. The circuit breaker state is visible in the System Design Panel and can be manually reset via an admin endpoint.

### "What happens if Redis goes down?"

The app degrades gracefully. Cache reads catch exceptions and fall through to the API call. Cache writes catch exceptions and log warnings. The only impact is higher latency (every request hits the external API) and increased API quota usage. MySQL handles the critical persistent data (users, favorites).

### "How do you prevent the 10 req/min API limit from being exhausted?"

The sliding-window rate limiter tracks requests per 60-second window. The polling scheduler checks remaining quota before each cycle and skips if exhausted. Rate limiting is checked BEFORE the circuit breaker to avoid conflating policy decisions with failure detection. Remaining quota is displayed in the System Design Panel so I can monitor it in real-time.

### "Why cache AI insights for 1 hour?"

League standings don't change dramatically within an hour. The AI analysis covers trends (title race, relegation battle) that are valid for hours. Caching for 1hr means each league's insight costs one API call per hour regardless of how many users request it. The cache key includes the league code, so different leagues get independent caches.

### "How does the data diff engine work?"

It compares the previous cached match list against the fresh API response. It checks three dimensions: score changes (goals), status changes (kickoff, halftime, full-time), and new events (cards, substitutions). Only the delta is broadcast via WebSocket, not the full match list. This reduces bandwidth and allows the frontend to animate specific changes (score flash effect).

### "Walk me through what happens when a user opens the dashboard."

1. React app loads, connects to SSE endpoint (`/api/workflow/stream`) and WebSocket (`/ws/live`)
2. User selects Premier League → `GET /api/leagues/PL/standings`
3. Backend checks Redis (cache miss on first load), calls Football-Data.org, caches result, returns JSON
4. System Design Panel shows the full trace: Gateway → Cache MISS → Rate Check → API Call (340ms) → Cache Write → Response
5. WebSocket sends subscription for PL. If a polling cycle detects a score change, the client gets a push update
6. User clicks AI Analysis → `GET /api/insights/PL` → Claude Haiku generates analysis → cached for 1hr
7. Second user loads same league → all cache HITs → sub-5ms response

### "How did you deploy this to production?"

The backend runs as a Docker container on **ECS Fargate** — serverless containers, no EC2 instances to manage. It sits behind an **ALB** (Application Load Balancer) with a 3600s idle timeout for SSE/WebSocket. **RDS MySQL** and **ElastiCache Redis** provide managed database and cache. The React frontend is a static build deployed to **S3** behind **CloudFront** for HTTPS and CDN caching. CloudFront routes `/api/*` and `/ws/*` to the ALB, so everything goes through a single HTTPS domain. All secrets are injected via ECS task definition environment variables — nothing in code or Docker images.

### "How does this project handle streams of data?"

Three independent streams: (1) **Live scores via WebSocket** — the backend polls Football-Data.org every 30s, diffs against cached data, and pushes only the changes to subscribed clients. This is a server-initiated push stream with selective fan-out. (2) **System Design Panel via SSE** — an append-only event stream where every backend operation emits trace events. Multiple users see each other's traces plus background polling. This is a multiplexed, unbounded event log. (3) **AI analysis pipeline** — request-triggered with aggressive caching. One LLM call is amortized across all users for 1 hour. The three streams are independent but the SSE panel acts as a meta-stream, tracing operations from the other two.

### "Why ECS Fargate over Lambda or EC2?"

Lambda has a 15-minute execution limit and cold starts — incompatible with long-lived WebSocket/SSE connections that persist for the entire browser session. EC2 would work but requires patching, scaling configuration, and OS management. Fargate gives always-on containers with zero infrastructure management. I define the CPU/memory (0.5 vCPU, 1GB), push a Docker image, and ECS handles placement, restarts, and health checks.

### "How do you handle the CloudFront + WebSocket interaction?"

CloudFront natively supports WebSocket — when it receives a request with `Upgrade: websocket` header, it forwards it to the origin (ALB) and establishes a persistent connection. I added a `/ws/*` cache behavior in CloudFront that forwards all headers to the ALB. This way, both REST API and WebSocket use the same HTTPS CloudFront domain, avoiding mixed-content browser security errors.

### "How did you implement observability for this project?"

Four pillars through Datadog: (1) **APM** via dd-java-agent — zero-code auto-instrumentation that traces every Spring MVC request, JPA query, Redis command, and RestTemplate call. (2) **Custom metrics** via Micrometer with a StatsD registry — circuit breaker state, rate limiter quota, cache hit/miss ratios, LLM token consumption, and WebSocket session counts. (3) **Structured logging** with logstash-logback-encoder — JSON logs in production with `dd.trace_id` for log-to-trace correlation. (4) **RUM** via @datadog/browser-rum — page load times, user interactions, and frontend-to-backend trace linking via `allowedTracingUrls`.

### "How do you correlate a slow user experience to a backend issue?"

Datadog RUM captures the frontend request. Because `allowedTracingUrls` is configured for `/api/` routes, RUM injects trace context headers into every API call. I can click a slow RUM resource and jump directly to the backend APM trace showing the exact Spring controller, Redis cache lookup, and external API call that caused the latency. If the backend was fast but the user still experienced slowness, RUM shows whether it was network, JS execution, or resource loading.

### "How do you monitor LLM costs across your AI features?"

Each of the three AI services (InsightService, NewsService, NarratorService) increments Micrometer counters tagged by `direction` (input/output), `model`, and `operation` after every Claude API call. These flow through StatsD to Datadog, where I can build dashboards showing daily token burn rate per operation. Combined with cache hit rate metrics, I can see that 95% of insight requests are served from cache, so the actual LLM cost is one API call per league per hour rather than per user request.

### "What would you do differently if starting over?"

- **Use WebClient instead of RestTemplate** for non-blocking HTTP calls. RestTemplate is synchronous and blocks a thread per request.
- **TypeScript on the frontend** for better type safety across the many component props.
- **Integration tests** with Testcontainers for Redis and MySQL.
- **OpenAPI/Swagger** for API documentation.
- **CI/CD pipeline** with GitHub Actions: build → test → push to ECR → deploy to ECS on every push to main.
- **AWS Secrets Manager** instead of plain environment variables for sensitive values like API keys and database passwords.
