---
title: "feat: Add :demo-app module for interactive SDK integration testing"
type: feat
status: active
date: 2026-06-09
origin: docs/brainstorms/2026-06-09-demo-app-requirements.md
---

# feat: Add :demo-app module for interactive SDK integration testing

## Summary

Neues `:demo-app` Gradle-Modul mit einer mehrstufigen Jetpack Compose UI, das alle 10 SDK-Event-Typen in einem simulierten Such-bis-Checkout-Flow auslöst. Eine `RecordingTransport`-Implementierung fängt die decodierten Events ab, ohne etwas ans Netzwerk zu senden. Ein persistenter FAB exportiert alle aufgezeichneten Events als JSON-Datei in den Downloads-Ordner des Geräts.

---

## Problem Frame

Die Unit-Tests laufen auf In-Memory-Implementierungen unter Robolectric und verifizieren keine echte Android-Laufzeit. Es fehlt ein Weg, das SDK im Emulator unter realistischen Bedingungen zu exercisen — mit echten Screen-Übergängen, echter SharedPreferences-Persistenz, und dem vollständigen Event-Lifecycle inkl. Trail-Attribution. Das Demo-Modul schließt diese Lücke als interaktive Entwicklungs-Sandbox (see origin: `docs/brainstorms/2026-06-09-demo-app-requirements.md`).

---

## Requirements

**App-Modul**

- R1. Die App lebt in einem neuen `:demo-app` Gradle-Modul und bindet `:library` via `project(':library')` ein.
- R2. Das Modul enthält eine einzelne Activity; Navigation zwischen Screens erfolgt innerhalb dieser Activity.

**Recording-Schicht**

- R3. `RecordingTransport` ersetzt den echten Transport über `SearchCollectorConfig.overrides.transport` und speichert Batches im Speicher ohne Netzwerkzugriff.
- R4. Events werden als deserialisierte `SearchCollectorEvent`-Objekte gespeichert, nicht als URL-Strings.
- R5. Ein "JSON exportieren"-Button schreibt alle aufgezeichneten Events als JSON-Array nach `Downloads/searchhub-events-<timestamp>.json`.

**Simulierter Flow**

- R6. Screen 1 – Suche: Textfeld mit debounced `trackInstantSearch` (~300 ms), zwei Fake-Vorschläge (`trackSuggestClick` / `trackSuggestProductClick`), "Suchen"-Button feuert `trackFiredSearch` und navigiert zu Screen 2.
- R7. Screen 2 – Ergebnisse: Beim ersten Erscheinen automatisch `trackSearch(keywords, 42)` und `trackImpression(3 Produkte)`; Produkt-Tap feuert `trackProductClick` und navigiert zu Screen 3.
- R8. Screen 3 – Produktdetail: 2 verwandte Produkte antippbar (`trackAssociatedProductClick`); "In den Warenkorb"-Button feuert `trackBasket` und navigiert zu Screen 4.
- R9. Screen 4 – Checkout: "Jetzt kaufen"-Button feuert `trackCheckout`.
- R10. Beim App-Start wird `SearchCollector.initialize()` aufgerufen (`browser`-Event).

**Export**

- R11. FAB "💾 JSON" ist auf allen Screens sichtbar und löst den Export aus. Ist die Event-Liste leer, zeigt der FAB-Handler einen Toast "Noch keine Events aufgezeichnet" und schreibt keine Datei. Schlägt der Schreibvorgang fehl (Permission-Fehler, I/O-Fehler), zeigt ein Toast die Fehlermeldung.
- R12. Nach erfolgreichem Export: Toast mit vollständigem Dateipfad.

---

## Key Technical Decisions

- **Jetpack Compose + Navigation-Compose als UI-Layer.** Keine bestehenden Views-Patterns im Repo, Compose ist der Standard-Toolkit für neuen Android-Code. Navigation-Compose passt zum linearen 4-Screen-Flow mit Route-Arguments.

- **`com.android.application` Plugin (nicht `library`).** Das Modul produziert eine installierbare APK — daher Application-Plugin, nicht Library. Muss als neues Plugin-Alias in `gradle/libs.versions.toml` ergänzt werden.

