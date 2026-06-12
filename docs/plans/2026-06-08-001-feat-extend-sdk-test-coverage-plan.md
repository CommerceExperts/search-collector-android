---
title: "feat: Extend SDK test coverage"
date: 2026-06-08
status: completed
---

# feat: Extend SDK test coverage

## Summary

The existing test suite covers `InMemoryEventQueue` fully and seven scenarios in `SearchCollectorCore`, but leaves the public singleton, all SharedPreferences implementations, `AndroidContextProvider`, and transport endpoint logic completely untested. This plan adds targeted unit tests for each untested area without introducing new test dependencies. `HttpGetTransport` HTTP sending and `SearchCollectorFlushWorker` are explicitly out of scope for this pass.

---

## Problem Frame

A library consumed by apps with tens of millions of users needs test coverage that matches its risk surface. The current gaps are:

- Seven event types in `SearchCollectorCore` have no test (only `trackSearch`, `trackProductClick`, `trackBasket`, and flush are covered)
- `SearchCollector`'s pre-configure buffering — the mechanism that makes the library safe to call before `configure()` — is entirely untested
- All three SharedPreferences implementations are untested despite being the library's persistence layer
- `ShSqsTransport`'s debug-routing logic (the `/debug` path prefix) is untested

---

## Requirements

- R1: All 12 event types emitted by `SearchCollectorCore` have at least one test verifying the correct event type, key fields, and channel
- R2: `SearchCollector` pre-configure buffering and replay are tested end-to-end
- R3: `SharedPreferencesSessionStore`, `SharedPreferencesTrailStore`, and `SharedPreferencesEventQueue` each have unit tests covering their core contract and TTL/expiry behavior
- R4: `AndroidContextProvider` has tests for the two non-trivial methods (`getUserAgent`, `getLanguage`)
- R5: `ShSqsTransport` endpoint resolution is tested for all four input combinations (debug on/off/null, explicit debugEndpoint)
- R6: No new runtime or test dependencies are introduced

---

## Key Technical Decisions

**KTD1: Robolectric for SharedPreferences and AndroidContextProvider tests**
`SharedPreferencesSessionStore`, `SharedPreferencesTrailStore`, `SharedPreferencesEventQueue`, and `AndroidContextProvider` all require an Android `Context`. Robolectric is already a test dependency (`libs.robolectric`) and `isIncludeAndroidResources = true` is set. Tests use `@RunWith(RobolectricTestRunner::class)` with `ApplicationProvider.getApplicationContext<Context>()`. No new dependency needed.

**KTD2: Make `ShSqsTransport.resolveEndpoint` internal for testability**
`resolveEndpoint` is currently `private` on the companion object. Making it `internal` allows direct unit testing of all four endpoint-resolution paths without needing a live network or MockWebServer. This is a one-line change to the production code.

**KTD3: Test isolation — SharedPreferences and singleton state**
Two separate isolation concerns:

- **Singleton state (`SearchCollectorTest`):** `SearchCollector.reset()` clears `core` and `pendingActions` but does not touch SharedPreferences. To avoid SharedPreferences entirely in U2, all six `DependencyOverrides` must be set (`transport`, `sessionStore`, `trailStore`, `eventQueue`, `contextProvider`, `timestampProvider`). With fully in-memory overrides, `reset()` in `@Before`/`@After` is sufficient.

- **SharedPreferences state (U3 tests):** Robolectric reuses the same `Application` instance across test methods within a class, so SharedPreferences values persist between methods. Each `@Before` must explicitly clear the relevant SharedPreferences file: `context.getSharedPreferences("<PREFS_NAME>", Context.MODE_PRIVATE).edit().clear().apply()`. The affected names are `"SearchCollectorSession"`, `"SearchCollectorTrail"`, and `"search-collector-queue"`.

**KTD4: Coroutines in SearchCollector tests**
`SearchCollector.flush()` is `suspend`. Tests that call it or verify async replay must use `kotlinx.coroutines.test.runTest` and may need a `TestCoroutineScheduler` to advance time. The existing tests already use `runTest` as a pattern to follow.

---

## Implementation Units

### U1. SearchCollectorCore — remaining event types

**Goal:** Cover all event types not yet tested in `SearchCollectorCoreTest`.

**Requirements:** R1

**Dependencies:** none

**Files:**
- `library/src/test/kotlin/io/searchhub/collector/SearchCollectorCoreTest.kt` (modify)

**Approach:** Add one test per untested event type. Follow the existing pattern: call the method, `queue.drain()`, cast and assert key fields. For events with trail involvement (`trackAssociatedProductClick`, `trackCheckout`) verify trail resolution alongside the event fields.

**Patterns to follow:** `SearchCollectorCoreTest.trackSearch enqueues a search event`

