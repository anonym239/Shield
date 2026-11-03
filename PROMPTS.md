# Prompts für Claude Code

So gehst du vor:
1. Leeren Ordner `shield` anlegen und `SPEC.md`, `CLAUDE.md` und diese Datei hineinlegen.
2. Im Ordner `git init` ausführen und Claude Code starten (`claude`).
3. Die Prompts der Reihe nach einfügen. Nach jeder Phase die App auf dem Handy testen (APK aus `app/build/outputs/apk/debug/`), erst dann weiter.

Voraussetzung: Android Studio installiert (für SDK, Emulator und ADB). USB-Debugging am Handy an, um die App direkt zu installieren (`./gradlew installDebug`).

---

## Phase 0 – Gerüst
```
Lies SPEC.md und CLAUDE.md. Setze Phase 0 um: Android-Projekt (Kotlin, Compose, Material 3, Hilt, Room, DataStore) mit der Paketstruktur aus SPEC §5.3, hellem und dunklem Theme mit den Statusfarben aus §3.1, Bottom-Navigation mit den 4 Tabs aus §3.2 (Platzhalter-Screens), Logging-Fassade mit Room-Tabelle security_event und GitHub-Actions-CI. Zeig mir zuerst den Plan.
```

## Phase 1 – DNS-Blocker
```
Setze Phase 1 um: ShieldVpnService nach SPEC §5.2 Stufe A, DNS-Parser, Filter-Engine nach §5.4 mit AdGuard DNS Filter, EasyPrivacy und einer Malware-Liste, DoH-Upstream (Standard Quad9, wählbar), eigene Block-/Erlaubt-Regeln, Tabelle dns_query und der Protokoll-Screen aus §3.4 mit Live-Liste und Filtern. Unit-Tests für Parser und Filter. Plan zuerst.
```

## Phase 2 – Dashboard & Selbsttest
```
Setze Phase 2 um: Dashboard genau nach SPEC §3.3 (Status-Ring, 2x2-Schutzkarten, Selbsttest-Zeile, 24h-Diagramm mit Vico, Top-5-Listen, Empfehlungen), Selbsttest nach §6 (Punkte 1–5) alle 15 Minuten und auf Knopfdruck, Homescreen-Widget und Quick-Settings-Kachel. Das Design soll clean und ruhig sein, große Zahlen, wenig Text. Plan zuerst.
```

## Phase 3 – App-Firewall & Tracker-Radar
```
Setze Phase 3 um: Apps-Tab mit Liste aller installierten Apps (Icon, Name, Anfragen heute, blockiert heute), Firewall-Regel pro App (erlaubt / nur WLAN / nur Mobilfunk / blockiert) zunächst DNS-basiert wie in SPEC §5.2 Stufe A beschrieben, Detailseite pro App mit Top-Domains, Rangliste „Wer telefoniert am meisten nach Hause?“. Grenzen der DNS-basierten Firewall in der UI und in docs/SECURITY.md erklären. Plan zuerst.
```

## Phase 4 – Debug, Export, Aufbewahrung
```
Setze Phase 4 um: Debug-Screen nach SPEC §3.5, Log-Export über Teilen-Dialog (standardmäßig ohne Domains/IPs), Aufbewahrungsregeln aus §4 per WorkManager, Option „Kein Verlauf speichern“, verschlüsseltes Backup/Restore von Einstellungen und Regeln. Plan zuerst.
```

## Phase 5 – Voller Tunnel & WireGuard
```
Setze Phase 5 um: Stufe B aus SPEC §5.2 mit Go-Kern (wireguard-go + gVisor netstack über gomobile). Prüfe zuerst firestack (RethinkDNS) als Vorlage inklusive Lizenz und schlag mir vor, ob wir es einbinden, forken oder eigenen Code schreiben. Danach: echte Paket-Firewall pro App, WireGuard-Profile (Import per .conf und QR-Code), Kill-Switch, Auto-VPN-Regeln (Heim-WLAN-SSIDs, offene WLANs, Mobilfunk), Leak-Test (IP, Land, DNS-Leak, IPv6). Selbsttest um Punkt 6 aus §6 erweitern. Plan zuerst.
```

## Phase 6 – Checks & Score
```
Setze Phase 6 um: WLAN-Check, Geräte-Check und Berechtigungs-Check aus SPEC §2.3/§2.2, Phishing-Blocklisten als eigene Kategorie, Sicherheits-Score 0–100 mit den 3 wichtigsten Empfehlungen im Dashboard, Panik-Knopf. Plan zuerst.
```

## Phase 7 – Persönliche Extras
```
Setze Phase 7 um: Homelab-Kacheln mit Erreichbarkeitsstatus (eigene URLs eintragbar), Wake-on-LAN, Nachtmodus, Datenspar-Modus und lokaler Wochenbericht. Plan zuerst.
```

---

## Nützliche Zwischen-Prompts
- Fehler: `Die App stürzt beim Start des VPN ab. Hier ist der Log aus dem Debug-Screen: … Schreib zuerst einen Test, der den Fehler zeigt, dann behebe ihn.`
- Design: `Mach das Dashboard ruhiger: weniger Rahmen, mehr Abstand, Zahlen größer. Zeig mir vorher einen Screenshot aus dem Emulator.`
- Überprüfung: `Prüfe das ganze Projekt gegen SPEC.md und liste auf, was fehlt oder abweicht.`
- Sicherheit: `Mach ein Security-Review des Netzwerk-Kerns: Wo könnte Traffic am Filter vorbeigehen? Was davon können wir beheben?`