- **`RecordingTransport` akkumuliert `List<SearchCollectorEvent>` direkt.** Das `Transport`-Interface empfängt bereits deserialisierte `SearchCollectorEvent`-Objekte — kein URL-Decoding nötig. Die akkumulierte Liste ist der direkte Input für den JSON-Export.

- **Thread-Safety in `RecordingTransport` via synchronisierte Liste.** `send()` wird aus einem Coroutine-Kontext aufgerufen; die interne Event-Liste muss thread-sicher sein.

- **Compose BOM für konsistente Versionen.** `androidx.compose:compose-bom` verwaltet alle Compose-Abhängigkeits-Versionen gemeinsam. Versions-Einträge kommen in `gradle/libs.versions.toml`.

- **`LaunchedEffect(Unit)` für auto-gefeuerte Screen-Events.** Events die beim Erscheinen eines Screens feuern sollen (Screen 2: `trackSearch` + `trackImpression`) werden mit `LaunchedEffect(Unit)` ausgelöst — feuert genau einmal pro Screen-Instanz, nicht auf Recompositionen.

- **`LaunchedEffect(query)` für debounced Instant-Search.** Bei jeder Query-Änderung bricht der vorige Effect ab; `delay(300L)` vor `trackInstantSearch` ergibt das Debounce-Verhalten ohne externen Dispatcher.

- **`keywords` und `productId` als NavArgs zwischen Screens.** Einfachste Möglichkeit: Strings als Routen-Parameter übergeben (`"results/{keywords}"`, `"detail/{productId}/{keywords}"`). Kein ViewModel nötig für dieses Demo.

- **`DemoApplication` hält `RecordingTransport`-Referenz.** Screens und FAB greifen via `(context.applicationContext as DemoApplication).recordingTransport` auf die Instanz zu. Einfacher als DI-Framework.

- **MediaStore-API für Downloads-Export (API ≥ 29), Legacy-Fallback für API 21-28.** `MediaStore.Downloads.EXTERNAL_CONTENT_URI` + `ContentValues` auf API ≥ 29; `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` mit `WRITE_EXTERNAL_STORAGE`-Permission auf API 21-28.

- **`kotlinx.serialization` für JSON-Export.**  `SearchCollectorEvent` ist bereits `@Serializable` mit korrektem `@JsonClassDiscriminator("type")`. Die `Json`-Instanz aus dem Library-Code kann im Demo-Modul neu instanziiert werden (`Json { prettyPrint = true }`).

---

## High-Level Technical Design

### Event-Flow und Transport-Interception

```mermaid
flowchart TB
  App["DemoApplication.onCreate()"] -->|configure + initialize| SDK["SearchCollector (singleton)"]
  SDK -->|DependencyOverrides.transport| RT["RecordingTransport"]

  S1["Screen 1: Suche"] -->|trackInstantSearch, trackSuggestClick/ProductClick, trackFiredSearch| SDK
  S1 -->|navigate(keywords)| S2

  S2["Screen 2: Ergebnisse"] -->|LaunchedEffect: trackSearch + trackImpression| SDK
  S2 -->|trackProductClick → navigate(productId, keywords)| S3

  S3["Screen 3: Produktdetail"] -->|trackAssociatedProductClick| SDK
  S3 -->|trackBasket → navigate(productId, keywords)| S4

  S4["Screen 4: Checkout"] -->|trackCheckout| SDK

  SDK -->|"send(List<SearchCollectorEvent>)"| RT
  RT -->|append| EL[("Event-Liste (in memory)")]

  FAB["💾 FAB (alle Screens)"] -->|getEvents()| RT
  FAB -->|JsonExporter| File["Downloads/searchhub-events-*.json"]
```

### Trail-Attribution und Screenreihenfolge

`trackProductClick` (Screen 2) setzt den Trail im `TrailStore` für die gewählte `productId` (Trail-Key = `productId`). `trackBasket` (Screen 3) und `trackCheckout` (Screen 4) lösen den Trail via `productId` auf und füllen das `query`-Feld.

**Welcher Trail wird geprüft:** `trackAssociatedProductClick` (Screen 3, Tap auf verwandtes Produkt) registriert einen Trail auf `related.id`, **nicht** auf dem Haupt-`productId`. `trackBasket`/`trackCheckout` verwenden den Haupt-`productId` — also den Trail, der durch `trackProductClick` gesetzt wurde. Das `query`-Feld in `basket`/`checkout` spiegelt daher den Haupt-Such-Trail wider, unabhängig davon welche verwandten Produkte angetippt wurden.

