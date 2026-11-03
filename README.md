# Shield

**Ein Knopf → Handy geschützt.** Shield ist eine Open-Source-Sicherheits-App für Android (GPL-3.0):
DNS-Blocker gegen Werbung, Tracker, Malware und Phishing, eine App-Firewall, ein WireGuard-VPN
und Sicherheits-Checks – in **einer** App, mit einem ruhigen Dashboard und einem nachvollziehbaren Protokoll.

> Kein Schutz ist lückenlos. Shield sagt deshalb nie „100 % sicher“, sondern zeigt konkret,
> **welche** Schutzschichten aktiv und geprüft sind. Was Shield nicht abdeckt, steht in [docs/SECURITY.md](docs/SECURITY.md).

## Funktionen

| Bereich | Was Shield macht |
|---|---|
| DNS-Blocker | Systemweiter Filter mit AdGuard DNS Filter, EasyPrivacy, URLhaus (Malware) und Phishing-Listen. Automatisches Update alle 24 h (optional nur im WLAN). |
| Verschlüsseltes DNS | Erlaubte Anfragen gehen per DNS-over-HTTPS an Quad9, Cloudflare, Mullvad, AdGuard oder einen eigenen Server – nie unverschlüsselt über Port 53. |
| Eigene Regeln | Block- und Erlaubt-Liste mit Platzhaltern (`*.example.com`). Im Protokoll ein Tipp → „Blockieren“ / „Erlauben“. |
| App-Firewall | Pro App: erlaubt / nur WLAN / nur Mobilfunk / blockiert. Im Modus „Nur DNS“ über DNS, im vollen Tunnel als echte Paket-Firewall. |
| Tracker-Radar | Anfragen und Blockierungen pro App, Top-Domains, Rangliste „Wer telefoniert am meisten nach Hause?“. |
| WireGuard-VPN | Import per `.conf`, QR-Code oder Einfügen. Mehrere Profile (z. B. Proton, eigener Server). Kill-Switch. |
| Auto-VPN | Heim-WLAN → nur Blocker, offene/unbekannte WLANs → VPN, Mobilfunk → wählbar. |
| Checks | Live-Selbsttest (alle 15 Min + auf Knopfdruck), Leak-Test (IP, Land, DNS-, IPv6-Leak), WLAN-, Geräte- und Berechtigungs-Check, Sicherheits-Score 0–100. |
| Extras | Widget, Quick-Settings-Kachel, Panik-Knopf, verschlüsseltes Backup, Homelab-Kacheln, Wake-on-LAN, Nachtmodus, Datenspar-Modus, lokaler Wochenbericht. |
| Datenschutz | Alles lokal (Room/DataStore). Keine Analytics, kein Firebase, keine Konten. Aufbewahrung einstellbar, „Kein Verlauf speichern“. |

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

### Windows-Installer (`Shield-Setup-<version>.exe`)
Shield ist eine Android-App und läuft **nicht** unter Windows. Der Setup-Installer ist ein Helfer für den PC:
er legt die APK und die Android-Platform-Tools (ADB) ab und installiert Shield per USB auf ein angeschlossenes Handy
(USB-Debugging muss am Handy an sein). Danach ist er nicht mehr nötig.

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
installer/  Windows-Installer (NSIS)
```

Mehr: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/SECURITY.md](docs/SECURITY.md) · [docs/STATUS.md](docs/STATUS.md) · [CHANGELOG.md](CHANGELOG.md) · [SPEC.md](SPEC.md)

## Lizenz

GPL-3.0-or-later, siehe [LICENSE](LICENSE). Verwendete Komponenten: wireguard-go (MIT), gVisor (Apache-2.0),
OkHttp (Apache-2.0), AndroidX/Jetpack (Apache-2.0), Vico (Apache-2.0), ZXing Android Embedded (Apache-2.0).
