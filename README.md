# Shield

**Ein Knopf → Handy und PC geschützt.** Shield ist eine Open-Source-Sicherheits-App für Android (GPL-3.0):
DNS-Blocker gegen Werbung, Tracker, Malware und Phishing, eine App-Firewall, ein WireGuard-VPN
und Sicherheits-Checks – in **einer** App, mit einem ruhigen Dashboard und einem nachvollziehbaren Protokoll.

> Kein Schutz ist lückenlos. Shield sagt deshalb nie „100 % sicher“, sondern zeigt konkret,
> **welche** Schutzschichten aktiv und geprüft sind. Was Shield nicht abdeckt, steht in [docs/SECURITY.md](docs/SECURITY.md).

## Funktionen

| Bereich | Was Shield macht |
|---|---|
| DNS-Blocker | Systemweiter Filter mit den Standardlisten von uBlock Origin (uBlock filters, Badware, Privacy, Quick fixes, Unbreak, EasyList, EasyPrivacy, Peter Lowe, URLhaus) plus EasyList Germany, AdGuard DNS Filter und Phishing-Listen. Automatisches Update alle 24 h (optional nur im WLAN). |
| Verschlüsseltes DNS | Erlaubte Anfragen gehen per DNS-over-HTTPS an Quad9, Cloudflare, Mullvad, AdGuard oder einen eigenen Server – nie unverschlüsselt über Port 53. |
| Eigene Regeln | Block- und Erlaubt-Liste mit Platzhaltern (`*.example.com`). Im Protokoll ein Tipp → „Blockieren“ / „Erlauben“. |
| App-Firewall | Pro App: erlaubt / nur WLAN / nur Mobilfunk / blockiert. Im Modus „Nur DNS“ über DNS, im vollen Tunnel als echte Paket-Firewall. |
| Tracker-Radar | Anfragen und Blockierungen pro App, Top-Domains, Rangliste „Wer telefoniert am meisten nach Hause?“. |
| WireGuard-VPN | Import per `.conf`, QR-Code oder Einfügen. Mehrere Profile (z. B. Proton, eigener Server). Kill-Switch. |
| Auto-VPN | Heim-WLAN → nur Blocker, offene/unbekannte WLANs → VPN, Mobilfunk → wählbar. |
| Checks | Live-Selbsttest (alle 15 Min + auf Knopfdruck), Leak-Test (IP, Land, DNS-, IPv6-Leak), WLAN-, Geräte- und Berechtigungs-Check, Sicherheits-Score 0–100. |
| Extras | Widget, Quick-Settings-Kachel, Panik-Knopf, verschlüsseltes Backup, Homelab-Kacheln, Wake-on-LAN, Nachtmodus, Datenspar-Modus, lokaler Wochenbericht. |
| Datenschutz | Alles lokal (Room/DataStore). Keine Analytics, kein Firebase, keine Konten. Aufbewahrung einstellbar, „Kein Verlauf speichern“. |

### Neu in 1.2
- **uBlock-Origin-Regeln in jeder Stufe**: Shield lädt die Listen, die uBlock Origin standardmäßig nutzt (inklusive aller `!#include`-Teile), und wendet jede Regel an, die über DNS umsetzbar ist. Das verringert Werbung in Apps, Spielen und Browsern. Seitenelement- und Skript-Regeln wirken nur im Browser.
- **„Werbefrei im Browser & YouTube“**: Anleitung mit Knöpfen für Firefox + uBlock Origin und NewPipe. Ehrlich: Die offizielle YouTube-App lässt sich per DNS nicht werbefrei machen.

### Neu in 1.1
- **Power-Knopf** statt Schalter; nach dem Einschalten prüft Shield automatisch alle Schichten. „Geschützt“ erscheint erst, wenn der Selbsttest bestanden ist.
- **Schutzstufen Standard / Streng / Maximum**: Streng blockiert zusätzlich App-Telemetrie (Firebase-Logging, Crashlytics, App-Measurement, AppsFlyer, Werbe-SDKs …). Maximum blockiert außerdem Bedrohungs-Feeds und fremde DNS-Server und erzwingt vollen Tunnel und Kill-Switch.
- **Selbstheilung**: Schlägt ein Test fehl, verbindet Shield neu oder lädt Listen neu (max. 3× pro Stunde).
- **Keine Benachrichtigungen** (Ruhemodus), z. B. für Geräte von Angehörigen.
- **KI-Assistent (optional)** mit eigenem Gemini-API-Key: tägliche Prüfung, nur anonyme Daten, nur sichere Aktionen.
- **Proton VPN in Shield**: geführte Einrichtung, das erste importierte Profil wird direkt verwendet.
- **Shield für Windows** (siehe unten).

## Shield für Windows

`Shield-Setup-<version>.exe` installiert die PC-Version:
- lokaler DNS-Filter (127.0.0.1) mit denselben Listen, Schutzstufen und eigenen Regeln wie am Handy
- verschlüsseltes DNS (DoH), CNAME-Cloaking-Schutz, Protokoll, 24-h-Diagramm
- **WireGuard/Proton-VPN direkt in Shield** mit Kill-Switch (Windows-Filterplattform, wie bei WireGuard für Windows)
- Selbsttest alle 15 Min + Selbstheilung, optional KI-Prüfung, „Mit Windows starten“
- beim Beenden, nach einem Absturz und beim Deinstallieren werden die DNS-Einstellungen wiederhergestellt; zusätzlich gibt es die Verknüpfung „Shield – DNS zurücksetzen“