**Navigations-Konvention:** Der lineare 4-Screen-Flow (Screen 1 → 2 → 3 → 4) stellt sicher, dass der Trail gesetzt ist, bevor `trackBasket`/`trackCheckout` feuern. Der NavHost erzwingt dies nicht technisch, aber die Route `detail/{productId}/{keywords}` ist nur über Screen 2's `onProductClick`-Callback erreichbar — ein direktes Anspringen von Screen 3 ist im Demo-Code nicht vorgesehen.

---

## Output Structure

```
demo-app/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/io/searchhub/demo/
        ├── DemoApplication.kt
        ├── MainActivity.kt
        ├── AppNavHost.kt
        ├── RecordingTransport.kt
        ├── FakeData.kt
        ├── JsonExporter.kt
        └── screens/
            ├── SearchScreen.kt
            ├── ResultsScreen.kt
            ├── ProductDetailScreen.kt
            └── CheckoutScreen.kt
```

---

## Implementation Units

### U1. Gradle-Modul-Setup

**Goal:** Neues `:demo-app` Android-Application-Modul anlegen und alle nötigen Abhängigkeiten im Versions-Catalog ergänzen.

**Requirements:** R1

**Dependencies:** keine

**Files:**
- `settings.gradle.kts` — `include(":demo-app")` ergänzen
- `gradle/libs.versions.toml` — Compose BOM, Navigation-Compose, Activity-Compose Versionen + Library-Aliases + `android-application` Plugin-Alias + `kotlin-compose` Plugin-Alias hinzufügen
- `demo-app/build.gradle.kts` — `com.android.application`, `kotlin.android`, `kotlin.serialization`, `kotlin.plugin.compose` Plugins; `buildFeatures { compose = true }` aktivieren; Abhängigkeiten: `project(":library")`, Compose BOM, `compose-ui`, `material3`, `navigation-compose`, `activity-compose`, `kotlinx-serialization-json`

**Approach:** Das `android-application`-Plugin-Alias fehlt noch im Catalog (nur `android-library` ist vorhanden). Mit **Kotlin 2.0.21** wird Compose über das separate Plugin `org.jetbrains.kotlin.plugin.compose` aktiviert — der alte `composeOptions { kotlinCompilerExtensionVersion }`-Block ist für Kotlin 2.0+ ein No-Op und darf **nicht** verwendet werden. Der Catalog-Alias lautet `kotlin-compose` mit `version.ref = "kotlin"` (gleiche Version wie Kotlin selbst). `buildFeatures { compose = true }` in `android {}` bleibt erforderlich. `kotlinx-serialization-json` ist bereits im Catalog und kann direkt verwendet werden.

**Patterns to follow:** `library/build.gradle.kts` als Vorbild für Plugin-Aliases, Namespace, compileSdk/minSdk-Referenzen aus dem Catalog.

**Test expectation:** none — Gradle-Konfiguration, kein Verhaltenscode.

**Verification:** `./gradlew :demo-app:assembleDebug` kompiliert ohne Fehler.

---

### U2. RecordingTransport und FakeData

**Goal:** `RecordingTransport` als thread-sichere `Transport`-Implementierung; `FakeData`-Objekt mit hartkodierten Testdaten.

**Requirements:** R3, R4

**Dependencies:** U1

**Files:**
- `demo-app/src/main/kotlin/io/searchhub/demo/RecordingTransport.kt`
- `demo-app/src/main/kotlin/io/searchhub/demo/FakeData.kt`

**Approach:**

`RecordingTransport` implementiert `io.searchhub.collector.interfaces.Transport`. `send(events)` hängt die übergebene Liste an eine intern verwaltete, synchronisierte Liste an. `getEvents()` gibt eine unveränderliche Kopie zurück; `clear()` setzt die Liste zurück.

`FakeData` ist ein Kotlin-Objekt mit:
- 3 Ergebnis-Produkte: `id`, `name`, `price`, `position` (für Screen 2)
- 2 verwandte Produkte: gleiche Struktur (für Screen 3)
- Fake-Suggest-Einträge: ein Text-Vorschlag (`"jeans jacke"`), ein Produkt-Vorschlag (`productId = "prod-suggest-1"`)
- Konstante `DEFAULT_CHANNEL = "demo-app"`, `FAKE_ENDPOINT = "https://demo.invalid/sqs"`

