---
date: 2026-06-26
topic: sdk-integration-improvements
---

# SDK Integration Improvements

## Summary

Address six integration pain points identified by an external integrator working on a modularized, multi-country Android shopping app. Changes cover pending queue lifecycle control, a screen context API, flush consistency, semantic documentation for `url`/`ref` event fields, debug auto-detection, and a utility method for clearing the pending queue. One reported issue (`price: Double`) is accepted as won't fix.

---

## Problem Frame

The feedback originates from integrating the SDK into an app where search tracking is only active in a subset of countries. This surfaced four gaps in the public API:

- No way to signal that tracking is off for a given user — the pending queue grows unboundedly if `configure()` is never called.
- Screen context (`url`, `ref`) cannot be updated after `configure()` because `AndroidContextProvider` is created internally and not accessible to callers.
- `flush()` is `suspend` while all `trackXxx()` methods are fire-and-forget, creating an asymmetry that forces non-trivial Kotlin callers and all Java callers to manage a `CoroutineScope` for a single method.
- `url` and `ref` fields are present on every event but their expected content in a native Android context is undocumented.

Two additional points were raised: a mismatch between the documented and actual behaviour of `DebugRoutingSettings.enabled = null`, and the use of `Double` for monetary values. A seventh addition — not from the external feedback — is `clearPendingActions()`, a utility to explicitly discard buffered pre-configure events without disabling the collector.

---

## Key Decisions

**`disable()` drops immediately rather than buffering.** An explicit `disable()` call signals a deliberate decision to not track this user — buffering events during a disabled period and replaying them on a later `configure()` would be unexpected and hard to reason about. The existing pre-configure buffering (events accumulated before either `configure()` or `disable()` is called) already covers the "not yet decided" case. `disable()` therefore clears `pendingActions` and discards all subsequent `trackXxx()` calls until `configure()` is called.

**Pending queue is bounded, not unbounded.** A default cap of 250 entries (estimated ~50 KB in memory — actual size depends on event payload content) prevents unbounded growth when neither `configure()` nor `disable()` is called. Overflow drops the oldest entry, keeping the most recent context. The cap is configurable.

**`suspend fun flush()` is preserved alongside `flushAsync()`.** The suspend variant has real utility: tests use it to assert post-flush state, and callers who need guaranteed delivery before a critical transition can await it. Removing it would break existing tests. The fire-and-forget `flushAsync()` addresses the API asymmetry without sacrificing the await-capable form.

**`url` and `ref` stay in the event model, but get documented semantics for Android.** The fields are required by the server protocol and removing them is not an option. In a native Android context, `url` maps to the current screen identifier (e.g. a screen name or deep-link path) and `ref` to the previous screen. This is set via the new `setContext()` method.

**`clearPendingActions()` is a lightweight discard utility, not a disable.** It clears `pendingActions` without setting `isDisabled` and without touching the core. Useful when buffered pre-configure events should be dropped (e.g. after a GDPR consent dialog resolves to "no tracking") while keeping the option to call `configure()` later with a clean queue. Callers who also want to stop future tracking should call `disable()` instead.

**`price: Double` is accepted as-is.** This SDK captures analytics data, not financial transactions. The precision loss of `Double` at typical retail price values is negligible in an analytics context, and changing the type would be a breaking API change with no practical benefit.

---

## Requirements

**Pending queue lifecycle**

- R1. `SearchCollector.disable()` sets an internal `isDisabled` flag, clears `pendingActions`, and returns. Subsequent `trackXxx()` calls are immediately discarded. Any events that had already reached the `EventQueue` before `disable()` was called continue to flush normally — the running `SearchCollectorCore` is not disposed. Calling `configure()` re-enables the collector by clearing the flag and replacing the core as usual. `fireAndForget()` becomes three-state: `isDisabled → discard`, `core != null → execute`, `else → buffer to pendingActions`.
- R2. The pending queue is capped at `maxPendingActions` entries. When the cap is reached and a new action arrives, the oldest entry is dropped. Default cap: 250. The cap is configurable via `SearchCollectorConfig`.
- R3. Calling `configure()` re-enables the collector regardless of whether `disable()` was called. Any entries currently in `pendingActions` are replayed. After `disable()`, `pendingActions` is empty (see R1), so the replay is a no-op.

**Screen context**

- R4. `SearchCollector.setContext(url: String, referrer: String = "")` updates the current screen context. `SearchCollector` always caches the latest `url` and `referrer` as internal `@Volatile` fields — regardless of whether `configure()` has been called or the collector is disabled. When a core is active, `setContext()` additionally delegates to `core.setContext()`, which calls `contextProvider.setContext(url, referrer)`. `PendingAction` is extended with `url` and `referrer` fields that are captured from the cached values at the moment each `trackXxx()` call is buffered — so each pre-configure event carries the screen context that was current when it was called, not the context at replay time. When `configure()` creates a new core, it applies the cached values to it before replaying `pendingActions`. The `ContextProvider` interface gains a `setContext(url: String, referrer: String)` method with a default no-op implementation — existing custom implementations are not broken. `AndroidContextProvider` overrides it to call `setUrl()` and `setReferrer()`. Custom providers may override it to manage their own context state.
- R5. Public documentation for the `url` and `ref` event fields states that in native Android apps these carry the current screen identifier and the previous screen identifier respectively, not HTTP URLs or referrers. `setContext()` is the recommended way to keep them current.

