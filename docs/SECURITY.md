# Sicherheit

Shield macht Schutz **sichtbar und überprüfbar** – aber kein Werkzeug schützt lückenlos.
Diese Seite beschreibt ehrlich, was Shield abdeckt und was nicht.

## Was Shield schützt

* **Werbung, Tracker, Malware, Phishing per DNS** – Anfragen an bekannte Domains werden lokal mit `0.0.0.0`/`::`
  (oder NXDOMAIN) beantwortet. Listen: AdGuard DNS Filter, EasyPrivacy, URLhaus, Phishing Army (+ optional malware-filter).
* **CNAME-Cloaking** – zeigt eine harmlos wirkende Subdomain per CNAME auf eine blockierte Tracker-Domain,
  wird die Antwort trotzdem blockiert (Stufe A und B).
* **Umgehung über den Resolver** – im vollen Tunnel wird jede Anfrage an Port 53 (egal welche IP) abgefangen;
  die virtuelle DNS-Adresse nimmt nur Port 53 an (kein DoT-Umweg über 853).
* **Mitlesen von DNS** – erlaubte Anfragen gehen ausschließlich per DNS-over-HTTPS an den gewählten Anbieter.
  Der Upstream wird über feste Bootstrap-IPs erreicht, es gibt nie eine Klartext-DNS-Anfrage ins Netz.
* **Apps, die nach Hause telefonieren** – Firewall pro App und Tracker-Radar.
* **Offene WLANs** – WireGuard-VPN, Auto-VPN und Kill-Switch.
* **Unbemerkte Ausfälle** – Selbsttest alle 15 Minuten, rote Benachrichtigung bei Absturz/Fehler, jedes Ereignis im Protokoll.
* **Geheimnisse** – WireGuard-Schlüssel liegen verschlüsselt (Android Keystore, AES-GCM) und erscheinen nie in Logs
  oder Exporten (`Redactor`). Backups sind mit PBKDF2 + AES-256-GCM verschlüsselt.

## Was Shield **nicht** schützt (bekannte Grenzen)

| Grenze | Erklärung | Was hilft |
|---|---|---|
| Hartkodierte IPs (Stufe A) | Im Modus „Nur DNS“ sperrt die Firewall Apps nur über DNS (NXDOMAIN). Apps, die feste IP-Adressen nutzen, erreichen ihr Ziel trotzdem. | Vollen Tunnel (Stufe B) einschalten: echte Paket-Firewall. |
| Eigenes DNS von Apps | Apps mit eigenem DoH/DoT (z. B. Browser mit „Sicheres DNS“) umgehen den Filter in Stufe A. In Stufe B wird Port 53 abgefangen, DoH/DoT aber nicht. | Im Browser „Sicheres DNS“ ausschalten oder auf Shield-Upstream lassen. |
| „Privates DNS“ von Android | Ein fest eingetragener Private-DNS-Server umgeht den Filter. Der Selbsttest erkennt das (Test 2). | Android-Einstellungen → Netzwerk → Privates DNS → Aus/Automatisch. |
| Android-Systemverkehr | Teile des Systems (z. B. Konnektivitäts-Checks, manche Google-Dienste vor dem VPN-Start, Verkehr beim Booten) laufen außerhalb des VPN. | „Durchgehend aktives VPN“ + „Verbindungen ohne VPN blockieren“ aktivieren. |
| Zeit vor dem VPN-Start / nach Absturz | Ohne „Verbindungen ohne VPN blockieren“ gibt es Datenverkehr, solange Shield nicht läuft. | Android-Lockdown-Modus (siehe oben). |
| Kill-Switch | Greift nur in Stufe B mit aktivem WireGuard-Profil und nur für Shield selbst; ohne Android-Lockdown kann das System den Tunnel umgehen, wenn Shield beendet wird. | Android-Lockdown-Modus. |
| Inhalte | Shield ist kein Virenscanner und prüft keine Dateien, Webseiten-Inhalte oder verschlüsselten Verkehr. | Updates, Play Protect, Vorsicht bei Links. |
| IPv6-Erweiterungsheader, IP-Fragmente | In Stufe A werden nur einfache UDP/53-Pakete beantwortet (Resolver weicht sonst auf andere Wege aus). | Stufe B. |
| YouTube-Werbung und Werbung vom selben Server | Werbung, die vom selben Server kommt wie der Inhalt (YouTube-Werbung über `googlevideo.com`, Werbung in Instagram/Facebook, Werbung in manchen Spielen), lässt sich per DNS nicht trennen. Ein Blocken würde den Inhalt mitblocken. | Im Browser das echte uBlock Origin nutzen (Firefox); für YouTube z. B. NewPipe. Siehe „Werbefrei im Browser & YouTube“ in der App. |
| uBlock-Origin-Regeln nur teilweise | Shield übernimmt aus den uBlock-Origin-Listen nur Regeln für ganze Domains. Seitenelement-Regeln (`##`), Skript-Regeln (`+js`), Pfad-Regeln und Typ-Regeln (`$script`, `$image`) kann ein DNS-Filter nicht umsetzen. Ausnahmen werden nur übernommen, wenn sie eine ganze Domain betreffen und keine Zusatzbedingungen haben. | uBlock Origin im Browser ergänzt Shield. |
| HTTPS-Inspection | Shield bricht absichtlich keinen verschlüsselten Verkehr auf. Apps vertrauen seit Android 7 keinen Nutzer-Zertifikaten, und eine solche Zwischenstelle wäre ein großes Sicherheitsrisiko. | – |
| WebRTC | Browser können lokale IPs über WebRTC verraten. | WebRTC im Browser einschränken. |
| Leak-Test | Nutzt externe Dienste (am.i.mullvad.net, bash.ws) und ist eine Momentaufnahme. | Regelmäßig wiederholen. |
| Root/kompromittiertes Gerät | Mit Root-Rechten kann Malware jede App-Schutzschicht aushebeln. | Kein Root, aktuelle Sicherheitspatches. |

