# Trail Store: Proactive Expiry Cleanup

**Date:** 2026-06-12
**Status:** Ready for implementation

## Problem

`SharedPreferencesTrailStore` only cleans up expired entries lazily — an entry is removed only when that exact key is requested via `get()`. Entries that are never read again remain in SharedPreferences indefinitely.

## Goal

Expired trail entries are removed at the next `register()` call or app restart, whichever comes first.

## Behavior

A private `purgeExpired()` method iterates over all entries in `prefs.all`, checks the stored `timestamp` for each, and removes entries where `now - timestamp > ttlMs` in a single `edit().apply()` call.

`purgeExpired()` is called at two points:

| Trigger | When | Rationale |
|---|---|---|
| `init` block | App start / store creation | Cleans up leftovers from previous sessions |
| `register()` | Before writing a new entry | User-driven (product click), infrequent enough for a full scan |

The existing lazy cleanup in `get()` (lines 35–38 in `library/src/main/kotlin/io/searchhub/collector/impl/trail/SharedPreferencesTrailStore.kt`) remains unchanged.

The trail store is expected to hold at most a small number of entries in practice (bounded by the number of distinct products a user clicks per session without completing checkout). The linear-cost full scan at `register()` is acceptable given this bound.

## Out of Scope

- Calling cleanup in `get()` — call frequency is too high (invoked on every basket/checkout)
- Background worker or coroutine timer for periodic cleanup
- Configurable trigger selection
- Concurrency guards between `purgeExpired()` and `register()` — the `init` block completes synchronously before the store object is accessible, so no `register()` call can overlap with the init-triggered purge; within a single `register()` call the purge runs before the write in the same coroutine

## Success Criteria

- Expired entries (older than `ttlMs`) are removed from SharedPreferences after the next `register()` call or app restart
- Non-expired entries are left untouched
- Existing tests continue to pass
- New test case: expired entries are purged by `register()` even if their key was never directly requested via `get()`
