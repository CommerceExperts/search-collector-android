---
title: "SearchCollectorEvent: query field must use $s= trail format, not raw keywords"
date: 2026-06-09
category: docs/solutions/logic-errors
module: library
problem_type: logic_error
component: tooling
symptoms:
  - "Basket and checkout events are sent successfully but server-side attribution returns nothing"
  - "query field in JSON payload contains raw keywords (\"jeans\") instead of trail format (\"$s=jeans/\")"
  - "No error, no crash — events appear valid in logs and transport, attribution silently fails"
root_cause: wrong_api
resolution_type: code_fix
severity: critical
tags:
  - wire-format
  - query
  - keywords
  - trail
  - attribution
  - search-collector-event
  - serialization
  - "$s-format"
---

# SearchCollectorEvent: query field must use $s= trail format, not raw keywords

## Problem

Attribution events (`Basket`, `Checkout`, `Product`, `AssociatedProduct`) were sent with `query = "jeans"` instead of `query = "$s=jeans/"`. The server requires the `$s={encoded}/` trail prefix to match queries for attribution. Without it, every attribution lookup silently returns nothing — correct event delivery, broken feature.

## Symptoms

- Basket and checkout events are sent without errors, but no search attribution appears in SearchHub reports
- Inspecting the JSON payload (e.g. via `RecordingTransport`) shows `"query":"jeans"` instead of `"query":"$s=jeans%2F"` (or similar `$s=` prefix)
- No exception, no log warning — the events pass serialization and transport cleanly
- Trail registration (`trackProductClick` → `trailStore.register`) works correctly; the bug was in `formatQuery()` being absent/bypassed

## What Didn't Work

- **Renaming `keywords` to `query`**: An initial attempt removed the `keywords` field from search-type events and mapped `keywords` → `query` with a raw string value. This discarded the raw user input (needed for analytics and replay), still sent the wrong format, and broke backward-compat deserialization of events persisted in `SharedPreferencesEventQueue`. (session history)
- **Setting `query = keywords` directly**: Passing the raw string as `query` produces a valid-looking field but the server's attribution matcher requires the `$s=` prefix and URL-encoding — a bare keyword never matches.

## Solution

Two coordinated changes: a dual-field data model on the event type, and a `formatQuery()` helper in `SearchCollectorCore` that is called for every tracking function.

### Event.kt — restore `keywords`, add `query` with backward-compat default

Search-type events (`InstantSearch`, `FiredSearch`, `SuggestSearch`, `SuggestProductClick`, `Search`, `Redirect`) carry both fields:

```kotlin
@Serializable
@SerialName("fired-search")
data class FiredSearch(
    override val timestamp: Long,
    override val session: String,
    override val channel: String,
    override val url: String,
    override val ref: String,
    val keywords: String,
    val query: String = keywords,  // $s=.../ — set explicitly by SearchCollectorCore; default enables deserialization of old persisted events
    @Transient override val type: EventType = EventType.FIRED_SEARCH,
) : SearchCollectorEvent()
```

The `val query: String = keywords` default is a **deserialization safety net only**. Old events persisted by `SharedPreferencesEventQueue` may only have a `"keywords"` JSON field. The default lets them deserialize without crashing. In all new events, `SearchCollectorCore` always sets `query` explicitly.

Attribution-only events (`Impression`, `Product`, `AssociatedProduct`, `Basket`, `Checkout`) carry only `query` — they have no `keywords` field because raw input is not required for attribution-only events.

### SearchCollectorCore.kt — formatQuery() called for every tracking function

```kotlin
private fun formatQuery(keywords: String): String {
    val encoded = java.net.URLEncoder.encode(keywords, "UTF-8")
    return "\$s=$encoded/"
}
```

Every tracking function that receives a `keywords` parameter computes `val query = formatQuery(keywords)` and passes both fields explicitly to the event constructor:

```kotlin
suspend fun trackFiredSearch(keywords: String) {
    val query = formatQuery(keywords)
    val common = getCommonProperties()
    enqueue(
        SearchCollectorEvent.FiredSearch(
            timestamp = common.timestamp, session = common.session,
            channel = common.channel, url = common.url, ref = common.ref,
            keywords = keywords,
            query = query,
        )
    )
}
```

`trackImpression`, `trackProductClick`, and `trackAssociatedProductClick` pass only `query = formatQuery(keywords)` (no `keywords` field on those event types).

## Why This Works

The SearchHub server's attribution matcher uses the `$s={encoded}/` prefix as a protocol token — it signals that the query originated from a search event trail, distinguishes search-driven attribution from direct navigation, and carries the URL-encoded keywords for exact-match lookup. A raw keyword string (`"jeans"`) does not carry this token and matches nothing in the attribution index.

The dual-field model preserves both concerns independently:
- `keywords` — raw user input, preserved for analytics replay and demo tooling
- `query` — wire-protocol value, always the `$s=` trail format when set by `SearchCollectorCore`

The `= keywords` default on `query` is a one-way compatibility shim: it makes old JSON (missing `"query"`) deserialize correctly, but `SearchCollectorCore` always writes both fields explicitly, so no newly emitted event ever relies on the default.

## Prevention

- **Assert `$s=` format in all query-field tests.** Any test that checks a search-type event's `query` field must assert the trail format, not the raw string:

```kotlin
// Correct — verifies the wire format
assertEquals("\$s=jeans/", event.query)

// Wrong — passes even when formatQuery() is missing or bypassed
assertEquals("jeans", event.query)
```

- **Never pass `keywords` directly as `query`.** The `query` field on `SearchCollectorEvent` is always a trail string (`$s=.../`). `keywords` is the raw input. If you need to set `query` without calling `formatQuery()` (e.g., in `trailStore.register` or test setup), use the `$s=` literal directly:

```kotlin
trailStore.register("prod-1", "\$s=sneaker/", TrailType.MAIN)
```

- **Check for both fields in serialization round-trip tests.** The existing discriminator test (see `docs/solutions/runtime-errors/kotlinx-serialization-sealed-class-discriminator-conflict.md`) verifies the `type` field. Extend it to verify `query` format:

```kotlin
val serialized = json.encodeToString(listOf(event))
assertTrue(serialized.contains("\"query\":\"\$s="))  // assert trail prefix is present
assertTrue(serialized.contains("\"keywords\":\"jeans\""))  // assert raw input is preserved
```

- **`formatQuery()` is private to `SearchCollectorCore`.** If you need a trail string outside `SearchCollectorCore` (e.g., test helpers, manual `registerTrail` calls), write the `$s=` literal explicitly — do not re-implement `formatQuery()`. This makes the pattern visible and greppable.

## Related Issues

- `library/src/main/kotlin/io/searchhub/collector/model/Event.kt` — dual-field event model
- `library/src/main/kotlin/io/searchhub/collector/SearchCollectorCore.kt` — `formatQuery()` and all tracking functions
- `docs/solutions/runtime-errors/kotlinx-serialization-sealed-class-discriminator-conflict.md` — related discriminator bug on the same sealed class