**Patterns to follow:** `library/src/main/kotlin/io/searchhub/collector/impl/transport/HttpGetTransport.kt` als Referenz für `Transport`-Implementierung.

**Test expectation:** none — Demo-Hilfscode.

**Verification:** Kompiliert; `RecordingTransport` ist als `overrides.transport` in `SearchCollectorConfig` einsetzbar (Compile-Time-Check).

---

### U3. App-Skeleton — Application, Activity, NavHost, FAB, JSON-Export

**Goal:** App-Entry-Point, SDK-Konfiguration, Compose-Navigation mit globalem FAB und JSON-Export-Logik.

**Requirements:** R2, R10, R11, R12

**Dependencies:** U1, U2

**Files:**
- `demo-app/src/main/AndroidManifest.xml` — `DemoApplication` als `android:name`; `MainActivity` als Launcher-Activity; `WRITE_EXTERNAL_STORAGE`-Permission (maxSdk=28)
- `demo-app/src/main/kotlin/io/searchhub/demo/DemoApplication.kt` — Application-Klasse, hält `RecordingTransport`-Instanz, konfiguriert `SearchCollector`, ruft `SearchCollector.initialize()` auf
- `demo-app/src/main/kotlin/io/searchhub/demo/MainActivity.kt` — setzt `setContent { AppNavHost(...) }` mit Compose
- `demo-app/src/main/kotlin/io/searchhub/demo/AppNavHost.kt` — `NavHost` mit `Scaffold`, FAB auf allen Screens; Routen: `search`, `results/{keywords}`, `detail/{productId}/{keywords}`, `checkout/{productId}/{keywords}`
- `demo-app/src/main/kotlin/io/searchhub/demo/JsonExporter.kt` — `suspend fun export(context, events): String` schreibt JSON-Datei auf `Dispatchers.IO`, gibt Pfad zurück

**Approach:**

`DemoApplication.onCreate()`: instanziiert `RecordingTransport`, ruft `SearchCollector.configure(...)` mit `DependencyOverrides(transport = recordingTransport)` auf, dann `SearchCollector.initialize()`. `endpoint` = `FakeData.FAKE_ENDPOINT`, `channel` = `FakeData.DEFAULT_CHANNEL`.

`AppNavHost` rendert einen `Scaffold` mit einem FAB ("💾 JSON"). FAB-Click-Handler: holt `RecordingTransport` via `(context.applicationContext as DemoApplication).recordingTransport`. Ist die Event-Liste leer, zeigt der Handler sofort einen Toast "Noch keine Events aufgezeichnet" und bricht ab. Andernfalls wird `JsonExporter.export()` via `rememberCoroutineScope().launch { ... }` aufgerufen (da `export` suspend ist und Datei-I/O auf `Dispatchers.IO` durchführt). Nach Abschluss zeigt ein `Toast` auf dem Main-Thread den zurückgegebenen Pfad; schlägt der Export fehl, zeigt ein Toast die Fehlermeldung.

`JsonExporter.export()`:
- API ≥ 29: `MediaStore.Downloads.EXTERNAL_CONTENT_URI` + `ContentValues`, schreibt via `ContentResolver.openOutputStream`
- API < 29: `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`, schreibt via `FileOutputStream`
- Dateiname: `"searchhub-events-${System.currentTimeMillis()}.json"`
- JSON-Serialisierung: `Json { prettyPrint = true }.encodeToString(events)` — `SearchCollectorEvent` ist bereits `@Serializable`
- Rückgabe: vollständiger Dateipfad-String

**Patterns to follow:** `SearchCollectorConfig` / `DependencyOverrides` aus `library/src/main/kotlin/io/searchhub/collector/model/Config.kt`.

**Test expectation:** none — App-Scaffolding und Infra-Code.

**Verification:** App startet im Emulator; FAB ist sichtbar; nach Button-Tap erscheint Toast (auch wenn Events-Liste noch leer ist).

---

### U4. SearchScreen (Screen 1)

**Goal:** Suche-Screen mit debounced Instant-Search, Fake-Autocomplete-Vorschlägen und Suchen-Button.

**Requirements:** R6

**Dependencies:** U3

