# Shield – Spezifikation

> Arbeitstitel. Open-Source-Sicherheits-App für Android: VPN, Werbe-/Tracker-Blocker, App-Firewall und Sicherheits-Checks in **einer** App, mit einem klaren Dashboard und einem nachvollziehbaren Protokoll.

## 1. Ziel

- **Ein Knopf → Handy geschützt.** Alles läuft automatisch, Einstellungen sind optional.
- **Sichtbare Sicherheit:** Das Dashboard zeigt auf einen Blick, ob wirklich alles aktiv ist, und das Protokoll beweist es.
- **Alles lokal:** Keine Telemetrie, keine Konten, keine Daten verlassen das Gerät (außer dem eigentlichen, getunnelten Traffic).
- **Open Source:** GPL-3.0, Veröffentlichung auf GitHub (später F-Droid).

Ehrlicher Grundsatz für Texte in der App: Es gibt keinen 100-%-Schutz. Die App sagt deshalb nie „100 % sicher“, sondern zeigt konkret, *welche* Schutzschichten aktiv und geprüft sind.

### Wichtige Android-Einschränkung
Android erlaubt **nur eine aktive VPN-App**. Deshalb laufen Blocker, Firewall und VPN alle über **einen** `VpnService` dieser App. Während Shield aktiv ist, kann die Proton-App nicht parallel laufen. Stattdessen wird Proton als WireGuard-Profil in Shield importiert (Proton-Konto → Downloads → WireGuard-Konfiguration).

## 2. Funktionen

Priorität: **P1** = MVP, **P2** = zweite Ausbaustufe, **P3** = später.

### 2.1 Schutz im Netz
| Funktion | Prio | Beschreibung |
|---|---|---|
| DNS-Blocker | P1 | Systemweiter Werbe-, Tracker- und Malware-Blocker über lokalen DNS-Filter. Listen: AdGuard DNS Filter, EasyPrivacy, URLhaus/Malware-Liste. Listen-Update automatisch alle 24 h (nur über WLAN, optional). |
| Verschlüsseltes DNS | P1 | Erlaubte Anfragen gehen per DNS-over-HTTPS an einen wählbaren Upstream (Quad9, Cloudflare, Mullvad, AdGuard, eigener). Nie unverschlüsselt über Port 53 ins Netz. |
| Eigene Regeln | P1 | Eigene Block- und Erlaubt-Liste (Domains, Wildcards). Ein Tipp im Protokoll → „Diese Domain blockieren/erlauben“. |
| WireGuard-VPN | P2 | Import von `.conf` oder QR-Code. Mehrere Profile (z. B. Proton NL, eigener Oracle-Server Zürich). |
| Auto-VPN | P2 | Regeln: In unbekannten/offenen WLANs → VPN an. Im Heim-WLAN (SSID-Liste) → nur Blocker. Im Mobilfunk → wählbar. |
| Kill-Switch | P2 | Kein Traffic, wenn das VPN-Profil aktiv sein soll, aber nicht verbunden ist. Zusätzlich Hinweis + Direktlink auf Android-Einstellung „Durchgehend aktives VPN“ und „Verbindungen ohne VPN blockieren“. |
| Phishing-Schutz | P2 | Zusätzliche Phishing-/Scam-Blocklisten, eigene Kategorie im Dashboard. |

### 2.2 Apps im Griff
| Funktion | Prio | Beschreibung |
|---|---|---|
| App-Firewall | P1 | Pro App: Internet erlaubt / nur WLAN / nur Mobilfunk / blockiert. Systemkritische Apps markiert. |
| Tracker-Radar | P1 | Pro App: Anzahl Anfragen, blockierte Anfragen, Top-Domains. Rangliste „Wer telefoniert am meisten nach Hause?“. |
| Berechtigungs-Check | P2 | Listet Apps mit Mikrofon, Kamera, Standort, Kontakte, SMS, Bedienungshilfen, Geräteadmin. Markiert ungewöhnliche Kombinationen. Ein Tipp → App-Einstellungen. |
| Nachtmodus | P3 | Zeitfenster, in dem nur freigegebene Apps ins Netz dürfen. |
| Datenspar-Modus | P3 | Hintergrund-Daten im Mobilfunk für ausgewählte Apps blockieren. |

