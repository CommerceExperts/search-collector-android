---
type: fix
status: active
origin: docs/brainstorms/2026-06-12-trail-store-proactive-expiry-requirements.md
created: 2026-06-12
---

# fix: Proactive Expiry Cleanup in SharedPreferencesTrailStore

## Summary

Add a private `purgeExpired()` method to `SharedPreferencesTrailStore` that sweeps all expired entries in a single SharedPreferences edit. Wire it to the `init` block (app start) and `register()` (product click). The existing lazy per-key cleanup in `get()` remains unchanged. One new Robolectric test verifies entries are removed by `register()` without any direct `get()` call.

---

## Problem Frame

`SharedPreferencesTrailStore` only removes an expired entry when that exact key is requested via `get()`. Entries for products the user never revisits accumulate in SharedPreferences indefinitely. The fix adds proactive sweeps at two natural housekeeping moments: store construction and product click.

(see origin: `docs/brainstorms/2026-06-12-trail-store-proactive-expiry-requirements.md`)

---

## Requirements Trace

| Requirement | Addressed by |
|---|---|
| Expired entries removed at next `register()` or app restart | U1 |
| Non-expired entries left untouched | U1 |
| Existing tests continue to pass | U1 (no change to `get()` behavior) |
| New test: purge via `register()` without `get()` | U2 |

---

## Key Technical Decisions

**Single `edit().apply()` for all removals.** All expired keys are collected in one pass over `prefs.all` and removed via a single editor. Two alternatives were considered and rejected: per-entry `apply()` creates unnecessary I/O; folding into `get()` is too frequent a trigger (invoked on every basket/checkout). (see origin: Out of Scope)

**No change to `InMemoryTrailStore`.** The in-memory variant's entries are ephemeral and do not accumulate across sessions; proactive cleanup is only needed for the SharedPreferences-backed store.

**No concurrency guards needed.** The `init` block completes synchronously before the store object is accessible, so init-triggered purge and a subsequent `register()` call cannot overlap. Within a single `register()` call, the purge runs before the write in the same coroutine. Two concurrent `register()` calls could each invoke `purgeExpired()` simultaneously, but Android's `SharedPreferences.apply()` merges editors at per-key granularity — each editor only touches keys it explicitly removed, so simultaneous purges produce identical removals with no write loss. (see origin: Out of Scope)

---

## Implementation Units

### U1. Add purgeExpired() and wire to init and register()

**Goal:** Implement the proactive sweep and call it at both trigger points.

**Requirements:** Expired entries removed at next `register()` or app restart; non-expired entries untouched.

**Dependencies:** none

**Files:**
- `library/src/main/kotlin/io/searchhub/collector/impl/trail/SharedPreferencesTrailStore.kt`

**Approach:** Add a private `purgeExpired()` that calls `prefs.all`, iterates all entries, parses the `timestamp` field from each JSON value, and calls `editor.remove(key)` for any entry where `now - timestamp > ttlMs`. If no entries exist or none have expired, return early without creating an editor. If at least one removal occurred, call `editor.apply()`. Wrap each entry's parse in `runCatching` and skip silently on failure, matching the existing pattern in `get()`. Call `purgeExpired()` in the `init` block (after `prefs` is initialized) and at the start of `register()` (before the new entry is written). The per-click prefs.all scan at `register()` is acceptable because the store holds at most a small number of entries in practice — bounded by the number of distinct products clicked per session without checkout (see origin: Behavior).

**Patterns to follow:**
- Existing `runCatching` in `get()` for JSON parsing resilience
- Existing `prefs.edit().remove(key).apply()` for removal

**Test scenarios:** Covered by U2.

**Verification:** All existing tests in `SharedPreferencesTrailStoreTest` pass. Run: `./gradlew :library:test --tests "io.searchhub.collector.SharedPreferencesTrailStoreTest"`.

---

### U2. Add Robolectric test for proactive expiry via register()

**Goal:** Verify that `register()` removes expired entries from SharedPreferences even without a `get()` call on those keys.

**Requirements:** New test case from success criteria.

**Dependencies:** U1

**Files:**
- `library/src/test/kotlin/io/searchhub/collector/SharedPreferencesTrailStoreTest.kt`

**Approach:** Use `ttlMs = 1L` (same pattern as the existing expired-trail test). Register key A, sleep to expire it, then call `register()` with a new key B. Assert that key A is absent from SharedPreferences directly via `prefs.getString("key-A", null)` — without going through `get()`. Optionally, add a second case verifying non-expired entries are not purged.

**Patterns to follow:** Existing `expired trail returns null and is removed from prefs` test in `SharedPreferencesTrailStoreTest` — TTL setup, `Thread.sleep`, direct prefs assertion.

**Test scenarios:**
- **Expired entry purged by register():** register key A with `ttlMs = 1L`, sleep to expire, call `register("key-B", ...)`, assert `prefs.getString("key-A", null) == null`
- **Non-expired entry survives register():** register key A and key B with normal TTL, call `register("key-C", ...)`, assert both A and B are still present in prefs

**Verification:** New test passes under Robolectric. Run: `./gradlew :library:test --tests "io.searchhub.collector.SharedPreferencesTrailStoreTest"`.

---

## Scope Boundaries

**In scope:** `SharedPreferencesTrailStore` only.

**Out of scope:**
- Cleanup in `get()` — call frequency too high (basket/checkout path)
- Background worker or coroutine timer for periodic cleanup
- Configurable trigger selection
- Concurrency guards between `purgeExpired()` and `register()`
- `InMemoryTrailStore` — no persistent accumulation problem

---

## Deferred / Open Questions

### From 2026-06-12 review

- **init-block purge runs synchronous disk I/O on the main thread** — U1 — Add purgeExpired() and wire to init and register() (P1, feasibility, confidence 100)

  `SharedPreferencesTrailStore` is constructed synchronously on the main thread: `SearchCollector.configure()` is called from `Application.onCreate()` (main thread), and there is no `withContext(Dispatchers.IO)` wrapping in `configure()`. An `init` block that calls `prefs.all` — a SharedPreferences disk read — runs entirely on the main thread. On a cold start this triggers a StrictMode `DiskReadOnMainThread` violation and, if prefs are large or the device is slow, an ANR risk. The `register()`-triggered purge does not share this problem because `register()` is a `suspend fun` called from `Dispatchers.IO` within `SearchCollectorCore.scope`. Deliberately deferred: in practice `SharedPreferences` keeps its data in memory after first load, so `prefs.all` is a HashMap read for a small trail store and the ANR risk is negligible.

  <!-- dedup-key: section="u1  add purgeexpired and wire to init and register" title="initblock purge runs synchronous disk io on the main thread" evidence="/home/toarm/dev/searchhub-git/search-collector-android/library/src/main/kotlin/io/searchhub/collector/SearchColl" -->
