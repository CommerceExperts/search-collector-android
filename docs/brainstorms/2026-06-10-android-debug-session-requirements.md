---
date: 2026-06-10
topic: android-debug-session
---

# Android Debug Session

## Summary

Add debug-session support to the Android SDK so that the existing SearchHub debug web app — which currently works only via browser/JS tracking — can also display events from Android apps. Setting a debug token activates both the `sid` override and the `/debug` endpoint routing atomically. The debug web app gains support for app deep-link URLs as the session entry point alongside the existing HTTPS shop URL.

---

## Problem Frame

The debug web app shows a live view of all tracking events for a session. Access is token-secured: the developer opens the web app, requests a token, and the web app generates a tokenized shop URL (e.g., `https://myshop.com?sid=<token>`). The JS tracking picks up the token from the URL and uses it as the session ID (`sid`); the debug endpoint then associates all events carrying that `sid` with the developer's debug session.

For Android apps there is no browser URL — no mechanism to pass the token to a running app. The debug web app also has no concept of an app deep link as an entry point. Both gaps must be closed together to give the feature the same UX fluidity it has on the web.

---

## Key Decisions

**Token, session override, and debug endpoint routing are one atomic operation.** When a debug token is active, the SDK must simultaneously use the token as `sid` and route events to the `/debug` endpoint. There is no state where the token overrides `sid` without `/debug` routing, or vice versa. This mirrors the JS behavior and simplifies the mental model for both the SDK API and the debug web app.

**Deep link as the primary runtime token-delivery mechanism.** The token needs to reach the Android device without requiring a code change and rebuild. An Android Intent (deep link) is the natural carrier: the debug web app generates a tokenized deep-link URL the developer or QA tester can tap; the app opens and hands the Intent to the SDK. This also covers the developer case — they can trigger the same code path programmatically.

**Config-time token as a secondary path for developer testing.** During active SDK development, rebuilding with a hardcoded token is acceptable. A field in `SearchCollectorConfig` provides this path without requiring a deep-link handler to be wired up first.

**Token delivery is the developer's responsibility; the SDK provides the extraction helper.** The SDK does not implement QR code scanning or any UI for token entry. The app developer registers a deep-link handler and calls the SDK helper from it — this keeps the SDK free of camera permissions and scanning dependencies.

---

## Actors

A1. **SDK developer** — integrating or testing the SearchHub SDK in their Android app. Has IDE access; can modify source and rebuild.

A2. **QA / support tester** — verifying event tracking on a physical or emulated device. Has the device in hand and the debug web app open on a nearby browser; no IDE access during the test run.

A3. **Debug web app** — the existing SearchHub debug UI. Generates tokens, accepts an entry-point URL (currently shop URL, extended to app deep links), and shows events for the active debug session.

---

## Requirements

**Android SDK — token injection**

- R1. The SDK provides a runtime method to activate a debug session by accepting a token string. Calling this method after `configure()` sets the token as the current `sid` and switches event routing to the `/debug` endpoint for all subsequent flushes.
- R2. `SearchCollectorConfig` accepts an optional debug session token. When present at `configure()` time, it behaves identically to R1 — `sid` is overridden and `/debug` routing is active from the first flush.
- R3. When a debug session is active, the token must be used as the `sid` in every event sent, overriding the normal session ID for the lifetime of the debug session.
- R4. Debug-session activation must also route events to the `/debug` endpoint (the existing `ShSqsTransport` `/debug` path-prefix behavior). Setting the token without enabling debug routing, or enabling debug routing without the token as `sid`, is not a valid state.
- R5. The SDK provides a helper that extracts a debug session token from an Android `Intent`. The helper returns the token string when present, or null if the Intent carries no debug token, and makes no other side effects — the caller decides whether to activate the session.
- R6. After the helper extracts the token (R5), the caller activates the debug session via the method from R1. The SDK does not auto-activate from Intents without explicit caller invocation.

**Android SDK — session lifecycle**

- R7. A debug session remains active in the SDK until explicitly cleared or until the SDK is reconfigured. The token expires server-side after 12 hours of inactivity — the SDK does not track this TTL, but the debug endpoint will silently stop associating events with the session once the token has expired. The SDK documentation must communicate this limit so developers know to refresh the token after extended inactivity.
- R8. The SDK must provide a way to deactivate a debug session and restore normal session ID and production endpoint routing.

**Debug web app** *(separate repository — not implemented here; listed for cross-system completeness)*

