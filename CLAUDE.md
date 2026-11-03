# CLAUDE.md – Regeln für dieses Projekt

Lies zuerst `SPEC.md`. Sie ist die verbindliche Spezifikation.

## Projekt
Shield: Open-Source-Android-App (Kotlin, Jetpack Compose) mit DNS-Blocker, App-Firewall, WireGuard-VPN, Sicherheits-Checks, Dashboard und Protokoll. Lizenz GPL-3.0.

## Arbeitsweise
- Arbeite **phasenweise** nach der Roadmap in `SPEC.md` §8. Keine Funktionen aus späteren Phasen vorziehen.
- Vor jeder Phase: kurzen Plan zeigen (Dateien, Klassen, Tests) und auf mein OK warten.
- Nach jeder Phase: `./gradlew assembleDebug test lint` muss grün sein. Danach `CHANGELOG.md` und `docs/STATUS.md` (was fertig ist, was offen ist, bekannte Grenzen) aktualisieren und einen Commit mit aussagekräftiger Nachricht machen.
- Kleine, überprüfbare Schritte. Lieber eine Funktion vollständig als drei halb.
- Wenn etwas technisch nicht wie in der SPEC geht (Android-Einschränkung), sag es klar und schlag eine Alternative vor, statt still etwas anderes zu bauen.

## Code-Regeln
- Kotlin, Coroutines/Flow, Hilt, Room, DataStore, Material 3. Keine zusätzlichen Bibliotheken ohne Begründung.
- Pakete wie in `SPEC.md` §5.3.
- Kein Netzwerkzugriff der App außer: Blocklisten, DoH-Upstream, VPN-Endpunkt, vom Nutzer ausgelöste Checks. **Keine** Analytics, kein Firebase, keine Werbe-SDKs.
- Sockets für Upstream-Verbindungen immer mit `VpnService.protect()` schützen.
- Jeder sicherheitsrelevante Zustandswechsel erzeugt ein `security_event` über die Logging-Fassade (`logging/`). Nie `println`/`Log.d` direkt verwenden.
- Logs und Exporte enthalten keine Geheimnisse (WireGuard-Private-Keys, Tokens). Keys im Android Keystore bzw. EncryptedFile.
- Fehler nie verschlucken: Fehler → Log-Eintrag (ERROR) + passender UI-Status (gelb/rot).
- UI-Texte auf Deutsch über `strings.xml` (Englisch als zweite Sprache vorbereiten). Nie „100 % sicher“ schreiben.
- Barrierefreiheit: Status nie nur über Farbe, `contentDescription` für Icons, Mindest-Touchgröße 48 dp.

## Tests
- Unit-Tests für jede Logik-Klasse in `core/` (Parser, Filter, Regeln, Score).
- Bei Fehlerbehebung zuerst einen Test schreiben, der den Fehler zeigt.

## Doku
- `README.md`: was die App macht, Screenshots, Installation (APK), Bauanleitung.
- `docs/ARCHITECTURE.md`: Aufbau des Netzwerk-Kerns mit Diagramm (Mermaid).
- `docs/SECURITY.md`: Was die App schützt, was **nicht** (z. B. hartkodierte IPs in Stufe A, Android-Systemverkehr), wie man Sicherheitslücken meldet.
