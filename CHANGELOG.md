# Changelog

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung nach [SemVer](https://semver.org/lang/de/).

## [1.3.0] – 2026-11-04

### Hinzugefügt
- **Server-Übersicht für VPN** (Android und Windows): jeder Server als Karte mit Flagge, Name, Land und letzter
  Geschwindigkeit; antippen/klicken wechselt den Server sofort.
- **„Welcher Server ist das?“** öffnet sich nach jedem Import: Name eingeben (z. B. „Niederlande #1“), das Land wird
  dabei automatisch erkannt (oder aus 96 Ländern gewählt), Flagge wird angezeigt.
- **Geschwindigkeitstest** pro Server (Download, Upload, Ping) über speed.cloudflare.com – nur auf Knopfdruck, ca. 35 MB.
  Shield schaltet dafür auf den Server um und wartet auf die Verbindung.
- Name und Land werden beim Import aus Protons Server-Angabe in der Datei übernommen (`# NL-FREE#12` → „Niederlande #12
  (Free)“, Secure Core und US-Bundesstaaten werden richtig erkannt).

### Behoben
- Die VPN-Karte zeigt „Verbunden“ mit Flagge und Servername statt des Dateinamens (z. B. „Dokument“).
  Nichtssagende Dateinamen werden nie mehr als Profilname verwendet.
- Texte unter dem Power-Knopf und im VPN-Bereich sind mittig ausgerichtet.

### Hinweis
- Flaggen: Android nutzt die eingebauten Flaggen-Emojis; Windows bringt die Flaggen von *flag-icons* (MIT) mit, weil
  Windows keine Flaggen-Emojis darstellt. Es wird dafür nichts zur Laufzeit aus dem Internet geladen.

## [1.2.0] – 2026-11-04

### Hinzugefügt
- **uBlock-Origin-Regeln** in jeder Schutzstufe (Android und Windows): uBlock filters (mit allen `!#include`-Teilen),
  Badware, Privacy, Quick fixes, Unbreak, EasyList, EasyList Germany und Peter Lowe. Übernommen wird jede Regel, die
  ein DNS-Filter umsetzen kann (`||domain^`, `$important`, `$all`, `$doc`; ab „Streng“ auch `$third-party`).
  Optional: uBlock Annoyances und Cookie-Hinweise. Bestehende Installationen bekommen die Listen einmalig hinzu.
- **Proton VPN mit einem Knopf** („Mit Proton verbinden“): Android – Proton-Seite öffnet sich, nach „Herunterladen“
  in der Download-Meldung „Öffnen“ → Shield übernimmt das Profil, aktiviert es, schaltet den Schutz ein und prüft alles.
  Shield erscheint dafür beim Öffnen/Teilen von `.conf`-Dateien; eine kopierte Konfiguration wird beim Zurückkehren
  erkannt (Zwischenablage wird danach geleert). Windows – Shield wartet nach dem Klick bis zu 15 Minuten auf die neue
  Datei im Download-Ordner, übernimmt sie, verbindet und löscht die Datei (sie enthält den privaten Schlüssel).
- Seite **„Werbefrei im Browser & YouTube“** mit Knöpfen für Firefox + uBlock Origin und NewPipe, auf Windows eine
  Karte mit Links zu Firefox + uBlock Origin bzw. uBlock Origin Lite.

### Geändert
- Ein neu importiertes VPN-Profil wird sofort das aktive Profil.
- uBlock-Origin-Listen haben einen Ausweich-Server (GitHub), falls GitHub Pages kurz nicht antwortet.
- URLhaus nutzt jetzt den Feed von malware-filter (wie uBlock Origin); die alte Adresse antwortet mit HTTP 410.
- Adblock-Listen (EasyList, EasyPrivacy, uBlock) werden streng gelesen: einzelne Wörter gelten dort nicht als Domain.
- Listen-Ausnahmen mit `$doc` werden nie als komplette Domain-Freigabe übernommen.

### Grenzen
- YouTube-Werbung in der offiziellen App ist per DNS nicht blockierbar (gleiche Server wie die Videos).
- Shield kann die Daten der installierten Proton-App nicht lesen (Android-Sandbox, keine Proton-Schnittstelle) und
  meldet sich bewusst nicht mit dem Proton-Passwort an. Darum der Weg über den Download der WireGuard-Datei.

## [1.1.5] – 2026-11-04

### Behoben
- Kein doppelter Listen-Download mehr beim Start: Wartet eine zweite Anfrage hinter einem gerade beendeten Download
  derselben Listen, wird sie übersprungen (spart Datenvolumen und Speicher; Android).

## [1.1.4] – 2026-11-04

### Behoben
- Ein Wechsel der Schutzstufe oder der Listenauswahl wirkt sofort: eingebaute und bereits geladene Listen werden direkt
  übernommen, Downloads laufen getrennt im Hintergrund und blockieren das Umschalten nicht mehr (Android).

## [1.1.3] – 2026-11-04

### Behoben
- Ausweichserver greift schneller: ein hängender DoH-Hauptserver bekommt höchstens 2,5 s, dann antwortet der zweite
  verschlüsselte Server – bevor Android oder Windows die Anfrage aufgeben (Android, Windows).

## [1.1.2] – 2026-11-04

### Hinzugefügt
- Ausfallsicheres verschlüsseltes DNS: Ist der gewählte DoH-Server gestört, nutzt Shield automatisch einen zweiten
  verschlüsselten Server (Quad9 ↔ Cloudflare) und prüft den ersten nach 60 s erneut. Nie Rückfall auf unverschlüsseltes
  DNS; bei einem eigenen Server gibt es bewusst keinen Ausweichserver. Gilt für Android (Stufe A und B) und Windows.

## [1.1.1] – 2026-11-04

### Behoben
- Ein Wechsel der Schutzstufe während des ersten Listen-Downloads griff erst nach dem Download, und der laufende
  Download konnte die alte Listenauswahl wieder aktivieren (Android und Windows; vom Emulator-Test gefunden).
- Windows: PowerShell-Ausgabe wird robust gelesen; DNS-Setzen wird bei kurzzeitigen Fehlern wiederholt.

## [1.1.0] – 2026-11-04

### Hinzugefügt
- **Shield für Windows**: lokaler DNS-Filter mit DoH, Schutzstufen, eigene Regeln, Protokoll, WireGuard/Proton-VPN mit
  Kill-Switch (WFP), Selbsttest, Selbstheilung, KI-Prüfung, Autostart, WebView2-Oberfläche. Der Installer installiert
  Shield für Windows, die Android-App per USB ist optional.
- Android: Power-Ring statt Schalter, automatischer Selbsttest nach jedem Start („Geschützt“ erst nach bestandenem Test).
- Schutzstufen Standard / Streng / Maximum (eingebaute Telemetrie-Liste, strenge EasyPrivacy-Regeln, HaGeZi-Listen,
  DoT-Sperre und erzwungener voller Tunnel bei Maximum).
- Selbstheilung bei fehlgeschlagenem Selbsttest (max. 3× pro Stunde).
- Einstellung „Keine Benachrichtigungen“.
- Optionaler KI-Assistent mit eigenem Google-Gemini-Key (anonymisierte Daten, nur sichere Aktionen).
- Geführte Proton-Einrichtung; das erste importierte VPN-Profil wird sofort verwendet.

### Geändert
- VPN-Karte zeigt „Nicht eingerichtet“ statt einer Warnung, solange kein Profil eingerichtet ist.

## [1.0.1] – 2026-11-03

### Behoben
- Installation auf manchen Geräten (gemeldet: Samsung, Android 16) schlug mit „App nicht installiert“ fehl.
  APK jetzt mit Signaturschema v1+v2+v3 und RSA-2048/SHA-256 signiert, ohne unvollständigen 32-Bit-x86-Teil.

### Hinzugefügt
- Schlankes `Shield-<version>-arm64.apk` für ARM64-Handys (empfohlen) zusätzlich zum Universal-APK.

## [1.0.0] – 2026-11-03

Erste Version mit allen Phasen 0–7 aus `SPEC.md`.

### Hinzugefügt
- **Phase 0 – Gerüst:** Kotlin/Compose/Material 3, Hilt, Room, DataStore, helles/dunkles Theme mit Statusfarben,
  Bottom-Navigation (Übersicht, Apps, Protokoll, Einstellungen), Logging-Fassade (`ShieldLog`, `EventLogger`,
  Tabelle `security_event`), GitHub-Actions-CI.
- **Phase 1 – DNS-Blocker:** `ShieldVpnService` Stufe A, DNS-Parser, Filter-Engine (hosts, Domainlisten,
  AdGuard-Syntax), AdGuard DNS Filter, EasyPrivacy, URLhaus, DoH-Upstream (Quad9 Standard; Cloudflare, Mullvad,
  AdGuard, eigener), Cache mit TTL, eigene Regeln mit Platzhaltern, Tabelle `dns_query`, Protokoll mit Live-Liste,
  Filtern, Suche und „Blockieren/Erlauben“.
- **Phase 2 – Dashboard & Selbsttest:** Status-Ring, 2×2-Schutzkarten, Selbsttest-Zeile mit Testliste,
  24-h-Diagramm (Vico), Top-5-Listen, Empfehlungen, Selbsttest (Punkte 1–5) alle 15 Min, Widget, QS-Kachel.
- **Phase 3 – App-Firewall & Tracker-Radar:** Apps-Tab, Regel pro App (erlaubt/WLAN/Mobil/blockiert),
  Detailseite mit Top-Domains, Rangliste „Wer telefoniert am meisten nach Hause?“.
- **Phase 4 – Debug, Export, Aufbewahrung:** Debug-Screen, Log-Export (Standard ohne Domains/IPs),
  Aufbewahrung per WorkManager, „Kein Verlauf speichern“, verschlüsseltes Backup/Restore.
- **Phase 5 – Voller Tunnel & WireGuard:** Go-Kern (wireguard-go + gVisor netstack, gomobile), Paket-Firewall
  pro App, WireGuard-Profile (.conf, QR, Einfügen), Kill-Switch, Auto-VPN, Leak-Test, Selbsttest Punkt 6.
- **Phase 6 – Checks & Score:** WLAN-, Geräte- und Berechtigungs-Check, Phishing-Listen als eigene Kategorie,
  Sicherheits-Score 0–100 mit Top-3-Empfehlungen, Panik-Knopf.
- **Phase 7 – Extras:** Homelab-Kacheln mit Erreichbarkeit, Wake-on-LAN, Nachtmodus, Datenspar-Modus,
  lokaler Wochenbericht.
- Schutz vor CNAME-Cloaking; im vollen Tunnel nimmt die virtuelle DNS-Adresse nur Port 53 an.
- Ende-zu-Ende-Tests im Android-Emulator (Stufe A, Stufe B, Panik-Modus) und Room-DAO-Tests in CI.

### Behoben
- Stufe A: DoH-Socket wird vor `protect()` gebunden – vorher wurden erlaubte Domains nicht aufgelöst (vom E2E-Test gefunden).

### Hinzugefügt (Auslieferung)
- Release-Workflow: signierte APK und Windows-Installer (`Shield-Setup.exe`, installiert die APK per ADB).
