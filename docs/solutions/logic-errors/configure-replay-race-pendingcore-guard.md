---
title: "SearchCollector configure() replay race: live events read stale context during pre-configure buffer replay"
date: 2026-06-30
category: docs/solutions/logic-errors
module: library
problem_type: logic_error
component: tooling
severity: high
symptoms:
  - After configure() returns, live tracking calls record the URL or referrer of a past replayed event rather than the current screen
  - Calling configure() a second time silently discards events from the first replay — no error or log warning
  - Under concurrent setNavContext() calls a single event captures a mismatched URL-and-referrer pair (new URL, old referrer)
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
  - browser-info-provider
  - concurrency
---

# SearchCollector configure() replay race: live events read stale context during pre-configure buffer replay

## Problem

When `SearchCollector.configure()` is called, it previously activated the new core synchronously and then launched a coroutine to replay buffered pre-configure events. Because `core` was non-null the moment the coroutine was scheduled, any `fireAndForget()` call arriving during replay dispatched into the same core and raced with the replay's per-event context mutations — causing live events to record the wrong URL or referrer. A secondary issue meant replay ran on an untracked `CoroutineScope` that was never cancelled, so events were silently lost when `configure()` was called a second time.

## Symptoms

- After calling `configure()`, search or click events report an unexpected URL/referrer — typically the URL of a buffered pre-configure event rather than the current screen.
- Calling `configure()` a second time causes some previously buffered events to vanish with no error; they were written to a disposed core.
- Under concurrent `setNavContext()` calls, a single event captures a mismatched URL-and-referrer pair (new URL, old referrer).
- Bug is intermittent and load-dependent — disappears under a debugger or light load.

## What Didn't Work

**Activating core before replay (original approach):** The original code set `core = newCore` synchronously before launching the replay coroutine. Because `core` was immediately visible to all threads, `fireAndForget()` dispatched live events directly to the core while the replay loop was still processing buffered actions with their captured context, producing incorrect attribution data for concurrent live events.

**Separate replay scope (earlier iteration):** Replay previously ran on `CoroutineScope(Dispatchers.IO)` stored in a field called `replayScope`. This scope was never cancelled when `configure()` or `reset()` was called again, so the old replay kept appending to a disposed `SearchCollectorCore` — events were silently discarded with no log.

**Two separate `@Volatile` fields for URL and referrer:** Reading `cachedUrl` and then `cachedReferrer` as two distinct volatile reads gave no atomicity guarantee. A concurrent `setNavContext()` call between the two reads produced a torn context snapshot (new URL, old referrer).

## Solution

Three coordinated fixes in `library/src/main/kotlin/io/searchhub/collector/SearchCollector.kt`.

### P1.5 — Delay core activation with a `pendingCore` guard

Keep `core` null throughout replay so that `fireAndForget()` continues buffering into `pendingActions`. Introduce a `pendingCore` field that holds the in-flight core. Only assign `core = newCore` from inside the replay coroutine, after replay completes, and only if no newer `configure()`/`reset()` has displaced this replay. Drain a second round of `pendingActions` accumulated during replay before restoring the live context.

```kotlin
@Volatile private var pendingCore: SearchCollectorCore? = null

fun configure(config: SearchCollectorConfig) {
    pendingCore?.dispose()   // cancel any in-flight replay
    pendingCore = null
    core?.dispose()
    core = null              // null so fireAndForget() buffers during replay

    // ... build newCore ...

    val pending = mutableListOf<PendingAction>()
    while (true) { pending.add(pendingActions.poll() ?: break) }
    val useOriginal = config.bufferedEventsTimestamp == BufferedEventsTimestamp.ORIGINAL

    if (pending.isNotEmpty()) {
        pendingCore = newCore
        newCore.launch {
            for (action in pending) {
                val ts = if (useOriginal) action.timestamp else System.currentTimeMillis()
                // url/ref are passed directly — no shared-state mutation
                runCatching { action.block(newCore, ts, action.url, action.referrer) }
                    .onFailure { err -> newCore.logReplayError(err) }
            }

            // Activate only if not displaced by a subsequent configure()/reset()
            if (pendingCore === newCore) {
                core = newCore
                pendingCore = null

                // Drain events that buffered in pendingActions during replay
                val lateArrivals = mutableListOf<PendingAction>()
                while (true) { lateArrivals.add(pendingActions.poll() ?: break) }
                for (action in lateArrivals) {
                    val ts = if (useOriginal) action.timestamp else System.currentTimeMillis()
                    runCatching { action.block(newCore, ts, action.url, action.referrer) }
                        .onFailure { err -> newCore.logReplayError(err) }
                }
            }
        }
    } else {
        core = newCore   // no buffered events — activate immediately
    }
}

fun reset(clearStorage: Boolean = false) {
    cachedContext.set("" to "")
    pendingCore?.dispose()   // cancel in-flight replay
    pendingCore = null
    core?.dispose()
    core = null
    pendingActions.clear()
}
```

