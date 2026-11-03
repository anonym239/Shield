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
| WebRTC | Browser können lokale IPs über WebRTC verraten. | WebRTC im Browser einschränken. |
| Leak-Test | Nutzt externe Dienste (am.i.mullvad.net, bash.ws) und ist eine Momentaufnahme. | Regelmäßig wiederholen. |
| Root/kompromittiertes Gerät | Mit Root-Rechten kann Malware jede App-Schutzschicht aushebeln. | Kein Root, aktuelle Sicherheitspatches. |

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
