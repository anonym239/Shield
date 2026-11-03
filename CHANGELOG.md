# Changelog

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung nach [SemVer](https://semver.org/lang/de/).

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
- Release-Workflow: signierte APK und Windows-Installer (`Shield-Setup.exe`, installiert die APK per ADB).