### P0 — Bind replay to the core's own scope

Replace the free-floating `CoroutineScope(Dispatchers.IO)` with `newCore.launch { … }`. `SearchCollectorCore.scope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is cancelled when `dispose()` is called, so any in-flight replay is automatically torn down the moment `pendingCore?.dispose()` runs.

### P1.4 — Atomic context snapshot

Replace two separate `@Volatile` fields with an `AtomicReference<Pair<String,String>>` so `fireAndForget()` always reads a consistent URL-and-referrer pair in a single atomic operation.

```kotlin
// Before (torn read risk):
@Volatile private var cachedUrl: String = ""
@Volatile private var cachedReferrer: String = ""

// After (atomic snapshot, captured at call time for every event):
private val cachedContext = AtomicReference("" to "")

fun setNavContext(url: String, referrer: String = "") {
    cachedContext.set(url to referrer)
    // No delegation to core — Core no longer holds per-event URL/referrer.
}

private fun fireAndForget(block: suspend (SearchCollectorCore, Long, String, String) -> Unit) {
    if (isDisabled) return
    val ts = System.currentTimeMillis()
    val (url, ref) = cachedContext.get()   // atomic snapshot — always, not just when buffering
    val currentCore = core
    if (currentCore != null) {
        currentCore.launch { block(currentCore, ts, url, ref) }
    } else {
        pendingActions.add(PendingAction(ts, url, ref, block))
        if (pendingActions.size > maxPendingActions) pendingActions.poll()
    }
}
```

## Why This Works

The root cause was that `core` — the field consulted by every `fireAndForget()` call — was made non-null before the replay coroutine had finished, and URL/referrer were read lazily at async processing time rather than captured atomically at call time.

Keeping `core` null during replay closes the replay-race window entirely: `fireAndForget()` takes the else branch and appends to `pendingActions` rather than dispatching into a racing core. The `pendingCore === newCore` identity check ensures only the most-recently-started replay can graduate to become `core`, making all core-activation transitions linearisable. Binding replay to `newCore.scope` means every outstanding replay job is torn down atomically with the core it writes to, eliminating silent event loss.

The `AtomicReference` and call-time context snapshot together close the remaining window: `fireAndForget()` reads `cachedContext` atomically before branching on `currentCore`, so both live and buffered events always carry the URL/referrer that was current at the moment the caller invoked the tracking method. `SearchCollectorCore` no longer reads URL or referrer from `BrowserInfoProvider` at all — those values travel through the block parameters, making context attribution a pure data-flow rather than a shared-state read.

## Prevention

- **Never expose a singleton reference that a background coroutine is still mutating.** If an init or replay phase must mutate shared state, delay publishing the reference until after that phase completes — or use an identity guard inside the coroutine to gate activation.
- **Tie coroutine scopes to the object lifecycle.** Background work that writes to a component must launch on that component's own scope so `dispose()` guarantees no further writes occur.
- **Replace parallel `@Volatile` reads with `AtomicReference`, and snapshot at call time.** Two adjacent volatile reads are not atomic under concurrent writes. Group logically-coupled fields into a single immutable data class wrapped in `AtomicReference`, and read the snapshot before any branching — including for live events, not only for the buffered-event path. Pass the captured values through the call chain as parameters; do not re-read shared state asynchronously at processing time.
- **Test late arrivals during replay.** Buffer an event before `configure()`, call `configure()`, immediately buffer a second event (before replay settles), then assert both events appear with the correct context in the final queue.
- **Test that `reset()` during replay leaves the collector clean.** Call `reset()` immediately after `configure()` and verify no stale events appear after a subsequent `configure()` and flush.

## Related Issues

- `docs/solutions/logic-errors/android-url-encoding-urlencode-vs-uri-encode.md` — sibling logic error in the same module