### 2.3 Sicherheits-Checks
| Funktion | Prio | Beschreibung |
|---|---|---|
| Live-Selbsttest | P1 | Prüft regelmäßig und auf Knopfdruck: Tunnel aktiv, DNS-Filter antwortet, Test-Domain wird blockiert, Upstream ist verschlüsselt erreichbar, Blocklisten geladen und aktuell. |
| Leak-Test | P2 | Öffentliche IP und Land, DNS-Leak (welche Resolver sehen die Anfrage?), IPv6-Leak, WebRTC-Hinweis. |
| WLAN-Check | P2 | Verschlüsselung des WLANs (offen/WPA2/WPA3), Hinweis bei offenem Netz, Captive-Portal-Erkennung. |
| Geräte-Check | P2 | Android-Sicherheitspatch-Alter, Bildschirmsperre aktiv, Entwickleroptionen/USB-Debugging an, unbekannte Quellen erlaubt. |
| Leak-Check E-Mail | P3 | Optional per „Have I Been Pwned“-API (nur auf ausdrücklichen Tipp, klar als Online-Anfrage gekennzeichnet). |
| Sicherheits-Score | P2 | Score 0–100 aus allen Checks, mit den 3 wichtigsten Verbesserungen. |

### 2.4 Homelab (persönlich)
| Funktion | Prio | Beschreibung |
|---|---|---|
| Schnellzugriff | P3 | Kacheln für eigene Dienste (Jellyfin, Nextcloud, Dashboard) mit Erreichbarkeitsstatus. |
| Wake-on-LAN | P3 | Homeserver per Magic Packet einschalten (über Heimnetz oder VPN). |

### 2.5 Extras
| Funktion | Prio | Beschreibung |
|---|---|---|
| Widget | P1 | Startbildschirm-Widget: Status-Farbe, Schutz an/aus, heute blockiert. |
| Quick-Settings-Kachel | P1 | Schutz an/aus aus der Benachrichtigungsleiste. |
| Panik-Knopf | P2 | Sofort alle Verbindungen trennen (Tunnel bleibt, alles wird verworfen). |
| Wochenbericht | P3 | Lokale Benachrichtigung mit Zusammenfassung. |
| Backup/Export | P2 | Einstellungen, Regeln und Profile als verschlüsselte Datei exportieren/importieren. |

## 3. Design

**Stil:** Material 3 (Jetpack Compose), Dynamic Color optional, dunkles und helles Theme. Viel Weißraum, große Zahlen, wenig Text, keine Fachbegriffe auf der Startseite (Fachbegriffe erst in Details/Debug).

### 3.1 Statusfarben (überall gleich)
- **Grün – „Geschützt“:** alle aktivierten Schichten laufen und der letzte Selbsttest war erfolgreich.
- **Gelb – „Eingeschränkt“:** Schutz läuft, aber etwas fehlt (z. B. Listen veraltet, VPN getrennt aber Blocker aktiv, offenes WLAN ohne VPN).
- **Rot – „Ungeschützt“:** Tunnel aus oder Selbsttest fehlgeschlagen.
Farbe nie allein: immer zusätzlich Icon + Text.

### 3.2 Navigation (Bottom Bar, 4 Tabs)
1. **Übersicht** (Dashboard)
2. **Apps** (Firewall + Tracker-Radar)
3. **Protokoll**
4. **Einstellungen** (inkl. Debug)

### 3.3 Dashboard-Aufbau (von oben nach unten)
1. **Großer Status-Ring** mit Schild-Icon, Status-Text („Geschützt“) und darunter ein großer Ein/Aus-Schalter.
2. **Schutzschichten-Karten** (2×2-Raster), jede mit Icon, Status-Punkt und einer Zeile Detail:
   - Blocker – „2.341 heute blockiert“
   - VPN – „Zürich · verbunden seit 2 h“ / „Aus“
   - Firewall – „4 Apps gesperrt“
   - Netzwerk – „WLAN WPA3 · sicher“
