---
title: "SearchCollector configure() replay race: live events dispatched to wrong core"
date: 2026-06-30
category: docs/solutions/logic-errors
module: library
problem_type: logic_error
component: tooling
severity: high
symptoms:
  - Calling configure() a second time silently discards events from the first replay — no error or log warning
  - Events arriving right after configure() are lost or appear in the wrong batch
  - Bug is intermittent and disappears under light load or a debugger
root_cause: async_timing
resolution_type: code_fix
tags:
  - coroutines
  - singleton
  - configure
  - replay
  - race-condition
  - pending-core
  - concurrency
---

# SearchCollector configure() replay race: live events dispatched to wrong core

## Problem

When `SearchCollector.configure()` is called, it previously activated the new core synchronously and then launched a coroutine to replay buffered pre-configure events. Because `core` was non-null the moment the coroutine was scheduled, any `fireAndForget()` call arriving during replay dispatched live events into the already-active core — racing with the in-progress replay. A secondary issue meant replay ran on an untracked `CoroutineScope` that was never cancelled, so events were silently lost when `configure()` was called a second time.

## Symptoms

- Calling `configure()` a second time causes some previously buffered events to vanish with no error; they were written to a disposed core.
- Events fired immediately after `configure()` (before replay settles) are sometimes lost.
- Bug is intermittent and load-dependent — disappears under a debugger or light load.

## What Didn't Work

**Activating core before replay (original approach):** The original code set `core = newCore` synchronously before launching the replay coroutine. Because `core` was immediately visible to all threads, `fireAndForget()` dispatched live events directly to the core while replay was still running — live and replayed events interleaved non-deterministically.

**Separate replay scope (earlier iteration):** Replay previously ran on `CoroutineScope(Dispatchers.IO)` stored in a field called `replayScope`. This scope was never cancelled when `configure()` or `reset()` was called again, so the old replay kept appending to a disposed `SearchCollectorCore` — events were silently discarded with no log.

## Solution

Two coordinated fixes in `library/src/main/kotlin/io/searchhub/collector/SearchCollector.kt`.

### P1.5 — Delay core activation with a `pendingCore` guard

Keep `core` null throughout replay so that `fireAndForget()` continues buffering into `pendingActions`. Introduce a `pendingCore` field that holds the in-flight core. Only assign `core = newCore` from inside the replay coroutine, after replay completes, and only if no newer `configure()`/`reset()` has displaced this replay. Drain a second round of `pendingActions` accumulated during replay before activating.

```kotlin
@Volatile private var pendingCore: SearchCollectorCore? = null

fun configure(config: SearchCollectorConfig) {
    pendingCore?.dispose()   // cancel any in-flight replay
    pendingCore = null
    core?.dispose()
    core = null              // null so fireAndForget() buffers during replay

    // ... build newCore ...

    val pending = drainPendingActions()
    if (pending.isNotEmpty()) {
        pendingCore = newCore
        newCore.launch {
            replay(pending, useOriginal, newCore)

            // Activate only if not displaced by a subsequent configure()/reset()
            if (pendingCore === newCore) {
                core = newCore
                pendingCore = null
                replay(drainPendingActions(), useOriginal, newCore)
            }
        }
    } else {
        core = newCore   // no buffered events — activate immediately
    }
}

fun reset(clearStorage: Boolean = false) {
    pendingCore?.dispose()   // cancel in-flight replay
    pendingCore = null
    core?.dispose()
    core = null
    pendingActions.clear()
}
```

### P0 — Bind replay to the core's own scope

Replace the free-floating `CoroutineScope(Dispatchers.IO)` with `newCore.launch { … }`. `SearchCollectorCore.scope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is cancelled when `dispose()` is called, so any in-flight replay is automatically torn down the moment `pendingCore?.dispose()` runs.

## Note on URL/referrer context

An earlier iteration of this bug also involved URL and referrer being read from a shared `ContextProvider` at async processing time rather than at call time — a design mistake that compounded the race. The fix was to remove URL/referrer from `BrowserInfoProvider` entirely: `setNavContext()` stores them in an `AtomicReference`, `fireAndForget()` snapshots them atomically at the moment of the call, and passes them through block parameters to `SearchCollectorCore`. Reading data at the point where it is logically owned (the tracking call) rather than at the point of processing eliminates the race by construction.

## Why This Works

Keeping `core` null during replay closes the race window entirely: `fireAndForget()` takes the else branch and appends to `pendingActions` rather than dispatching into a racing core. The `pendingCore === newCore` identity check ensures only the most-recently-started replay can graduate to become `core`, making all core-activation transitions linearisable. Binding replay to `newCore.scope` means every outstanding replay job is torn down atomically with the core it writes to, eliminating silent event loss.

## Prevention

- **Never expose a singleton reference that a background coroutine is still mutating.** If an init or replay phase must mutate shared state, delay publishing the reference until after that phase completes — or use an identity guard inside the coroutine to gate activation.
- **Tie coroutine scopes to the object lifecycle.** Background work that writes to a component must launch on that component's own scope so `dispose()` guarantees no further writes occur.
- **Capture event data at call time, not at processing time.** Any value that can change between the tracking call and the async processing of the event (URL, referrer, timestamp) must be snapshotted when the caller invokes the method and passed as parameters — never re-read from shared state inside the coroutine.
- **Test late arrivals during replay.** Buffer an event before `configure()`, call `configure()`, immediately buffer a second event (before replay settles), then assert both events appear in the final queue.
- **Test that `reset()` during replay leaves the collector clean.** Call `reset()` immediately after `configure()` and verify no stale events appear after a subsequent `configure()` and flush.

## Related Issues

- `docs/solutions/logic-errors/android-url-encoding-urlencode-vs-uri-encode.md` — sibling logic error in the same module