## KI-Assistent (optional)

Nur wenn du selbst einen Gemini-API-Key einträgst. Gesendet wird eine anonyme Zusammenfassung (Testergebnisse,
Fehlerzähler, Einstellungen). Domains, IP-Adressen und Schlüssel werden vorher entfernt (unit-getestet). Die KI darf nur
„neu verbinden“, „Listen aktualisieren“ und „Selbsttest“ auslösen, unbekannte Aktionen werden verworfen. Der Key liegt
verschlüsselt (Android Keystore bzw. Windows DPAPI). Im kostenlosen Gemini-Tarif darf Google eingesendete Daten zur
Verbesserung nutzen.

## Shield für Windows

* Lokaler DNS-Filter auf 127.0.0.1/::1, alle verbundenen Adapter zeigen darauf. Upstream nur per DoH über Bootstrap-IPs.
* WireGuard über wireguard-go + Wintun. Der Kill-Switch nutzt die WFP-Firewall von WireGuard für Windows: ohne Tunnel
  kein Verkehr, DNS nur an Shield.
* Die Bedienoberfläche läuft nur auf 127.0.0.1 mit zufälligem Token pro Start, Host-Prüfung gegen DNS-Rebinding und
  JSON-only-Requests gegen Cross-Site-Angriffe (getestet).
* Grenzen: keine Zuordnung pro Programm, keine App-Firewall. Programme mit eigenem DoH (z. B. Browser mit „Sicheres DNS“)
  umgehen den Filter, ab Stufe Maximum werden bekannte DoH-Server per Liste blockiert. Windows' eigene Verbindungstests
  und Verkehr vor dem Start von Shield sind nicht abgedeckt. Stürzt Shield ab, werden die DNS-Einstellungen beim
  nächsten Start oder über „Shield – DNS zurücksetzen“ wiederhergestellt.

## Listen-Teile (`!#include`)

uBlock-Origin-Listen bestehen aus mehreren Dateien. Shield folgt `!#include` nur zu Dateien im selben Ordner desselben
HTTPS-Servers (kein `..`, keine fremden Hosts, kein `http`, höchstens 32 Teile, eine Ebene). Eine manipulierte Liste kann
Shield deshalb nicht zum Abruf beliebiger Adressen bringen.

## Netzwerkzugriffe der App selbst

Nur: Blocklisten-Downloads, DoH-Upstream, VPN-Endpunkt und vom Nutzer ausgelöste Checks (Leak-Test, Homelab-Check,
Wake-on-LAN). Jeder Zugriff erscheint als Ereignis `NETWORK_ACCESS` im Protokoll. Keine Analytics, kein Firebase,
keine Crash-Reporter, keine Werbe-SDKs.

## Sicherheitslücke melden

Bitte **nicht** als öffentliches Issue. Nutze GitHub → *Security* → *Report a vulnerability*
(private Meldung) im Repository [anonym239/Shield](https://github.com/anonym239/Shield).
Beschreibe Version, Gerät/Android-Version, Schritte zum Nachvollziehen und – falls vorhanden – einen Log-Export
aus dem Debug-Screen (standardmäßig ohne Domains/IPs). Wir antworten innerhalb von 7 Tagen und veröffentlichen
einen Fix mit Danksagung (wenn gewünscht).