**Test scenarios:**
- `trackInstantSearch` enqueues an `InstantSearch` event with correct `keywords` and `channel`
- `trackFiredSearch` enqueues a `FiredSearch` event with correct `keywords`
- `trackSuggestClick` enqueues a `SuggestSearch` event with correct `keywords`, `prefix`, and `position`
- `trackSuggestProductClick` enqueues a `SuggestProductClick` event with correct `keywords`, `prefix`, `position`, and `id`
- `trackRedirect` enqueues a `Redirect` event with correct `keywords` and `resultCount`
- `trackImpression` enqueues an `Impression` event; `data` list matches the input `ProductPosition` list
- `trackAssociatedProductClick` registers a trail with type `ASSOCIATED` and enqueues an `AssociatedProduct` event with the formatted query
- `trackCheckout` with multiple products emits one `Checkout` event per product, each resolving its own trail; products without a trail emit an empty `query`
- `trackBrowser` enqueues a `Browser` event with non-empty `agent` and `lang` fields

**Verification:** All new test methods pass; existing tests remain green.

---

### U2. SearchCollector singleton behavior

**Goal:** Test the public singleton facade — pre-configure buffering, replay, timestamp modes, reconfigure, and reset.

**Requirements:** R2

**Dependencies:** none (uses InMemory implementations via `DependencyOverrides`)

**Files:**
- `library/src/test/kotlin/io/searchhub/collector/SearchCollectorTest.kt` (create)

**Approach:** Each test method calls `SearchCollector.reset()` in `@Before` and `@After`. Wire a `DependencyOverrides` with an in-memory transport and queue so assertions do not require SharedPreferences or network. Use `runTest` for `flush()` calls.

**Test scenarios:**
- Calls made before `configure()` are buffered and replayed after `configure()` — verify the event arrives in the transport
- `bufferedEventsTimestamp = ORIGINAL`: replayed event carries the pre-configure timestamp, not the configure-time timestamp
- `bufferedEventsTimestamp = REPLAY`: replayed event carries a timestamp at or after `configure()` was called
- `configure()` called a second time disposes the first core — events queued on the old core are not sent on the new core
- `reset()` discards buffered pre-configure actions — after `reset()` followed by `configure()`, no stale events are replayed
- `reset()` before `configure()` does not throw

**Verification:** All tests pass; no state leaks between tests (verified by running them in isolation and as a suite).

---

### U3. SharedPreferences implementations

**Goal:** Test all three SharedPreferences-backed implementations using Robolectric.

**Requirements:** R3

**Dependencies:** U2 pattern (Robolectric context setup)

**Files:**
- `library/src/test/kotlin/io/searchhub/collector/SharedPreferencesSessionStoreTest.kt` (create)
- `library/src/test/kotlin/io/searchhub/collector/SharedPreferencesTrailStoreTest.kt` (create)
- `library/src/test/kotlin/io/searchhub/collector/SharedPreferencesEventQueueTest.kt` (create)

**Approach:** Each test class annotated with `@RunWith(RobolectricTestRunner::class)`. Context from `ApplicationProvider.getApplicationContext<Context>()`. For TTL tests, use a configurable `ttlMs` constructor parameter (already available on `SharedPreferencesTrailStore`) and pass `1L` to test immediate expiry. For event age in `SharedPreferencesEventQueue`, the `MAX_EVENT_AGE_MS` constant is internal — test with a real 24h-old timestamp by injecting a stored JSON entry directly via SharedPreferences in the test setup.

**Patterns to follow:** Existing `InMemoryEventQueueTest` structure.

**Test scenarios — SharedPreferencesSessionStore:**
- `getOrCreateSessionId()` on a fresh store returns a non-empty string
- Subsequent calls within TTL return the same session ID
- After TTL expiry, a new session ID is generated
- `touch()` extends TTL — a session touched just before natural expiry is still valid after what would have been its expiry

**Test scenarios — SharedPreferencesTrailStore:**
- `register` + `get` for a known key returns the correct `query` and `type`
- `get` for an unknown key returns `null`
- An entry with `ttlMs = 1L` (expired) returns `null` and is removed from SharedPreferences
- `TrailType.MAIN` and `TrailType.ASSOCIATED` are round-tripped correctly

**Test scenarios — SharedPreferencesEventQueue:**
- `push` / `drain` round-trips events correctly; queue is empty after drain
- `push` returns `true` when `maxBatchSize` is reached
- `transactionalDrain` only removes the events from the block; events pushed during the block survive
- `transactionalDrain` preserves all events when the block throws
- `clear` empties the queue
- Events older than 24 hours are pruned in `loadAndClean` — inject an old entry directly via SharedPreferences and verify it is not returned by `drain`

