# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android Kotlin library implementing the SearchHub Search Collector API. It tracks user search interactions (searches, clicks, basket, checkout) and sends them to a SearchHub SQS endpoint. There are two publishable modules:

- `:library` — core tracking library (always required)
- `:library-workmanager` — optional add-on for background flush via WorkManager (depends on `:library` via `api()`)

## Build Commands

```bash
# Build release AARs + run tests for both library modules
./gradlew buildLibraries

# Build release AARs only (no tests)
./gradlew assembleLibraries

# Run unit tests for both library modules
./gradlew testLibraries

# Run unit tests (all modules including demo-app)
./gradlew test

# Run unit tests for :library only
./gradlew :library:test

# Run a single test class
./gradlew :library:test --tests "io.searchhub.collector.SearchCollectorCoreTest"

# Run a single test method
./gradlew :library:test --tests "io.searchhub.collector.SearchCollectorCoreTest.trackSearch enqueues a search event"

# Clean build
./gradlew clean
```

Tests use Robolectric (no emulator needed) with JUnit 4 and MockK. The test options include `isIncludeAndroidResources = true` so Android resources are available in tests.

## Architecture

```
SearchCollector (object)          ← Public singleton API, fire-and-forget facade
    └── SearchCollectorCore       ← Internal orchestration: event building, batching, flush timer
            ├── Transport         ← Network layer (ShSqsTransport → HttpGetTransport)
            ├── SessionStore      ← Session ID persistence (SharedPreferences / InMemory)
            ├── TrailStore        ← Product→query attribution map (SharedPreferences / InMemory)
            ├── EventQueue        ← Event buffer (InMemory / SharedPreferences)
            ├── ContextProvider   ← URL, referrer, user-agent (AndroidContextProvider / custom)
            └── TimestampProvider ← Current time
```

All six dependencies are interfaces (`io.searchhub.collector.interfaces`) with concrete implementations under `io.searchhub.collector.impl.*`. Every component is swappable via `DependencyOverrides` in `SearchCollectorConfig`.

**Pre-configure buffering:** Calls made before `SearchCollector.configure()` are stored in a `ConcurrentLinkedQueue<PendingAction>` and replayed (on `Dispatchers.IO`) once `configure()` runs. The `bufferedEventsTimestamp` config flag controls whether replayed events use their original timestamp or the current time.

**Flush triggers:** Three things trigger a flush:
1. Auto-flush timer (default every 5 s, runs in `SearchCollectorCore.scope`)
2. Queue full (when `EventQueue.push()` returns `true`, i.e. batch size or 10 KB base64 limit reached)
3. WorkManager periodic worker (`SearchCollectorFlushWorker`, minimum 15-minute interval)

**Batching:** `createBatches()` in `SearchCollectorCore` splits a drained queue into batches capped at `maxBatchSize` events **and** 10 KB (base64-encoded JSON). The 10 KB limit is a load balancer constraint.

**Trail system:** `trackProductClick` / `trackAssociatedProductClick` register a trail (`productId → "$s=<encoded-keywords>/"`) in `TrailStore`. `trackBasket` and `trackCheckout` look up the trail to attach attribution. Trails have a 48-hour TTL and survive app restarts (SharedPreferences by default).

**Event fields — `keywords` vs `query`:** Search-type events (`InstantSearch`, `FiredSearch`, `SuggestSearch`, `SuggestProductClick`, `Search`, `Redirect`) and `Impression` carry two related fields:
- `keywords` — raw user search input (e.g. `"blue jeans"`)
- `query` — URL-encoded trail format `$s={encoded}/` (e.g. `"$s=blue%20jeans/"`) produced by `formatQuery()` in `SearchCollectorCore`

Attribution events (`Product`, `AssociatedProduct`, `Basket`, `Checkout`) carry only `query` (the trail string). The `$s=` prefix is required by the server for attribution matching.

**Transport:** `ShSqsTransport` wraps `HttpGetTransport` and applies debug-routing: in debug builds it prefixes `/debug` to the endpoint path (matching JS `shEndpointResolver` behavior). Debug builds are detected via `android.os.Build.VERSION.CODENAME != "REL"`.

## Package Layout

```
io.searchhub.collector
├── SearchCollector.kt          ← public API (object)
├── SearchCollectorCore.kt      ← internal orchestrator
├── interfaces/                 ← Transport, EventQueue, SessionStore, TrailStore, ContextProvider, TimestampProvider, Logger
├── impl/
│   ├── context/                ← AndroidContextProvider
│   ├── queue/                  ← InMemoryEventQueue, SharedPreferencesEventQueue
│   ├── session/                ← SharedPreferencesSessionStore, InMemorySessionStore
│   ├── timestamp/              ← SystemTimestampProvider
│   ├── trail/                  ← SharedPreferencesTrailStore, InMemoryTrailStore
│   └── transport/              ← ShSqsTransport, HttpGetTransport
└── model/                      ← SearchCollectorConfig, SearchCollectorEvent (sealed), Types, CheckoutProduct, …

io.searchhub.collector.workmanager
└── SearchCollectorFlushWorker.kt
```

## Testing Conventions

Tests live in `library/src/test/` and use the in-memory implementations (`InMemoryEventQueue`, `InMemorySessionStore`, `InMemoryTrailStore`) with a hand-wired `SearchCollectorCore` to avoid SharedPreferences and Android dependencies. Use `batchIntervalMs = 60_000L` to disable auto-flush during tests. Use `kotlinx.coroutines.test.runTest` for suspending assertions.

Tests for the SharedPreferences implementations themselves (`SharedPreferencesSessionStore`, `SharedPreferencesTrailStore`, `SharedPreferencesEventQueue`) use Robolectric with `@RunWith(RobolectricTestRunner::class)` — testing these layers inherently requires Android dependencies.

## Documented Solutions

`docs/solutions/` — documented solutions to past bugs, best practices, and workflow patterns, organized by category with YAML frontmatter (`module`, `tags`, `problem_type`). Relevant when debugging or implementing in documented areas.