**Files:**
- `demo-app/src/main/kotlin/io/searchhub/demo/screens/SearchScreen.kt`

**Approach:**

Composable-Parameter: `keywords: String` (initialer Wert, kann leer sein), `onSearch: (String) -> Unit` (navigiert zu Screen 2).

Lokaler State: `query` als `mutableStateOf(keywords)` — bearbeitbarer Text.

Debounce-Pattern:
```
// Directional guidance only:
LaunchedEffect(query) {
    if (query.isNotBlank()) {
        delay(300L)
        SearchCollector.trackInstantSearch(query)
    }
}
```

Fake-Vorschläge erscheinen nur wenn `query.length >= 2`:
- Text-Vorschlag (`FakeData.suggestText`): Tap → `SearchCollector.trackSuggestClick(keywords = FakeData.suggestText, prefix = query, position = 0)`, setzt `query = FakeData.suggestText`
- Produkt-Vorschlag (`FakeData.suggestProduct`): Tap → `SearchCollector.trackSuggestProductClick(keywords = FakeData.suggestText, prefix = query, position = 1, productId = FakeData.suggestProduct.id)`

"Suchen"-Button (enabled wenn `query.isNotBlank()`): `SearchCollector.trackFiredSearch(query)` → `onSearch(query)`.

**Test expectation:** none — Demo-UI.

**Verification:** Im Emulator: Tippen löst Logcat-Ausgabe für `instant-search` aus (nach ~300 ms); Suggest-Einträge erscheinen; "Suchen" navigiert zu Screen 2.

---

### U5. ResultsScreen (Screen 2)

**Goal:** Ergebnisse-Screen der beim Erscheinen automatisch `trackSearch` und `trackImpression` feuert und 3 antippbare Fake-Produkte zeigt.

**Requirements:** R7

**Dependencies:** U3

**Files:**
- `demo-app/src/main/kotlin/io/searchhub/demo/screens/ResultsScreen.kt`

**Approach:**

Composable-Parameter: `keywords: String`, `onProductClick: (productId: String) -> Unit`.

Auto-fire beim ersten Erscheinen:
```
// Directional guidance only:
LaunchedEffect(Unit) {
    SearchCollector.trackSearch(keywords, count = 42)
    SearchCollector.trackImpression(FakeData.products.map { ProductPosition(it.id, it.position) })
}
```

Produkt-Liste: 3 Items aus `FakeData.products`, jedes antippbar:
- Tap → `SearchCollector.trackProductClick(product.id, product.position, keywords)` → `onProductClick(product.id)`

**Test expectation:** none — Demo-UI.

**Verification:** Im Emulator: Screen 2 erscheint → Logcat zeigt `search` + `impression`-Events; Produkt-Tap navigiert zu Screen 3.

---

### U6. ProductDetailScreen (Screen 3)

**Goal:** Produktdetail-Screen mit antippbaren verwandten Produkten und "In den Warenkorb"-Button.

**Requirements:** R8

**Dependencies:** U3

**Files:**
- `demo-app/src/main/kotlin/io/searchhub/demo/screens/ProductDetailScreen.kt`

**Approach:**

Composable-Parameter: `productId: String`, `keywords: String`, `onBasket: () -> Unit`.

Zeigt das ausgewählte Produkt (Name, Preis aus `FakeData` per `id`).

2 verwandte Produkte aus `FakeData.relatedProducts`, jedes antippbar:
- Tap → `SearchCollector.trackAssociatedProductClick(related.id, related.position, keywords)`
- Kein Navigations-Trigger — Tap bleibt auf diesem Screen (nur Event-Auslösung).

"In den Warenkorb"-Button:
- `SearchCollector.trackBasket(productId, price)` — `price` aus `FakeData` per `productId`
- → `onBasket()`

**Test expectation:** none — Demo-UI.

**Verification:** Im Emulator: Verwandtes-Produkt-Tap → Logcat zeigt `associated-product`; Warenkorb-Button → `basket`-Event mit befülltem `query`-Feld (Trail-Check).

---

### U7. CheckoutScreen (Screen 4)

**Goal:** Checkout-Screen mit "Jetzt kaufen"-Button, der `trackCheckout` auslöst.

**Requirements:** R9

**Dependencies:** U3

**Files:**
- `demo-app/src/main/kotlin/io/searchhub/demo/screens/CheckoutScreen.kt`

