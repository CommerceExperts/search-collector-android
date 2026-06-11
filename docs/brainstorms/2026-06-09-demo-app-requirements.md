---
date: 2026-06-09
topic: demo-app
---

# Demo App — SDK Integration Test

## Summary

Ein neues `:demo-app` Gradle-Modul mit einer mehrstufigen Jetpack Compose UI, die einen realistischen Such-bis-Checkout-Flow simuliert. 10 SDK-Event-Typen werden abgedeckt. Eine Recording-Schicht fängt die decodierten Events ab ohne sie an SQS zu senden, und exportiert sie als JSON-Datei zur externen Verifizierung.

---

## Problem Frame

Die vorhandenen Unit-Tests laufen auf In-Memory-Implementierungen unter Robolectric und verifizieren keine echte Android-Laufzeit. Es fehlt ein Weg, das SDK im Emulator unter realistischen Bedingungen zu exercisen — also mit echten Screen-Übergängen, echter Eingabe, echter SharedPreferences-Persistenz und dem vollständigen Event-Lifecycle inkl. Trail-Attribution. Die Demo-App schließt diese Lücke als interaktive Sandbox.

---

## Key Decisions

- **Keine SQS-Verbindung.** Die App sendet nichts an das Netzwerk. Stattdessen ersetzt eine `RecordingTransport`-Implementierung den echten Transport via `DependencyOverrides`. Das hält die App deterministisch und sicher für die Entwicklung.

- **Decoded Events statt Wire-Format.** Die JSON-Ausgabe enthält deserialisierte `SearchCollectorEvent`-Objekte — lesbar und direkt verifizierbar. Das Base64-kodierte Wire-Format ist für manuelle Überprüfung zu opak.

- **Flow-Reihenfolge erzwingt Trail-Korrektheit.** `trackProductClick` muss vor `trackBasket`/`trackCheckout` kommen, damit die Trail-Attribution ein befülltes `query`-Feld produziert. Die mehrstufige Navigation macht das von Natur aus unmöglich zu umgehen.

- **`trackRedirect`, `registerTrail`, `copyTrail` nicht implementiert.** Diese Events passen nicht in den Hauptflow und werden für einen späteren Side-Path-Screen zurückgestellt.

---

## Actors

- A1. Entwickler — navigiert manuell durch den simulierten Flow im Emulator, löst Events aus, exportiert die JSON-Datei.

---

## Requirements

**App-Modul**

- R1. Die App lebt in einem neuen `:demo-app` Gradle-Modul im bestehenden Repo und bindet `:library` via `project(':library')` ein.
- R2. Das Modul enthält eine einzelne Activity als Entry-Point; die Navigation zwischen Screens erfolgt innerhalb dieser Activity.

**Recording-Schicht**

- R3. Eine `RecordingTransport`-Implementierung ersetzt den echten Transport über `SearchCollectorConfig.overrides.transport`. Sie speichert alle empfangenen Batches im Speicher ohne Netzwerkzugriff.
- R4. Die Recording-Schicht hält die Events als deserialisierte `SearchCollectorEvent`-Objekte vor, nicht als kodierte URL-Strings.
- R5. Ein "JSON exportieren"-Button serialisiert alle aufgezeichneten Events als JSON-Array und schreibt die Datei in den öffentlichen Downloads-Ordner des Geräts (`Downloads/searchhub-events-<timestamp>.json`).

**Simulierter Flow — Screens**

- R6. **Screen 1 – Suche:** Ein Textfeld feuert `trackInstantSearch(keywords)` bei jeder Zeicheneingabe (debounced, ~300 ms). Darunter erscheinen zwei Fake-Autocomplete-Vorschläge; Tippen auf einen Textvorschlag feuert `trackSuggestClick(keywords, prefix, position)`, Tippen auf einen Produktvorschlag feuert `trackSuggestProductClick(keywords, prefix, position, productId)`. Ein "Suchen"-Button feuert `trackFiredSearch(keywords)` und navigiert zu Screen 2.
- R7. **Screen 2 – Ergebnisse:** Beim Erscheinen des Screens werden automatisch `trackSearch(keywords, count = 42)` und `trackImpression(products)` mit 3 Fake-Produkten gefeuert. Jedes Produkt ist antippbar und feuert `trackProductClick(productId, position, keywords)`, bevor zu Screen 3 navigiert wird.
- R8. **Screen 3 – Produktdetail:** Beim Erscheinen werden 2 verwandte Fake-Produkte angezeigt; Antippen eines davon feuert `trackAssociatedProductClick(productId, position, keywords)`. Ein "In den Warenkorb"-Button feuert `trackBasket(productId, price)` und navigiert zu Screen 4.
- R9. **Screen 4 – Checkout:** Ein "Jetzt kaufen"-Button feuert `trackCheckout(products)` mit einem `CheckoutProduct`-Eintrag (quantity = 1).