3. **Selbsttest-Zeile:** „Zuletzt geprüft vor 3 Min · 6/6 Tests bestanden“ + Knopf „Jetzt prüfen“. Tipp öffnet die Testliste mit ✓/✗ pro Test.
4. **Diagramm:** Anfragen vs. blockiert, letzte 24 h (Balken pro Stunde).
5. **Top 5 Tracker-Apps** und **Top 5 blockierte Domains**.
6. **Empfehlungen** (max. 3), z. B. „Aktiviere ‚Verbindungen ohne VPN blockieren‘ in den Android-Einstellungen“.

### 3.4 Protokoll-Screen
- Live-Liste aller DNS-Anfragen: Uhrzeit, App-Icon + Name, Domain, Ergebnis (erlaubt/blockiert + welche Liste/Regel).
- Filter: Alle / Blockiert / Erlaubt / pro App, Suchfeld.
- Tipp auf Eintrag → Details + „Blockieren“ / „Erlauben“.
- Zweiter Tab **Ereignisse**: Sicherheitsereignisse (VPN verbunden/getrennt, Kill-Switch hat geblockt, Selbsttest bestanden/fehlgeschlagen, Listen aktualisiert, Netzwerkwechsel).

### 3.5 Debug-Screen (Einstellungen → Debug)
- Technischer Status: Tunnel-Interface, Adressen, Routen, MTU, aktiver DNS-Upstream, DoH-Latenz, Anzahl geladener Regeln, Speicher/CPU des Dienstes, Akkuverbrauch-Hinweis.
- Live-Log (Logcat der App) mit Levels VERBOSE/DEBUG/INFO/WARN/ERROR und Filter.
- Knöpfe: „Selbsttest ausführen“, „Blocklisten neu laden“, „Tunnel neu starten“, „Log exportieren“ (Textdatei über Teilen-Dialog), „Logs löschen“.
- Export enthält standardmäßig **keine** Domains/IPs aus dem Verlauf, nur wenn ausdrücklich angehakt.

## 4. Protokoll & Datenschutz

- Speicherung lokal in Room (SQLite). Optional mit SQLCipher verschlüsselt.
- Tabellen: `dns_query` (zeit, uid, app, domain, typ, ergebnis, regel, latenz), `security_event` (zeit, typ, schwere, nachricht, details-json), `app_stats` (aggregiert pro Tag).
- Aufbewahrung: Standard 7 Tage für Einzelanfragen, 90 Tage für Tagesstatistiken, Ereignisse 30 Tage. Einstellbar, inklusive „Kein Verlauf speichern“.
- Keine Analytics, keine Crash-Reporter von Drittanbietern. Netzwerkzugriffe der App selbst: nur Blocklisten-Downloads, DoH-Upstream, VPN-Endpunkt und vom Nutzer ausgelöste Checks; alle sind im Ereignis-Protokoll sichtbar.

## 5. Architektur

### 5.1 Stack
- **Sprache/UI:** Kotlin, Jetpack Compose, Material 3, Navigation Compose
- **Architektur:** MVVM + Clean-ish Layers (`ui` / `domain` / `data`), Kotlin Coroutines + Flow
- **DI:** Hilt
- **Datenbank:** Room (+ optional SQLCipher)
- **Einstellungen:** DataStore
- **Hintergrund:** WorkManager (Listen-Updates, Aufräumen, Wochenbericht)
- **Netzwerk:** OkHttp (DoH, Listen-Download)
- **Diagramme:** Vico (Compose-Charts)
- **Min SDK:** 29 (Android 10), Target: aktuellstes SDK (Gerät läuft Android 16)

### 5.2 Netzwerk-Kern – zwei Stufen

**Stufe A (P1): Nur DNS durch den Tunnel**
- `VpnService` mit TUN-Interface, das nur eine virtuelle DNS-Adresse routet (z. B. `10.111.222.3`, IPv6 `fd00::3`) und diese als System-DNS setzt.
- Die App liest UDP/53-Pakete aus dem TUN, parst die DNS-Anfrage, prüft gegen die Regeln:
  - blockiert → Antwort `0.0.0.0` / `::` bzw. NXDOMAIN
  - erlaubt → Weiterleitung per DoH an den Upstream (Socket über `protect()` am Tunnel vorbei), Antwort zurück ins TUN
- App-Zuordnung über `ConnectivityManager.getConnectionOwnerUid()`.
- Vorteil: einfach, stabil, akkuschonend. Firewall in Stufe A: über `addDisallowedApplication` bzw. eigene Routen nur eingeschränkt möglich → einfache Variante: blockierte Apps bekommen für alle DNS-Anfragen NXDOMAIN (Hinweis im UI, dass hartkodierte IPs damit nicht abgedeckt sind).

