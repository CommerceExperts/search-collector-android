---
title: Use android.net.Uri for URL encoding in transport, trail format, and navigation, not java.net.URLEncoder
date: 2026-06-11
last_updated: 2026-06-11
category: docs/solutions/logic-errors
module: library
problem_type: logic_error
component: tooling
symptoms:
  - Multi-word keywords show + in place of spaces in the SearchHub debug tool
  - Single-word keywords are unaffected and the bug is invisible for those
  - Jetpack Navigation screens after the first hop display %20 instead of spaces in string arguments
  - "Trail strings ($s=) in search events contain + for spaces instead of %20, breaking server-side attribution for multi-word queries"
root_cause: wrong_api
resolution_type: code_fix
severity: high
tags:
  - url-encoding
  - uri-encode
  - urlencode
  - android-net-uri
  - navigation
  - navtype
  - http-get-transport
  - format-query
  - encodeURIComponent
---

# Use android.net.Uri for URL encoding in transport, trail format, and navigation, not java.net.URLEncoder

## Problem

Three related occurrences of the same bug: `java.net.URLEncoder.encode()` was used instead of `android.net.Uri.encode()`, causing spaces to be encoded as `+` instead of `%20`. This broke the SearchHub debug tool display, server-side attribution for multi-word queries, and Jetpack Navigation argument passing.

## Symptoms

- Multi-word keywords show `+` in place of spaces in the SearchHub debug tool. Single-word keywords are unaffected and the bug is invisible for those.
- Trail strings in events (`$s=blue+jeans/` instead of `$s=blue%20jeans/`) cause silent attribution failure for multi-word queries — the server returns no match but emits no error.
- Screens after the first navigation hop display percent-encoded sequences (`%20`, `%26`, etc.) in string arguments instead of the original text.

## What Didn't Work

- Adding `.replace("+", "%20")` as a post-processing step after `URLEncoder.encode()` — this is fragile and misses other characters where the two standards diverge (`!`, `'`, `(`, `)`, `*`, `~`). `URLEncoder` encodes `~`; `encodeURIComponent` does not.
- Relying on Jetpack Navigation to decode arguments automatically — it does not. `NavType.StringType` passes the raw path segment without any URL-decoding.
- **Trusting existing code as a spec.** After fixing `HttpGetTransport`, a comment was written stating that `formatQuery()` *intentionally* uses `+`. This was wrong — the assumption that the server accepts `+` was inferred from the broken code, never verified against the server contract or the JS SDK.

## Solution

### Fix A — `HttpGetTransport` (library)

Replace `URLEncoder.encode()` with `android.net.Uri.encode()`, extracted into a named internal helper:

```kotlin
// Before
val urlEncoded = URLEncoder.encode(jsonBody, "UTF-8")
val base64Body = base64UrlEncode(urlEncoded.toByteArray(Charsets.UTF_8))

// After
internal fun encodeURIComponent(value: String): String = Uri.encode(value)
val base64Body = base64UrlEncode(encodeURIComponent(jsonBody).toByteArray(Charsets.UTF_8))
```

File: `library/src/main/kotlin/io/searchhub/collector/impl/transport/HttpGetTransport.kt`

### Fix B — `formatQuery()` (library)

Replace `URLEncoder.encode()` with `android.net.Uri.encode()` in the trail format builder:

```kotlin
// Before
private fun formatQuery(keywords: String): String {
    val encoded = java.net.URLEncoder.encode(keywords, "UTF-8")
    return "\$s=$encoded/"
}

// After
private fun formatQuery(keywords: String): String = "\$s=${android.net.Uri.encode(keywords)}/"
```

File: `library/src/main/kotlin/io/searchhub/collector/SearchCollectorCore.kt`

Regression test:

```kotlin
@Test
fun `keywords with spaces are encoded as %20 in query trail`() = runTest {
    core.trackFiredSearch("blue jeans")
    val event = queue.drain()[0] as SearchCollectorEvent.FiredSearch
    assertEquals("blue jeans", event.keywords)
    assertEquals("\$s=blue%20jeans/", event.query)
}
```

### Fix C — `AppNavHost` (demo-app)

Introduce a symmetric pair of private extensions and use `decodeOrEmpty()` at every argument read-back:

```kotlin
private fun String.encode(): String = Uri.encode(this)
private fun String?.decodeOrEmpty(): String = Uri.decode(this.orEmpty())  // getString() returns String?

// Encode before embedding in route:
navController.navigate("results/${keywords.encode()}")

// Decode on every argument read-back:
val keywords = backStackEntry.arguments?.getString("keywords").decodeOrEmpty()
```

File: `demo-app/src/main/kotlin/io/searchhub/demo/AppNavHost.kt`

## Why This Works

`android.net.Uri.encode()` is Android's equivalent of JavaScript's `encodeURIComponent`. It percent-encodes all characters except the RFC 3986 unreserved set (`A–Z a–z 0–9 - _ . ! ~ * ' ( )`). Spaces become `%20`. This matches what the JS SDK sends, so the SearchHub server can round-trip decode the keywords correctly.

`java.net.URLEncoder.encode()` follows the `application/x-www-form-urlencoded` specification (HTML form submission), where spaces become `+` and `~` is encoded. It is designed for form bodies, not URL path segments, query parameter values, or trail strings.

For Jetpack Navigation, `Uri.encode` / `Uri.decode` is the correct symmetric pair because navigation paths are URI path segments. `NavType.StringType.parseValue()` makes no guarantee about decoding — the decode must be explicit on every read.

## Prevention

- **One encoding API throughout.** Use `android.net.Uri.encode()` everywhere in this library. `java.net.URLEncoder` is banned — not just for transport, but also for `formatQuery()` and any future URL-building code.
- **Cross-check against the JS SDK.** The SearchHub JS SDK is the reference implementation. When encoding behavior is unclear, compare against the JS collector rather than inferring from existing Android code.
- **Test multi-word inputs explicitly.** Unit tests for any trail-producing function must assert the exact encoded string with a space-containing keyword. `SearchCollectorCoreTest` uses `@RunWith(RobolectricTestRunner::class)` because `android.net.Uri` requires it.
- **Treat "inferred from existing code" as unverified.** Comments that claim intentional behavior without a test or server-side reference are assumptions, not facts.

## Related Issues

- `docs/solutions/logic-errors/event-keywords-query-dual-field-wire-format.md` — documents the `formatQuery()` function and the `$s=` trail format structure.