**Flush API**

- R6. `SearchCollector.flushAsync()` is a non-suspending, fire-and-forget flush method annotated with `@JvmStatic`. It enqueues a flush on the core's internal scope and returns immediately. Behaviour when no core is configured: no-op.
- R7. `suspend fun SearchCollector.flush()` is unchanged. It remains the await-capable form for callers who need to block until all queued events have been sent (tests, explicit completion scenarios).

**Debug routing**

- R8. When `DebugRoutingSettings.enabled` is `null`, debug routing is auto-detected using `android.os.Build.VERSION.CODENAME != "REL"`. Release builds resolve to `false`; debug builds resolve to `true`. Explicit `true` or `false` values override auto-detection as before.

**Pending queue clearing**

- R9. `SearchCollector.clearPendingActions()` discards all entries currently in `pendingActions` without sending them. Can be called at any time — before `configure()`, while disabled, or while active. Does not affect the enabled/disabled state of the collector.

---

## Acceptance Examples

- AE1. **Covers R1.**
  - **Given** 50 `trackXxx()` calls have accumulated in `pendingActions` before `configure()` was called.
  - **When** `disable()` is called.
  - **Then** `pendingActions` is empty; no events have been sent.
  - **When** 10 further `trackXxx()` calls follow.
  - **Then** all 10 are immediately discarded.

- AE2. **Covers R2.**
  - **Given** neither `configure()` nor `disable()` has been called and `maxPendingActions` is 250.
  - **When** 260 `trackXxx()` calls arrive.
  - **Then** `pendingActions` holds exactly 250 entries — the 10 oldest have been dropped.

- AE3. **Covers R1, R3.**
  - **Given** `disable()` has been called.
  - **When** `configure()` is called.
  - **Then** the collector is active, `pendingActions` is empty, and subsequent `trackXxx()` calls are sent normally.

- AE4. **Covers R4.**
  - **Given** `configure()` has been called.
  - **When** `setContext(url = "pdp/12345", referrer = "search/results")` is called and a `trackSearch()` follows.
  - **Then** the resulting event carries `url = "pdp/12345"` and `ref = "search/results"`.

- AE5. **Covers R4.**
  - **Given** `configure()` has not been called.
  - **When** `setContext(url = "home")` is called, followed by `trackFiredSearch("jeans")`.
  - **Then** the url `"home"` is cached in `SearchCollector`. The `trackFiredSearch` call is buffered in `pendingActions` with `url = "home"` captured at that moment.
  - **When** `configure()` is subsequently called.
  - **Then** the cached context is applied to the new core, and the replayed `trackFiredSearch` event carries `url = "home"`.

- AE6. **Covers R8.**
  - **Given** `DebugRoutingSettings(enabled = null)` is passed to `configure()`.
  - **When** running on a release build (`Build.VERSION.CODENAME == "REL"`).
  - **Then** events are routed to the production endpoint.
  - **When** running on a debug build (`Build.VERSION.CODENAME != "REL"`).
  - **Then** events are routed to the `/debug` endpoint.

- AE7. **Covers R9.**
  - **Given** events have accumulated in `pendingActions` before `configure()` was called (e.g. after a GDPR consent dialog resolves to "no tracking").
  - **When** `clearPendingActions()` is called.
  - **Then** `pendingActions` is empty; the collector state is otherwise unchanged; a subsequent `configure()` replays nothing.

- AE8. **Covers R9.**
  - **Given** `configure()` has been called and the collector is active.
  - **When** `clearPendingActions()` is called.
  - **Then** the call is a no-op — `pendingActions` is already empty because active `fireAndForget()` calls bypass the buffer and go directly to the core.

---

## Scope Boundaries

- `price: Double` on `trackBasket()`, `CheckoutProduct`, and the corresponding events — won't fix. See Key Decisions.
- Multi-instance support (`SearchCollector` as a global `object`) — out of scope.

---

## Sources

- `INTEGRATION_FEEDBACK.md` — source of all six reported issues
- `library/src/main/kotlin/io/searchhub/collector/SearchCollector.kt` — `pendingActions`, `fireAndForget()`, `flush()`
- `library/src/main/kotlin/io/searchhub/collector/model/Config.kt` — `DebugRoutingSettings`, `SearchCollectorConfig`
- `library/src/main/kotlin/io/searchhub/collector/impl/context/AndroidContextProvider.kt` — `setUrl()`, `setReferrer()`
- `library/src/main/kotlin/io/searchhub/collector/model/Event.kt` — `url`, `ref` fields on the sealed class
