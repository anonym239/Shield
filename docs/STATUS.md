# Status

Stand: Version 1.0.0 (2026-11-03). Alle Phasen 0–7 aus `SPEC.md` §8 sind implementiert.

## Fertig

| Phase | Inhalt | Tests |
|---|---|---|
| 0 | Gerüst, Theme, Navigation, Logging-Fassade, Room, CI | `RedactorTest` |
| 1 | DNS-Blocker Stufe A, Listen, DoH, Cache, eigene Regeln, Protokoll | `DnsCodecTest`, `IpPacketTest`, `DnsCacheTest`, `DnsResolverTest`, `BlocklistParserTest`, `FilterEngineTest` |
| 2 | Dashboard, Selbsttest 1–5, Widget, QS-Kachel | `ProtectionStatusTest` |
| 3 | App-Firewall (DNS-basiert), Tracker-Radar, Rangliste | `FirewallRulesTest` |
| 4 | Debug-Screen, Export, Aufbewahrung, Kein Verlauf, Backup | `BackupCryptoTest` |
| 5 | Voller Tunnel (Go-Kern), Paket-Firewall, WireGuard, Kill-Switch, Auto-VPN, Leak-Test, Selbsttest 6 | `WgConfigParserTest`, `AutoVpnPolicyTest`, Go: `doh_test.go` |
| 6 | WLAN-/Geräte-/Berechtigungs-Check, Phishing-Kategorie, Score, Panik-Knopf | `SecurityScoreTest`, `ProtectionStatusTest` |
| 7 | Homelab-Kacheln, Wake-on-LAN, Nachtmodus, Datenspar-Modus, Wochenbericht | `WakeOnLanTest` |

## Offen / nächste Schritte

- Instrumentierte Tests (VpnService-Start/Stopp, Room-DAOs) auf Emulator in CI (SPEC §7).
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
- **Windows-Installer:** installiert die Android-App per ADB; Shield selbst läuft nicht unter Windows.
