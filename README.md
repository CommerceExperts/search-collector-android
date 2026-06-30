<p align="center">
  <a href="https://www.searchhub.io/" target="blank"><img src="https://avatars.githubusercontent.com/u/29304684?v=4" width="120" alt="Searchhub Logo" /></a>
</p>

<p align="center">A native Kotlin library for collecting search and search-related events on Android.</p>

<p align="center">
    <a href="#" target="_blank"><img src="https://github.com/CommerceExperts/search-collector-android/actions/workflows/ci.yml/badge.svg" alt="build workflow" /></a>
    <a href="https://github.com/CommerceExperts/search-collector-android/blob/main/LICENSE" target="_blank"><img src="https://img.shields.io/github/license/CommerceExperts/search-collector-android" alt="license" /></a>
    <a href="https://twitter.com/cxpsearchhub" target="_blank"><img src="https://img.shields.io/twitter/follow/cxpsearchhub?style=social" alt="twitter" /></a>
</p>

## Features

- 12 event types (Search, Product Click, Basket, Checkout, …)
- Singleton API, fire-and-forget — never blocking
- Automatic batching (10 events / 10 KB per request)
- Calls made before `configure()` are buffered and replayed automatically
- SharedPreferences-based session and trail persistence
- Fully swappable dependency injection system
- Optional background flush via WorkManager
- Java-compatible API (`@JvmStatic`)
- Min SDK: API 21 (Android 5.0)

## Modules

| Module | Description | When to use |
|---|---|---|
| `:library` | Core library | Always |
| `:library-workmanager` | Periodic background flush | When delivery must be guaranteed even after the app is killed |

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/CommerceExperts/search-collector-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

A GitHub token with `read:packages` permission is required. Add it to `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_TOKEN
```

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("io.searchhub:sc-android:0.0.2")

    // Optional: background flush
    implementation("io.searchhub:sc-android-workmanager:0.0.2")
}
```

## Quick Start

### 1. Configure (once in `Application.onCreate`)

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        SearchCollector.configure(
            SearchCollectorConfig(
                endpoint = "https://your-sqs-endpoint",
                channel = "de",
                context = applicationContext,
            )
        )
        SearchCollector.initialize()  // sends a browser event with device info
    }
}
```

### 2. Track events

```kotlin
// Search input (debounce recommended)
SearchCollector.trackInstantSearch("jean")

// Search submitted
SearchCollector.trackFiredSearch("jeans")

// Search results displayed
SearchCollector.trackSearch(keywords = "jeans", count = 120)

// Product clicked — automatically registers a trail
SearchCollector.trackProductClick(productId = "prod-123", position = 0, keywords = "jeans")

// Add to cart
SearchCollector.trackBasket(productId = "prod-123", price = 49.99)

// Checkout
SearchCollector.trackCheckout(
    products = listOf(
        CheckoutProduct(id = "prod-123", price = 49.99, quantity = 2)
    )
)
```

### 3. Set the current screen URL (recommended)

Android has no concept of a browser URL. Call `setContext` whenever the user navigates to a new screen. The values are attached to every subsequent event:

```kotlin
// Call on every screen change — safe to call before or after configure()
SearchCollector.setContext(
    url = "app://my-app/search?q=jeans",
    referrer = "app://my-app/home",   // optional
)
```

Events buffered before `configure()` capture the context at the time they were queued, so per-event attribution works even during app startup.

## API Reference

| Method | When to call |
|---|---|
| `initialize()` | Once on app start |
| `setContext(url, referrer?)` | On every screen navigation |
| `trackInstantSearch(keywords)` | On each search input change (debounced) |
| `trackFiredSearch(keywords)` | On explicit search submit |
| `trackSuggestClick(keywords, prefix, position)` | On autocomplete suggestion click |
| `trackSuggestProductClick(keywords, prefix, position, productId)` | On product click in autocomplete |
| `trackSearch(keywords, count, action?)` | When search results are rendered |
| `trackRedirect(keywords, resultCount)` | When a search redirects to a landing page |
| `trackImpression(keywords, products)` | When products are visible on the SERP |
| `trackProductClick(productId, position, keywords)` | On product click in the SERP |
| `trackAssociatedProductClick(productId, position, keywords)` | On related product click (e.g. on a PDP) |
| `trackBasket(productId, price)` | On add-to-cart |
| `trackCheckout(products)` | On completed purchase |
| `registerTrail(key, query, trailType?)` | Manually register a search trail |
| `copyTrail(fromProductId, toProductId)` | When a product variant is selected on the PDP |
| `flush()` | Force-send all queued events (suspending) |
| `flushAsync()` | Fire-and-forget flush — returns immediately |
| `disable()` | Opt out of tracking; clears the pending buffer |
| `clearPendingActions()` | Discard buffered pre-configure events without disabling |
| `reset(clearStorage?)` | Dispose the instance (e.g. after logout); pass `true` to also erase session, trails, and persisted queue |

## Background Flush (WorkManager)

Guarantees event delivery even if the app is killed before the in-process auto-flush fires — particularly important for basket and checkout events.

```kotlin
// Call once after configure():
SearchCollectorFlushWorker.schedule(applicationContext)

// Cancel (e.g. after logout):
SearchCollectorFlushWorker.cancel(applicationContext)
```

The worker runs every 15 minutes (WorkManager minimum) and only when a network connection is available.

## Debug Sessions

Debug sessions route events to a separate debug endpoint and tag them with a specific session token, making it easy to inspect a test run in the SearchHub debug tool without mixing in production traffic.

