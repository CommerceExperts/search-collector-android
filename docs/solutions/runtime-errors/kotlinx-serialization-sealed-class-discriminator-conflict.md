---
title: "kotlinx.serialization sealed class: type field conflicts with JSON class discriminator"
date: 2026-06-08
category: docs/solutions/runtime-errors
module: SearchCollectorCore / Event serialization
problem_type: runtime_error
component: tooling
symptoms:
  - "flush() completes without error but no events reach the server"
  - "IllegalStateException: Sealed class cannot be serialized as base class because property name conflicts with JSON class discriminator 'type'"
  - "Exception is swallowed by runCatching in SearchCollectorCore.launch — no visible crash"
  - "estimateBatchSize() throws during createBatches(), causing flush() to silently abort"
root_cause: wrong_api
resolution_type: code_fix
severity: critical
tags:
  - kotlinx-serialization
  - sealed-class
  - discriminator
  - android
  - kotlin
  - json
---

# kotlinx.serialization sealed class: type field conflicts with JSON class discriminator

## Problem

`flush()` silently never sent any events. All tracking calls appeared to succeed (fire-and-forget, no exceptions propagated), but nothing reached the server. The root cause was a conflict between an explicit `val type: EventType` property on the sealed class and kotlinx.serialization's default JSON class discriminator, which also defaults to the field name `"type"`.

This was a production bug present from the first commit. It only surfaced when unit tests tried to call `flush()` directly.

## Symptoms

- `flush()` returns without error but the transport's `send()` is never called
- `IllegalStateException: Sealed class 'fired-search' cannot be serialized as base class 'SearchCollectorEvent' because it has property name that conflicts with JSON class discriminator 'type'`
- Exception is swallowed by `runCatching` in `SearchCollectorCore.launch` — no crash, no visible indication
- `estimateBatchSize()` in `createBatches()` is the first call site that triggers the exception

## What Didn't Work

- Changing the `Json` config `classDiscriminator` to a different name (e.g., `"__type"`) — would have added an extra unwanted field to the wire format
- Removing the explicit `type` field entirely — changes wire format, breaks server-side parsing

## Solution

Add `@JsonClassDiscriminator("type")` to the sealed class to declare the existing `type` field as the discriminator, and mark each `override val type` with `@Transient` so kotlinx.serialization does not try to serialize it as a separate field.

**Before:**
```kotlin
@Serializable
sealed class SearchCollectorEvent {
    abstract val type: EventType
    ...
    @Serializable
    @SerialName("browser")
    data class Browser(
        ...
        override val type: EventType = EventType.BROWSER,  // conflicts with discriminator
    ) : SearchCollectorEvent()
}
```

**After:**
```kotlin
@file:OptIn(ExperimentalSerializationApi::class)

@JsonClassDiscriminator("type")
@Serializable
sealed class SearchCollectorEvent {
    abstract val type: EventType
    ...
    @Serializable
    @SerialName("browser")
    data class Browser(
        ...
        @Transient override val type: EventType = EventType.BROWSER,  // excluded from serialization
    ) : SearchCollectorEvent()
}
```

The `@file:OptIn` suppresses the `@ExperimentalSerializationApi` warning for `@JsonClassDiscriminator` at file scope — cleaner than a class-level `@OptIn`.

## Why This Works

kotlinx.serialization 1.7.x added strict validation: if a sealed class has an explicit field whose name matches the class discriminator (`"type"` by default), serialization throws. The fix makes the intent explicit:

1. `@JsonClassDiscriminator("type")` tells the serializer to use `"type"` as the discriminator key
2. The discriminator value comes from `@SerialName` on each subclass (e.g. `@SerialName("browser")`)
3. `@Transient` prevents the `val type: EventType` property from also being serialized — without it, there would be a duplicate `"type"` key

The wire format is unchanged: `{"type":"browser","timestamp":...}`. The discriminator value (`"browser"`) and the `@SerialName` on each subclass must match — in this codebase they do because `EventType.BROWSER` has `@SerialName("browser")` and the `Browser` class also has `@SerialName("browser")`.

## Prevention

- When a kotlinx.serialization `@Serializable` sealed class has an explicit `type`-named property, always declare `@JsonClassDiscriminator` explicitly to avoid the implicit conflict
- Add a JSON serialization round-trip test that serializes a concrete event and asserts the `"type"` field is present with the expected value — this would have caught the regression immediately:

```kotlin
@Test
fun `SearchCollectorEvent serializes with type discriminator`() {
    val json = Json { encodeDefaults = true }
    val event = SearchCollectorEvent.FiredSearch(
        timestamp = 1000L, session = "s", channel = "de",
        url = "https://example.com", ref = "",
        keywords = "jeans",
        query = "\$s=jeans/",  // always set explicitly by SearchCollectorCore; see docs/solutions/logic-errors/event-keywords-query-dual-field-wire-format.md
    )
    val serialized = json.encodeToString(listOf(event))
    assertTrue(serialized.contains("\"type\":\"fired-search\""))
    assertTrue(serialized.contains("\"query\":\"\$s="))  // verify trail format is present
}
```

- Run `./gradlew :library:testDebugUnitTest` (not just `assembleRelease`) to catch this class of bug early — it only manifests with the debug build type due to AndroidX dependency resolution

## Related Issues

- `library/src/main/kotlin/io/searchhub/collector/model/Event.kt` — the fixed file
- `library/src/main/kotlin/io/searchhub/collector/SearchCollectorCore.kt` — `estimateBatchSize()` is the first call site that triggered the exception