**Verification:** All Robolectric tests pass via `./gradlew :library:test`.

---

### U4. AndroidContextProvider

**Goal:** Test the non-trivial methods of `AndroidContextProvider`.

**Requirements:** R4

**Dependencies:** U3 (Robolectric setup pattern)

**Files:**
- `library/src/test/kotlin/io/searchhub/collector/AndroidContextProviderTest.kt` (create)

**Approach:** `@RunWith(RobolectricTestRunner::class)`. Use Robolectric's `RuntimeEnvironment` or `ApplicationProvider` for context. `System.setProperty("http.agent", ...)` / `System.clearProperty(...)` in `@Before`/`@After` to control the user-agent fallback path.

**Test scenarios:**
- `getUserAgent()` returns the value of `System.getProperty("http.agent")` when set
- `getUserAgent()` falls back to `"${Build.MANUFACTURER} ${Build.MODEL}"` when `http.agent` is not set
- `getLanguage()` returns a BCP-47 tag (contains a `-` separator, e.g. `"en-US"`)
- `setUrl()` is reflected in the next `getCurrentUrl()` call
- `setReferrer()` is reflected in the next `getReferrer()` call
- `isTouchDevice()` always returns `true`

**Verification:** All tests pass via `./gradlew :library:test`.

---

### U6. reset(clearStorage) — production code and tests

**Goal:** Extend `reset()` with an optional `clearStorage` parameter that wipes all SharedPreferences written by the library. Closes the GDPR erasure gap and enables clean test isolation in U2 and U3.

**Requirements:** R2, R3

**Dependencies:** none

**Files:**
- `library/src/main/kotlin/io/searchhub/collector/SearchCollector.kt` (already modified)
- `library/src/test/kotlin/io/searchhub/collector/SearchCollectorTest.kt` (cover in U2)

**Approach:** `appContext` is stored as `@Volatile private var` on the object at `configure()` time and set to `null` at the end of `reset()`. `reset(clearStorage = true)` clears the three SharedPreferences files before nulling the context. `reset()` without argument behaves exactly as before (rückwärtskompatibel). `@JvmOverloads` ensures both variants are visible as static methods to Java callers.

**Test scenarios** (covered in U2 `SearchCollectorTest`):
- `reset(clearStorage = false)` does not clear SharedPreferences — a value written before reset is still readable after
- `reset(clearStorage = true)` clears all three SharedPreferences files
- `reset(clearStorage = true)` before `configure()` does not throw (no context stored yet)
- `reset()` without argument behaves identically to `reset(clearStorage = false)`

**Verification:** Existing behaviour unchanged; `reset()` callers without argument compile without modification.

---

### U5. ShSqsTransport endpoint resolution

**Goal:** Test all four endpoint-resolution paths in `ShSqsTransport`.

**Requirements:** R5

**Dependencies:** none

**Files:**
- `library/src/main/kotlin/io/searchhub/collector/impl/transport/ShSqsTransport.kt` (modify — make `resolveEndpoint` `internal`)
- `library/src/test/kotlin/io/searchhub/collector/ShSqsTransportTest.kt` (create)

**Approach:** Change `private fun resolveEndpoint(...)` to `internal fun resolveEndpoint(...)` in the companion object. Tests call it directly without constructing a transport or sending anything over the network. `isDebugBuild()` is `private` and depends on `android.os.Build` — test its effect indirectly via the `debugEnabled` override rather than mocking the OS.

**Test scenarios:**
- `debugEnabled = true`: returned URL has `/debug` prepended to the path component
- `debugEnabled = false`: returned URL equals the original endpoint unchanged
- `debugEnabled = null` is covered indirectly — the auto-detect path (`isDebugBuild()`) is not tested directly; document this as a known gap
- `debugEndpoint` provided: `ShSqsTransport` constructor uses the explicit `debugEndpoint` and ignores `debugEnabled` and the original `queueUrl`'s path

**Verification:** All tests pass; `ShSqsTransport` still compiles and existing behavior is unchanged.

---

## Scope Boundaries

### In scope
- All five implementation units above
- One-line visibility change to `resolveEndpoint` in `ShSqsTransport`

### Deferred to Follow-Up Work
- `HttpGetTransport` HTTP sending — requires MockWebServer (new test dependency, deliberate decision)
- `SearchCollectorFlushWorker` — requires `work-testing` dependency and more complex WorkManager setup
- `isDebugBuild()` path in `ShSqsTransport` — depends on `android.os.Build.VERSION.CODENAME`, hard to control without instrumented tests or Robolectric config

---

## Open Questions

- None blocking. The `isDebugBuild()` gap is acceptable; it is documented in U5 test scenarios.
