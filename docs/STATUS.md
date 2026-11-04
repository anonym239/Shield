# Status

Stand: Version 1.2.0 (2026-11-04). Alle Phasen 0–7 aus `SPEC.md` §8 sind implementiert, dazu Shield für Windows,
Schutzstufen, automatischer Selbsttest mit Selbstheilung, Ruhemodus, optionaler KI-Assistent und die DNS-fähigen
Regeln der uBlock-Origin-Standardlisten (Tests: `UblockSyntaxTest`, `ProtectionLevelTest`, Go `TestUblockSyntax`,
`TestIncludes`, `TestOldSettingsGetUblockLists`).

Offen / Grenzen der uBlock-Origin-Integration: nur Domain-Regeln (keine kosmetischen, Skript- oder Pfad-Regeln);
YouTube-Werbung in der offiziellen App ist per DNS nicht blockierbar; `!#if`-Bedingungen werden ignoriert (alle
Zweige gelten).

## Fertig

| Phase | Inhalt | Tests |
|---|---|---|
| 0 | Gerüst, Theme, Navigation, Logging-Fassade, Room, CI | `RedactorTest` |
| 1 | DNS-Blocker Stufe A, Listen, DoH, Cache, eigene Regeln, Protokoll, CNAME-Cloaking-Schutz | `DnsCodecTest`, `IpPacketTest`, `DnsCacheTest`, `DnsResolverTest`, `CnameCloakingTest`, `BlocklistParserTest`, `FilterEngineTest`, `VpnEndToEndTest` |
| 2 | Dashboard, Selbsttest 1–5, Widget, QS-Kachel | `ProtectionStatusTest` |
| 3 | App-Firewall (DNS-basiert), Tracker-Radar, Rangliste | `FirewallRulesTest` |
| 4 | Debug-Screen, Export, Aufbewahrung, Kein Verlauf, Backup | `BackupCryptoTest` |
| 5 | Voller Tunnel (Go-Kern), Paket-Firewall, WireGuard, Kill-Switch, Auto-VPN, Leak-Test, Selbsttest 6 | `WgConfigParserTest`, `AutoVpnPolicyTest`, Go: `doh_test.go` |
| 6 | WLAN-/Geräte-/Berechtigungs-Check, Phishing-Kategorie, Score, Panik-Knopf | `SecurityScoreTest`, `ProtectionStatusTest` |
| 7 | Homelab-Kacheln, Wake-on-LAN, Nachtmodus, Datenspar-Modus, Wochenbericht | `WakeOnLanTest` |

## Geprüft (CI, bei jedem Push)

- `./gradlew assembleDebug testDebugUnitTest lintDebug` – grün.
- Go-Kern: `go vet` (android/arm64) + `go test`.
- **Ende-zu-Ende im Android-Emulator (API 34)**: echter `ShieldVpnService`, echte DNS-Auflösung des Systems:
  - Stufe A: eigene Blockregel greift, Selbsttest-Domain wird blockiert, erlaubte Domain wird per DoH aufgelöst,
    Selbsttest 1–4 bestanden, Ereignisse werden gespeichert.
  - Stufe B (Go-Kern): dasselbe plus HTTPS-Verbindung durch den Tunnel, DoH-Probe, Panik-Modus verwirft allen Verkehr
    und lässt sich wieder beenden.
  - Room-DAOs (Filter, Aufbewahrung, Aggregation).
- Windows-Installer wird zur Probe gebaut.
- **Shield für Windows**: Go-Unit-Tests (Filter, DNS-Codec, Resolver, WireGuard-Config, Selbstheilung, KI-Protokoll,
  API-Sicherheit) und ein Ende-zu-Ende-Test auf `windows-latest`: Schutz einschalten, eigene Regel blockiert über den
  Windows-Resolver, erlaubte Domain per DoH, Selbsttest, danach wird geprüft, ob der DNS wiederhergestellt ist.

Der Emulator-Test hat einen echten Fehler gefunden, der behoben ist: In Stufe A schlug `protect()` auf dem
noch unverbundenen DoH-Socket fehl, erlaubte Domains wurden nicht aufgelöst.

## Offen / nächste Schritte

- Windows: WireGuard und Kill-Switch werden in CI nicht mit einem echten Server getestet (nur Bau und Konfiguration).
- Windows: keine App-Firewall und keine Auswertung pro Programm (Windows bündelt DNS im Systemdienst).

- WireGuard-Verbindung wird in CI nicht Ende-zu-Ende getestet (kein Test-Server); getestet sind Parser, UAPI-Erzeugung und Kill-Switch-Logik.
- Detekt und ktlint laufen in CI nur informativ (noch nicht blockierend).
- SQLCipher-Verschlüsselung der Datenbank (SPEC §4 „optional“) ist nicht umgesetzt.
- Englische Übersetzung ist vorbereitet (`values-en`), aber nicht sprachlich geprüft.
- Screenshots für README und F-Droid-Metadaten.
- „Leak-Check E-Mail“ (Have I Been Pwned, P3) ist nicht umgesetzt.

## Bekannte Grenzen

- **Stufe A:** Firewall nur per DNS – hartkodierte IPs werden nicht erfasst (UI-Hinweis + `docs/SECURITY.md`).
- **Datenspar-Modus:** sperrt ausgewählte Apps im Mobilfunk komplett; Android verrät einer VPN-App nicht zuverlässig,
  ob eine App im Vordergrund oder Hintergrund ist.
- **App-Zuordnung in Stufe A:** DNS-Anfragen stellt der Android-Resolver; je nach Gerät erscheint als App teils „Android-DNS“.
- **WLAN-Name (SSID):** braucht die Standort-Berechtigung und eingeschalteten Standort (Android-Vorgabe).
- **Kill-Switch:** wirkt in Stufe B mit WireGuard; für vollständigen Schutz zusätzlich Android-Lockdown nutzen.
- **DNS-Leak-Erkennung:** Heuristik (Resolver im selben Netz/ASN wie die öffentliche IP = Leak).
- **Heim-IP:** wird beim ersten Leak-Test ohne VPN lokal gespeichert und für Test 6 genutzt.
- **Homelab-Kacheln:** nur HTTPS (Android blockiert unverschlüsseltes HTTP standardmäßig; bewusst nicht gelockert).
- **Release ohne R8:** das APK ist größer, verhält sich aber genau wie die getestete Debug-Version.
- **Windows-Installer:** installiert die Android-App per ADB; Shield selbst läuft nicht unter Windows.