**Stufe B (P2): Voller Tunnel**
- Alle Routen (`0.0.0.0/0`, `::/0`) ins TUN. Ein User-Space-Netzwerkstack verarbeitet die Pakete: echte Firewall pro App (Pakete verwerfen), DNS-Filter, und Weiterleitung entweder direkt (geschützte Sockets) oder über WireGuard.
- Empfohlener Weg: Go-Kern via gomobile mit **wireguard-go** und **gVisor netstack**. Als Vorlage dient **firestack** (Netzwerk-Kern von RethinkDNS) – Lizenz vorher prüfen und einhalten.
- Kill-Switch: Ist ein VPN-Profil aktiv und nicht verbunden, werden alle Nicht-DNS-Pakete verworfen.

### 5.3 Module / Pakete
```
app/                       Compose-UI, Navigation, Widget, QS-Kachel
core/vpn/                  ShieldVpnService, TUN-Verwaltung, Lebenszyklus, Auto-Modus
core/dns/                  DNS-Parser, Filter-Engine, DoH-Client, Cache
core/filter/               Blocklisten laden/parsen (hosts, AdGuard-Syntax), Trie/Hash-Set, Regeln
core/firewall/             App-Regeln, UID-Auflösung
core/wireguard/            (P2) Profile, Import, Go-Bridge
core/checks/               Selbsttest, Leak-Test, WLAN-, Geräte-, Berechtigungs-Check, Score
data/                      Room, DAOs, Repositories, DataStore
logging/                   Logger-Fassade, Ereignisse, Export, Aufbewahrung
```

### 5.4 Filter-Engine
- Formate: hosts-Dateien, reine Domainlisten, AdGuard-Basis-Syntax (`||domain^`, `@@||domain^`).
- Datenstruktur: umgekehrter Domain-Trie oder Hash-Set mit Suffix-Prüfung; Ziel < 1 ms pro Lookup, < 50 MB RAM bei ~500k Regeln.
- Reihenfolge: eigene Erlaubt-Liste > eigene Blockliste > Abo-Listen > erlauben.
- Cache für Antworten (TTL beachten).

## 6. Selbsttest – konkrete Prüfungen
1. VpnService läuft und TUN-Interface ist aktiv
2. System-DNS zeigt auf die virtuelle Adresse
3. Test-Domain (eigene interne Testdomain) wird blockiert
4. Erlaubte Domain wird über DoH aufgelöst (Latenz messen)
5. Blocklisten geladen, Anzahl > 0, Alter < 48 h
6. (P2) VPN-Handshake jünger als 3 Min, öffentliche IP ≠ Heim-IP, kein DNS-/IPv6-Leak
Jeder Test erzeugt ein `security_event`; das Dashboard zeigt das Ergebnis.

## 7. Qualität
- Unit-Tests: DNS-Parser, Filter-Engine, Regelreihenfolge, Score-Berechnung.
- Instrumentierte Tests: VpnService-Start/Stopp, Room-DAOs.
- Lint + Detekt + ktlint, GitHub Actions CI (Build, Tests, Release-APK signiert).
- Kein Absturz des Dienstes darf „ungeschützt“ verschweigen: Absturz → Ereignis + rote Benachrichtigung.

## 8. Roadmap
- **Phase 0:** Projektgerüst, Theme, Navigation, Logging-Fassade, Room
- **Phase 1:** DNS-Blocker (Stufe A), Listen, DoH, Protokoll-Screen
- **Phase 2:** Dashboard komplett, Selbsttest, Widget, QS-Kachel
- **Phase 3:** App-Firewall + Tracker-Radar (DNS-basiert)
- **Phase 4:** Debug-Screen, Export, Aufbewahrung, Backup
- **Phase 5:** Voller Tunnel (Stufe B), WireGuard, Kill-Switch, Auto-VPN, Leak-Test
- **Phase 6:** Geräte-/WLAN-/Berechtigungs-Check, Score, Phishing-Listen
- **Phase 7:** Homelab-Kacheln, Wake-on-LAN, Nachtmodus, Wochenbericht