**Session-Init**

- R10. Beim App-Start (Application `onCreate` oder erster Screen-Aufruf) wird `SearchCollector.initialize()` aufgerufen, das den `browser`-Event feuert.

**Export & Verifizierung**

- R11. Ein persistenter FAB oder eine Top-Bar-Action "JSON speichern" ist auf allen Screens erreichbar und löst den Export (R5) aus.
- R12. Nach erfolgreichem Export zeigt die App einen Toast mit dem vollständigen Dateipfad.

---

## Key Flows

- F1. Vollständiger Happy Path
  - **Trigger:** Entwickler startet die App im Emulator.
  - **Steps:** App-Start → `browser`-Event | Screen 1: Tippen → `instant-search`-Events; Suggest-Tap → `suggest-click` oder `suggest-product-click`; Suchen → `fired-search` | Screen 2: auto `search` + `impression`; Produkt antippen → `product-click` | Screen 3: verwandtes Produkt antippen → `associated-product-click`; Warenkorb → `basket` | Screen 4: Kaufen → `checkout` | FAB: JSON speichern → Datei im Downloads-Ordner.
  - **Outcome:** JSON-Datei enthält alle 10+ Event-Einträge mit korrekter Trail-Attribution in `basket`/`checkout`.

- F2. Basket ohne vorherigen Product-Click
  - **Trigger:** Entwickler navigiert direkt zu Screen 3 (z.B. über Back-Navigation nach einem vorherigen Durchlauf).
  - **Outcome:** `trackBasket` produziert ein `basket`-Event mit leerem `query`-Feld. Die App verhindert dies durch die sequenzielle Screen-Navigation — Screen 3 ist nur über Screen 2 erreichbar.

---

## Acceptance Examples

- AE1. **Trail-Attribution korrekt**
  - **Covers:** R7, R8, R9
  - **Given:** Entwickler durchläuft den vollständigen Flow mit keywords = "jeans" und productId = "prod-1".
  - **When:** JSON-Export ausgelöst.
  - **Then:** Das `basket`-Event im JSON enthält `"query": "$s=jeans/"` (oder das äquivalente Trail-Format) und `"id": "prod-1"`.

- AE2. **Alle Event-Typen im Export vorhanden**
  - **Covers:** R6–R10
  - **Given:** Vollständiger Happy Path durchgeführt.
  - **When:** JSON-Export ausgelöst.
  - **Then:** Die JSON-Datei enthält mindestens je ein Event der Typen: `browser`, `instant-search`, `fired-search`, `suggest-search`, `suggest-product-click`, `search`, `impression`, `product`, `associated-product`, `basket`, `checkout`.

- AE3. **Export-Datei auffindbar**
  - **Covers:** R5, R12
  - **Given:** Export ausgelöst.
  - **When:** Toast erscheint.
  - **Then:** Die angezeigte Datei ist über den System-Dateimanager oder `adb pull` am angezeigten Pfad abrufbar.

---

## Scope Boundaries

**Deferred for later**

- `trackRedirect`, `registerTrail`, `copyTrail` — geplant als Side-Path-Screen in einer Folgeversion.
- WorkManager-Integration (`:library-workmanager`) — nicht relevant für den manuellen Test-Flow.
- UI-Polish / Design — funktionale Oberfläche reicht aus.

**Outside this product's identity**

- Echte SQS-Verbindung — die App ist eine Entwicklungs-Sandbox, kein Produktions-Client.
- Automatisierter Test-Runner — Ziel ist manuelles, exploratives Testen im Emulator.

---

## Dependencies / Assumptions

- Jetpack Compose und Navigation-Compose sind verfügbar (werden als `implementation`-Abhängigkeit zum `:demo-app`-Modul hinzugefügt).
- Schreibzugriff auf den Downloads-Ordner erfordert `WRITE_EXTERNAL_STORAGE` auf API < 29 bzw. `MediaStore`-API auf API ≥ 29. Das Modul zielt auf `minSdk` des Projekts.
- Fake-Produktdaten sind hartcodiert im Demo-App-Code — kein Backend nötig.