- R9. The "entry point URL" input in the debug web app must accept custom URL schemes (e.g., `myapp://search`) in addition to HTTPS URLs.
- R10. When an app deep-link URL is provided, the debug web app generates a tokenized version: `<base-deep-link>?sid=<token>` (or appends `&sid=<token>` if the base URL already has query parameters). This tokenized link is the one the tester taps or copies to open the app.
- R11. The tokenized deep-link URL must be presented in a tappable / copyable form so the tester can easily transfer it to the device (e.g., clicking opens it, or it is displayed as a copyable string and as a QR code for cross-device transfer).

---

## Key Flows

- F1. **QA tester activates a debug session via deep link**
  - **Trigger:** A2 opens the debug web app, requests a token, and enters the app's deep-link base URL (e.g., `myapp://products`).
  - **Actors:** A2, A3, A1 (setup, prior to test)
  - **Steps:**
    1. A3 generates a token and produces: `myapp://products?sid=<token>`.
    2. A2 taps the link (or scans the QR) on the device. The app opens via its registered deep-link handler.
    3. The app's handler calls the SDK's Intent-extraction helper (R5), receives the token, and calls the activation method (R1).
    4. The SDK sets `sid = token` and routes subsequent events to the `/debug` endpoint.
    5. A2 performs the tracking interactions. A3 shows the events in real time.
  - **Covered by:** R1, R3, R4, R5, R6, R9, R10, R11

- F2. **SDK developer activates a debug session at config time**
  - **Trigger:** A1 is testing their own integration and wants to inspect events without setting up a deep-link handler.
  - **Actors:** A1
  - **Steps:**
    1. A1 obtains a token from the debug web app.
    2. A1 adds the token to `SearchCollectorConfig` (R2) and rebuilds.
    3. On `configure()`, the SDK activates the debug session: `sid = token`, routing to `/debug`.
    4. A1 runs the app; events appear in the debug web app.
  - **Covered by:** R2, R3, R4

---

## Acceptance Examples

- AE1. **Covers R3, R4.** Given a debug token `"abc123"` is active: when the SDK flushes events, every event carries `sid = "abc123"` and the HTTP request targets the `/debug`-prefixed endpoint. Neither condition is met without the other.

- AE2. **Covers R1, R7.** Given the SDK is configured and running normally: when `activateDebugSession("abc123")` is called at runtime, the next flush uses `sid = "abc123"` and the `/debug` endpoint. Subsequent flushes continue with the same token until `deactivateDebugSession()` is called.

- AE3. **Covers R5, R6.** Given an Intent with `?sid=abc123` in its data URI: when the SDK helper is called, it returns `"abc123"` and makes no other changes. The debug session is NOT active until the caller explicitly invokes the activation method (R1) with the returned token.

- AE4. **Covers R8.** Given a debug session is active with token `"abc123"`: when `deactivateDebugSession()` is called, the next flush uses the normal session ID (generated or persisted by the `SessionStore`) and the production endpoint.

- AE5. **Covers R9, R10.** Given the debug web app receives `myapp://search` as the entry URL: the generated tokenized link is `myapp://search?sid=<token>`. Given the input is `myapp://search?q=shoes`: the generated link is `myapp://search?q=shoes&sid=<token>`.

---

## Scope Boundaries

**Deferred for later**
- QR code scanning built into the SDK — would require camera permission and a scanning dependency; the debug web app can generate a QR of the deep link as a convenience (R11), but the SDK itself does not scan.
- Bidirectional registration (app pushes its session ID to the debug endpoint rather than receiving a token) — interesting as a zero-friction evolution but changes the security model.
- Client-side token expiry enforcement — the 12-hour server-side TTL is documented but not enforced in the SDK; the server is the authority on token validity.

**Outside this scope**
- Debugging end-user production sessions — this feature targets developer and QA workflows on debug builds only. No mechanism for attaching a debug token to a production release is in scope.

---

## Dependencies / Assumptions

- The debug endpoint already accepts and processes events with an arbitrary `sid` matching the token; no server-side changes are needed beyond what the existing JS flow already uses.
- The `/debug` endpoint path-prefix mechanism in `ShSqsTransport` is already implemented and tested; this feature reuses it rather than introducing a new routing path.
- App developers are responsible for registering the deep-link intent filter in their `AndroidManifest.xml`. The SDK documents the expected query parameter name (`sid`) but does not mandate a particular URI scheme or host.
- R9–R11 (debug web app changes) must be tracked and implemented in the debug web app's own repository. This document captures them for cross-system context only.