**Approach:**

Composable-Parameter: `productId: String`, `keywords: String`.

Zeigt Bestell-Zusammenfassung (Produkt-Name, Preis aus `FakeData`).

"Jetzt kaufen"-Button:
- `SearchCollector.trackCheckout(listOf(CheckoutProduct(productId, price, quantity = 1)))`
- Nach dem Event-Fire: Button wird deaktiviert; ein Bestätigungstext "✓ Kauf simuliert!" erscheint in-place.
- Ein "Neuen Flow starten"-Button darunter navigiert via `navController.popBackStack("search", inclusive = false)` zurück zu Screen 1 **ohne** `RecordingTransport.clear()` aufzurufen — Events bleiben erhalten, damit der FAB-Export den vollständigen Durchlauf zeigt. Der Entwickler kann `clear()` manuell über einen separaten Reset-Button (optional, in DemoApplication oder Screen 1) auslösen.

**Test expectation:** none — Demo-UI.

**Verification:** Im Emulator: "Jetzt kaufen" → Logcat zeigt `checkout`-Event; anschließend FAB-Tap → JSON-Datei enthält alle Event-Typen inkl. `basket`/`checkout` mit befülltem `query`-Feld.

---

## Scope Boundaries

**Deferred for later**
- `trackRedirect`, `registerTrail`, `copyTrail` — geplant als Side-Path-Screen in einer Folgeversion.
- Smoke-Tests für das Demo-Modul — werden im Nachgang ergänzt.
- WorkManager-Integration.
- UI-Polish / Design.

**Outside this product's identity**
- Echte SQS-Verbindung.
- Automatisierter Test-Runner.

---

## Risks & Dependencies

- **Compose-Versionen müssen zum AGP passen.** AGP 8.7.3 (im Projekt) unterstützt Compose. Compose BOM 2024.x empfohlen; ältere BOMs können Compiler-Kompatibilitätsprobleme mit Kotlin 2.0.21 zeigen.
- **`WRITE_EXTERNAL_STORAGE` nur bis API 28, Runtime-Permission auf API 23-28 erforderlich.** Ab API 29 ist das Permission-Modell für Downloads umgestellt (kein Runtime-Grant nötig). Auf API 23-28 muss die App die Permission zur Laufzeit anfordern — das Manifest-Eintrag allein reicht nicht. `JsonExporter` muss explizit zwischen API-Levels branchen; auf API 23-28 ist vor dem Schreiben `ActivityCompat.requestPermissions` oder `registerForActivityResult(RequestPermission())` erforderlich, sonst schlägt der Export mit `SecurityException` fehl. Im Demo reicht ein vereinfachter Flow: Permission prüfen → bei Fehlen anfordern → bei Ablehnung Toast zeigen.
- **`SearchCollector` ist ein Singleton.** Mehrmaliges `configure()` im selben Prozess disposed den vorherigen Core. Für das Demo reicht einmaliges `configure()` in `DemoApplication.onCreate()` — aber bei Prozess-Wiederverwendung im Emulator können alte Events in der `RecordingTransport`-Liste verbleiben; `clear()` vor jedem Testlauf berücksichtigen.

---

## Sources / Research

- `library/src/main/kotlin/io/searchhub/collector/interfaces/Transport.kt` — Interface-Signatur für `RecordingTransport`
- `library/src/main/kotlin/io/searchhub/collector/model/Config.kt` — `DependencyOverrides`, `SearchCollectorConfig`
- `library/src/main/kotlin/io/searchhub/collector/SearchCollector.kt` — `configure()`, `initialize()`, alle `track*`-Methoden
- `library/src/main/kotlin/io/searchhub/collector/model/Event.kt` — `SearchCollectorEvent`-Hierarchie, alle Subtypen und Felder
- `library/src/main/kotlin/io/searchhub/collector/model/Types.kt` — `CheckoutProduct`, `ProductPosition`, `SearchAction`
- `gradle/libs.versions.toml` — bestehende Versionen und Plugin-Aliases; Compose-Einträge ergänzen
- `docs/solutions/runtime-errors/` — Serialisierungs-Lösung für `@JsonClassDiscriminator` (bereits in Library gelöst; relevant wenn Demo-App eigene `@Serializable`-Typen hinzufügt)