A debug session is started from your SearchHub account: open the application from there and it will launch the app via a deep-link containing a generated session token. Override `onNewIntent` in your `Activity` to pick it up:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent) {
        val token = SearchCollector.extractDebugToken(intent) ?: return
        if (token.isNotEmpty()) {
            SearchCollector.activateDebugSession(token)
        }
    }
}
```

`extractDebugToken` reads the `___scForceNewSession_` query parameter from the Intent URI:

```
yourapp://debug?___scForceNewSession_=<token>
```

Once active, all events are routed to the debug endpoint and tagged with the session token until `deactivateDebugSession()` is called:

```kotlin
SearchCollector.deactivateDebugSession()
```

## Opt-out / Disable

Call `disable()` to stop tracking — for example when the user withdraws consent. It clears any events buffered before `configure()` and silently discards all subsequent tracking calls:

```kotlin
SearchCollector.disable()
```

Calling `configure()` re-enables tracking. Events queued in the active collector before `disable()` continue to flush normally.

To discard only the pre-configure buffer without disabling tracking, use `clearPendingActions()`:

```kotlin
SearchCollector.clearPendingActions()
```

For full cleanup on logout or GDPR erasure, use `reset(clearStorage = true)`, which also wipes the SharedPreferences session ID, trails, and persisted event queue:

```kotlin
SearchCollector.reset(clearStorage = true)
```

## Configuration

```kotlin
SearchCollectorConfig(
    endpoint = "https://your-sqs-endpoint",   // required
    channel = "de",                            // required
    context = applicationContext,              // required

    queueSettings = QueueSettings(
        batchIntervalMs = 5_000L,              // auto-flush interval (default: 5s)
        maxBatchSize = 10,                     // max events per batch (default: 10)
    ),

    logLevel = LogLevel.ERROR,                 // DEBUG | INFO | WARN | ERROR | SILENT
    logger = myCustomLogger,                   // custom Logger implementation (e.g. Timber)

    debugRouting = DebugRoutingSettings(
        enabled = true,                        // true: always debug endpoint, false: always prod
        debugEndpoint = "https://debug-url",   // optional: explicit debug endpoint URL
    ),

    bufferedEventsTimestamp = BufferedEventsTimestamp.ORIGINAL,  // ORIGINAL | REPLAY

    sessionLifetimeMs = 48 * 60 * 60 * 1000L, // session expiry in ms (default: 48h)

    overrides = DependencyOverrides(           // all components are swappable
        transport = myTransport,
        sessionStore = mySessionStore,
        trailStore = myTrailStore,
        eventQueue = myEventQueue,
        contextProvider = myContextProvider,
        timestampProvider = myTimestampProvider,
    ),
)
```

## Custom Implementations

### Custom Transport (e.g. OkHttp)

```kotlin
class OkHttpTransport(private val client: OkHttpClient) : Transport {
    private val json = Json { encodeDefaults = true }

    override suspend fun send(events: List<SearchCollectorEvent>) {
        val body = json.encodeToString(events).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://your-endpoint").post(body).build()
        withContext(Dispatchers.IO) { client.newCall(request).execute().close() }
    }
}
```

### Custom Logger (e.g. Timber)

```kotlin
val timberLogger = object : Logger {
    override fun debug(msg: String, data: Any?) = Timber.d("$msg | $data")
    override fun info(msg: String, data: Any?) = Timber.i("$msg | $data")
    override fun warn(msg: String, data: Any?) = Timber.w("$msg | $data")
    override fun error(msg: String, data: Any?) = Timber.e("$msg | $data")
}
```

### Persistent Event Queue

For maximum delivery guarantees, use `SharedPreferencesEventQueue` instead of the default in-memory queue:

```kotlin
SearchCollectorConfig(
    ...,
    overrides = DependencyOverrides(
        eventQueue = SharedPreferencesEventQueue(context, maxBatchSize = 10)
    )
)
```

## Trail System

Trails link product IDs to the search query that led to them, enabling attribution for basket and checkout events.

```
trackProductClick("prod-123", position=0, keywords="jeans")
  → registers trail: prod-123 → "$s=jeans/"

trackBasket("prod-123", price=49.99)
  → resolves trail: query = "$s=jeans/"
  → sends basket event with the correct query
```

Trails have a TTL of 48 hours and are stored in SharedPreferences by default (survive app restarts).

**Variant selection on the PDP:**

```kotlin
// User switches from prod-123 (blue) to prod-456 (red) — same search query
SearchCollector.copyTrail(fromProductId = "prod-123", toProductId = "prod-456")
SearchCollector.trackBasket("prod-456", price = 54.99)
```

## Architecture

```
SearchCollector (object)          ← Public singleton API
    └── SearchCollectorCore       ← Orchestration: batching, flush, timer
            ├── Transport         ← Network (HttpGetTransport / ShSqsTransport)
            ├── SessionStore      ← Session ID (SharedPreferences / InMemory)
            ├── TrailStore        ← Product→query mapping (SharedPreferences / InMemory)
            ├── EventQueue        ← Event buffer (InMemory / SharedPreferences)
            ├── ContextProvider   ← URL, user agent, language (Android / Custom)
            └── TimestampProvider ← Timestamps
```

All components are interfaces and fully swappable via `DependencyOverrides`.

## Technical Constraints

- **10 KB request size limit** — imposed by load balancer architecture; enforced automatically
- **10 events per batch** — recommended maximum
- **24-hour event buffering** — older events are pruned automatically
- **48-hour session lifetime** — sessions expire after 48 hours of inactivity