Grenzen am PC: Windows bündelt DNS-Anfragen im Systemdienst, daher gibt es keine Auswertung pro Programm und keine App-Firewall. Shield braucht Administratorrechte. Die Android-App lässt sich im Installer optional per USB aufs Handy spielen.

## Screenshots

Screenshots folgen, sobald die App auf einem Gerät läuft (`docs/screenshots/`).
Aufbau des Dashboards (SPEC §3.3): großer Status-Ring mit Schalter → 2×2-Karten (Blocker, VPN, Firewall, Netzwerk)
→ Selbsttest-Zeile → 24-h-Diagramm → Top 5 Tracker-Apps und Domains → Score und die 3 wichtigsten Empfehlungen.

## Installation

### Android (APK)
1. Neueste Version unter [Releases](../../releases) laden: `Shield-<version>.apk`.
2. Auf dem Handy öffnen und „Installation aus unbekannten Quellen“ für den Browser/Dateimanager erlauben.
3. Shield öffnen, Schalter antippen und die VPN-Anfrage von Android bestätigen.
4. Empfohlen: Android-Einstellungen → Netzwerk → VPN → Shield → **Durchgehend aktives VPN** und **Verbindungen ohne VPN blockieren**.

### Windows (`Shield-Setup-<version>.exe`)
Installer ausführen (Administratorrechte nötig) → Shield starten → Power-Knopf klicken. Für Proton: VPN-Tab →
WireGuard-Datei für „Windows“ bei Proton erstellen und auswählen. Optional installiert der Installer zusätzlich die
Android-App per USB auf ein Handy.

> Android erlaubt nur **eine** aktive VPN-App. Während Shield läuft, kann z. B. die Proton-App nicht parallel laufen.
> Importiere Proton stattdessen als WireGuard-Profil (Proton-Konto → Downloads → WireGuard-Konfiguration).

## Bauen

Voraussetzungen: JDK 17, Android SDK (Platform 36), Android NDK, Go (Version siehe `gocore/go.mod`).

```bash
# 1. Go-Netzwerkkern (wireguard-go + gVisor netstack) als AAR bauen
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<version>
scripts/build-gocore.sh            # erzeugt app/libs/shieldcore.aar

# 2. App bauen, testen, prüfen
./gradlew assembleDebug test lint
./gradlew installDebug             # auf ein per USB verbundenes Handy
```

Die Debug-APK liegt danach in `app/build/outputs/apk/debug/`.

Windows-Version (auf Linux oder Windows, Go ≥ siehe `desktop/go.mod`):

```bash
cd desktop
go test ./...
go run github.com/tc-hib/go-winres@v0.3.3 make --in winres/winres.json --out cmd/shield/rsrc --arch amd64
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -ldflags "-H=windowsgui" -o Shield.exe ./cmd/shield
# wintun.dll (https://www.wintun.net) neben Shield.exe legen
```

Release-Builds entstehen in GitHub Actions (`.github/workflows/release.yml`) beim Push eines Tags `v*` oder wenn die Datei `VERSION` geändert wird.
Für eine stabile Signatur die Secrets `SHIELD_KEYSTORE_B64` (Base64 der `.jks`), `SHIELD_KEYSTORE_PASSWORD`,
`SHIELD_KEY_ALIAS` und `SHIELD_KEY_PASSWORD` im Repository hinterlegen. Ohne Secrets wird ein einmaliger Schlüssel erzeugt.

## Projektstruktur

```
app/        Compose-UI, Navigation, Widget, QS-Kachel  (dev.shield.ui)
core/vpn    ShieldVpnService, TUN, Lebenszyklus, Auto-VPN
core/dns    DNS-Parser, Filter-Pfad, DoH-Client, Cache
core/filter Blocklisten laden/parsen, Filter-Engine, Regeln
core/firewall App-Regeln, UID-Auflösung
core/wireguard Profile, Import, Go-Bridge
core/checks Selbsttest, Leak-Test, WLAN-/Geräte-/Berechtigungs-Check, Score
data/       Room, DAOs, Repositories, DataStore
logging/    Logger-Fassade, Ereignisse, Export
gocore/     Go-Netzwerkkern für den vollen Tunnel (gomobile)
desktop/    Shield für Windows (Go: DNS-Filter, DoH, WireGuard, WebView2-UI)
installer/  Windows-Installer (NSIS)
```

Mehr: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/SECURITY.md](docs/SECURITY.md) · [docs/STATUS.md](docs/STATUS.md) · [CHANGELOG.md](CHANGELOG.md) · [SPEC.md](SPEC.md)

## Lizenz

GPL-3.0-or-later, siehe [LICENSE](LICENSE). Verwendete Komponenten: wireguard-go (MIT), gVisor (Apache-2.0),
OkHttp (Apache-2.0), AndroidX/Jetpack (Apache-2.0), Vico (Apache-2.0), ZXing Android Embedded (Apache-2.0).
