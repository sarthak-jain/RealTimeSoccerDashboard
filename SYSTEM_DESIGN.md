# System Design Document — Real-Time Soccer Dashboard

## Table of Contents
1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Tech Stack & Justifications](#tech-stack--justifications)
4. [Data Flow](#data-flow)
5. [Real-Time Architecture](#real-time-architecture)
6. [Caching Strategy](#caching-strategy)
7. [Resilience Patterns](#resilience-patterns)
8. [Authentication](#authentication)
9. [AI/LLM Integration](#aillm-integration)
10. [System Design Panel (Star Feature)](#system-design-panel-star-feature)
11. [API Design](#api-design)
12. [Database Schema](#database-schema)
13. [Challenges & Solutions](#challenges--solutions)
14. [Scalability Considerations](#scalability-considerations)
15. [Interview Q&A](#interview-qa)

---

## Overview

A real-time soccer dashboard that streams live scores, standings, and fixtures from external APIs, pushes updates to connected clients via WebSocket, and visualizes the entire backend pipeline in a live **System Design Panel** via SSE. Integrates Claude AI for league analysis, news digests, and workflow narration.

**Key metrics:**
- 43 Java backend files, 23 frontend files
- 20+ workflow step types traced in real-time
- 30s polling interval (adaptive), 5min idle
- Sub-second cache reads, <500ms API responses
- 3 AI-powered features with token tracking

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS (Browser)                              │
│                                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │  React UI    │  │  WebSocket   │  │  SSE Client  │  │  Auth (JWT)   │  │
│  │  (Vite)      │  │  Live Scores │  │  Panel Trace │  │  localStorage │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘  │
└─────────┼──────────────────┼──────────────────┼──────────────────┼──────────┘
          │ HTTP/REST        │ ws://            │ SSE              │ Bearer
          ▼                  ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SPRING BOOT BACKEND (8080)                          │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        CONTROLLER LAYER                             │   │
│  │  AuthController  LeagueController  LiveScoreController              │   │
│  │  SearchController  FavoriteController  NewsController               │   │
│  │  InsightController  WorkflowController  SystemController            │   │
│  └────────────────────────────┬────────────────────────────────────────┘   │
│                               │                                             │
│  ┌────────────────────────────▼────────────────────────────────────────┐   │
│  │                        SERVICE LAYER                                │   │
│  │                                                                     │   │
│  │  ┌──────────────┐  ┌──────────────────┐  ┌─────────────────────┐  │   │
│  │  │ FootballData  │  │ LiveScoreAggre-  │  │ PollingScheduler   │  │   │
│  │  │ Service       │  │ gator (merge/    │  │ (@Scheduled 30s)   │  │   │
│  │  │ (API client)  │  │ failover)        │  │ Adaptive intervals │  │   │
│  │  └──────┬────────┘  └────────┬─────────┘  └────────┬───────────┘  │   │
│  │         │                    │                      │              │   │
│  │  ┌──────▼────────┐  ┌───────▼──────────┐  ┌───────▼───────────┐  │   │
│  │  │ CircuitBreaker│  │ DataDiffEngine   │  │ WebSocket         │  │   │
│  │  │ (per-API)     │  │ (score/status    │  │ Broadcaster       │  │   │
│  │  │ 3 fails→open  │  │  change detect)  │  │ (fan-out to       │  │   │
│  │  └───────────────┘  └──────────────────┘  │  subscribed        │  │   │
│  │  ┌───────────────┐  ┌──────────────────┐  │  clients)          │  │   │
│  │  │ RateLimiter   │  │ InsightService   │  └───────────────────┘  │   │
│  │  │ (10 req/min   │  │ (Claude AI)      │                         │   │
│  │  │  sliding win) │  │ League analysis  │  ┌───────────────────┐  │   │
│  │  └───────────────┘  └──────────────────┘  │ NarratorService   │  │   │
│  │  ┌───────────────┐  ┌──────────────────┐  │ (Panel AI explain)│  │   │
│  │  │ AuthService   │  │ NewsService      │  └───────────────────┘  │   │
│  │  │ (BCrypt+JWT)  │  │ (GNews + AI      │                         │   │
│  │  └───────────────┘  │  brief digest)   │                         │   │
│  │                     └──────────────────┘                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      WORKFLOW TRACING LAYER                         │   │
│  │  WorkflowTracer → WorkflowStep (20+ types) → WorkflowEmitter (SSE)│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└────────────┬──────────────────────────────────┬─────────────────────────────┘
             │                                  │
             ▼                                  ▼
┌────────────────────────┐         ┌────────────────────────┐
│     Redis 7 (6379)     │         │     MySQL 8 (3306)     │
│                        │         │                        │
│  standings:{code} 5min │         │  users                 │
│  fixtures:{code}  1hr  │         │  favorite_teams        │
│  live:scores:all  30s  │         │                        │
│  insight:{code}   1hr  │         │                        │
│  news:soccer      15m  │         │                        │
│  news:brief       30m  │         │                        │
│  teams:all        30m  │         │                        │
└────────────────────────┘         └────────────────────────┘
             │
             │ (external)
             ▼
┌────────────────────────────────────────────────────────────┐
│                     EXTERNAL APIs                          │
│                                                            │
│  ┌──────────────────┐  ┌─────────┐  ┌──────────────────┐ │
│  │ Football-Data.org│  │ GNews   │  │ Claude API       │ │
│  │ 10 req/min free  │  │ 100/day │  │ (Haiku 4.5)      │ │
│  │ 12 leagues       │  │         │  │ Insights, Brief, │ │
│  │ Standings,       │  │ Soccer  │  │ Narrator         │ │
│  │ Fixtures, Live   │  │ news    │  │                  │ │
│  └──────────────────┘  └─────────┘  └──────────────────┘ │
└────────────────────────────────────────────────────────────┘
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
| **Containerization** | Docker Compose | Single `docker-compose up` spins up MySQL, Redis, backend, frontend. Reproducible dev environment. |

**Why not:**
- **Node.js backend?** — Java matches my resume and demonstrates strong typing, Spring ecosystem knowledge.
- **MongoDB?** — User/favorite data is relational. Redis handles the ephemeral cache layer.
- **GraphQL?** — REST is simpler for this use case. No deeply nested queries needed.
- **Kafka/RabbitMQ?** — Overkill for single-instance. WebSocket broadcaster handles fan-out directly. Documented as production upgrade path.

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

---

## Scalability Considerations

### Current Architecture (Single Instance)

Works for portfolio demo scale (~50 concurrent users). Bottlenecks at scale:

| Component | Limit | Solution at Scale |
|-----------|-------|-------------------|
| WebSocket connections | ~1000 per JVM | STOMP + RabbitMQ message broker |
| SSE connections | ~1000 per JVM | Load balance with sticky sessions |
| Polling scheduler | Single thread | Distribute with Redis-based leader election |
| Redis | Single instance | Redis Cluster or ElastiCache |
| MySQL | Single instance | RDS Multi-AZ with read replicas |
| API quota | 10 req/min | Multiple API keys, request pooling |

### What I Would Change for 10K+ Users

1. **Add a message broker (RabbitMQ/Kafka):** Decouple polling from WebSocket broadcasting. Poller publishes to a topic, multiple WebSocket servers consume and fan out.

2. **Horizontal WebSocket scaling:** Use Spring's STOMP over WebSocket with a message broker backing. Each server handles a subset of connections.

3. **Redis Cluster:** Shard cache keys across nodes. Add pub/sub for cache invalidation across instances.

4. **CDN for frontend:** S3 + CloudFront. Static assets served from edge locations.

5. **Rate limiting with Redis:** Move from in-memory sliding window to Redis-backed (e.g., `INCR` + `EXPIRE`). Shared across instances.

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

### "What would you do differently if starting over?"

- **Use WebClient instead of RestTemplate** for non-blocking HTTP calls. RestTemplate is synchronous and blocks a thread per request.
- **Add structured logging** (e.g., MDC with trace IDs) for correlation between System Design Panel events and server logs.
- **TypeScript on the frontend** for better type safety across the many component props.
- **Integration tests** with Testcontainers for Redis and MySQL.
- **OpenAPI/Swagger** for API documentation.
