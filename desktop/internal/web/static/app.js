// Shield für Windows – UI. Talks only to the local engine on 127.0.0.1 with a per-start token.
"use strict";
const token = new URLSearchParams(location.hash.slice(1)).get("t") || sessionStorage.getItem("t") || "";
sessionStorage.setItem("t", token);
history.replaceState(null, "", location.pathname);
const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);
let S = null, qFilter = "", sub = "queries";

async function api(path, data) {
  const opt = { headers: { "X-Shield-Token": token } };
  if (data !== undefined) { opt.method = "POST"; opt.headers["Content-Type"] = "application/json"; opt.body = JSON.stringify(data); }
  const r = await fetch(path, opt);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || ("HTTP " + r.status));
  return j;
}
function toast(t) { const el = $("#toast"); el.textContent = t; el.classList.add("show"); clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove("show"), 3500); }
function esc(s) { return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
const fmt = (n) => Number(n || 0).toLocaleString("de-DE");
const time = (t) => new Date(t).toLocaleTimeString("de-DE");
function ago(t) { if (!t) return "nie"; const m = Math.round((Date.now() - t) / 60000); return m < 1 ? "gerade eben" : m < 60 ? `vor ${m} Min` : `vor ${Math.round(m / 60)} h`; }
const ICON = {
  shield: "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z",
  power: "M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42A6.92 6.92 0 0 1 19 12c0 3.87-3.13 7-7 7A6.995 6.995 0 0 1 7.58 6.58L6.17 5.17A8.932 8.932 0 0 0 3 12a9 9 0 0 0 18 0c0-2.74-1.23-5.18-3.17-6.83z",
  warn: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  err: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z",
};
const REASON = { TUNNEL_OFF: "Schutz ist aus", TUNNEL_ERROR: "Fehler – siehe Debug", SELFTEST_FAILED: "Selbsttest fehlgeschlagen",
  PANIC: "Panik-Modus: alles wird verworfen", LISTS_MISSING: "Blocklisten werden noch geladen", LISTS_OUTDATED: "Blocklisten sind veraltet",
  VPN_DISCONNECTED: "VPN getrennt", SELFTEST_PENDING: "Wird geprüft …" };
const LEVELS = [
  ["STANDARD", "Standard", "Werbung, Tracker, Malware, Phishing. Alles funktioniert uneingeschränkt."],
  ["STRICT", "Streng", "Zusätzlich Telemetrie (Firebase, Analyse-SDKs, Windows), strenge EasyPrivacy-Regeln, HaGeZi Pro."],
  ["MAXIMUM", "Maximum", "Zusätzlich Bedrohungs-Feeds, Windows/Office-Telemetrie, Sperre fremder DNS-Server. Kill-Switch an."],
];
const TESTS = { TUNNEL: "Lokaler DNS-Filter läuft", SYSTEM_DNS: "System-DNS zeigt auf Shield", TEST_DOMAIN: "Test-Domain wird blockiert",
  UPSTREAM: "Verschlüsseltes DNS erreichbar", LISTS: "Blocklisten geladen und aktuell", VPN: "VPN verbunden" };

function render() {
  if (!S) return;
  const on = S.phase === "RUNNING" || S.phase === "STARTING";
  const checking = on && (S.phase === "STARTING" || (S.reasons || []).includes("SELFTEST_PENDING"));
  const col = !on ? "var(--outline)" : checking ? "var(--primary)" : S.status === "PROTECTED" ? "var(--green)" : S.status === "LIMITED" ? "var(--yellow)" : "var(--red)";
  const ring = $("#power");
  ring.className = "ring " + (!on ? "off" : checking ? "checking" : "on");
  ring.style.setProperty("--c", col);
  ring.setAttribute("aria-checked", on);
  const text = S.panic ? "Panik-Modus" : !on ? "Aus" : checking ? "Wird geprüft …" : { PROTECTED: "Geschützt", LIMITED: "Eingeschränkt", UNPROTECTED: "Ungeschützt" }[S.status];
  $("#ringText").textContent = text;
  $("#ringIcon").innerHTML = `<path d="${!on ? ICON.power : S.status === "PROTECTED" || checking ? ICON.shield : S.status === "LIMITED" ? ICON.warn : ICON.err}"/>`;
  const reasons = (S.reasons || []).filter((r) => r !== "SELFTEST_PENDING");
  $("#hint").textContent = !on ? "Klicken zum Einschalten" : checking ? "Alle Schutzschichten werden geprüft …" : reasons.length ? REASON[reasons[0]] : "Alle Schutzschichten aktiv und geprüft · Klicken zum Ausschalten";
  $("#errorText").textContent = S.error || (!S.admin ? "Shield braucht Administratorrechte – bitte als Administrator starten." : "");
  const lv = LEVELS.find((l) => l[0] === S.settings.level) || LEVELS[0];
  $("#levelChip").textContent = "Stufe: " + lv[1];
  $("#levelTitle").textContent = lv[1];
  $("#rulesCount").textContent = fmt(S.rules) + " Regeln";
  $("#blockedToday").textContent = fmt(S.today.blocked);
  dot("#dotBlocker", on ? "ok" : "bad");
  // VPN card: neutral when not set up
  const v = S.vpn;
  if (!S.settings.activeProfile) { $("#vpnTitle").textContent = "Nicht eingerichtet"; $("#vpnDetail").textContent = "Proton & Co. hier verbinden"; dot("#dotVpn", "na"); }
  else { $("#vpnTitle").textContent = v.name || "VPN"; $("#vpnDetail").textContent = v.connected ? "verbunden · Handshake vor " + v.handshakeAge + " s" : "verbindet …"; dot("#dotVpn", v.connected ? "ok" : "warn"); }
  const dnsOk = S.selftest && (S.selftest.results || []).find((r) => r.id === "SYSTEM_DNS");
  $("#netTitle").textContent = on ? "DNS: Shield" : "DNS: Standard";
  $("#netDetail").textContent = dnsOk ? dnsOk.detail : "System-DNS";
  dot("#dotNet", !on ? "bad" : dnsOk && dnsOk.passed ? "ok" : "warn");
  // self-test
  const rep = S.selftest;
  if (rep) {
    const app = rep.results.filter((r) => r.passed !== null);
    $("#testSummary").textContent = `Zuletzt geprüft ${ago(rep.time)} · ${app.filter((r) => r.passed).length}/${app.length} Tests bestanden`;
    $("#testList").innerHTML = rep.results.map((r) => `<li><b class="${r.passed === null ? "muted" : r.passed ? "dot ok" : "dot bad"}">${r.passed === null ? "–" : r.passed ? "✓" : "✗"}</b><span><b style="width:auto">${esc(TESTS[r.id] || r.id)}</b><br><span class="muted small">${esc(r.detail)}</span></span></li>`).join("");
  }
  $("#testBtn").disabled = S.testing;
  // chart
  const max = Math.max(1, ...S.hours.map((h) => h.queries));
  $("#chart").innerHTML = S.hours.map((h) => `<div class="bar" title="${new Date(h.hour * 3600000).getHours()}:00 · ${h.queries} Anfragen, ${h.blocked} blockiert"><i style="height:${(h.queries / max) * 100}%"></i><i class="b" style="height:${(h.blocked / max) * 100}%"></i></div>`).join("");
  $("#chartSummary").textContent = `Heute: ${fmt(S.today.queries)} Anfragen · ${fmt(S.today.blocked)} blockiert` + (S.today.phishing ? ` · ${fmt(S.today.phishing)} Phishing` : "");
  $("#topBlocked").innerHTML = (S.topBlocked || []).map((d) => `<li><span>${esc(d.domain)}</span><b>${fmt(d.count)}</b></li>`).join("") || `<li class="muted">${S.settings.noHistory ? "„Kein Verlauf“ ist an" : "Noch keine Daten"}</li>`;
  $("#panicBtn").classList.toggle("hidden", !on);
  $("#panicBtn").textContent = S.panic ? "Panik-Modus beenden" : "✋ Panik-Knopf";
  // AI
  const ai = S.ai.verdict;
  $("#aiCard").classList.toggle("hidden", !(S.settings.aiEnabled && ai));
  if (ai) { $("#aiTitle").textContent = `KI-Prüfung (${ai.status}) · ${ago(ai.time)}`; $("#aiSummary").textContent = ai.summary; }
  renderSettings();
}
function dot(sel, k) { const e = $(sel); e.className = "dot " + k; e.textContent = { ok: "✓", warn: "!", bad: "✗", na: "i" }[k]; e.setAttribute("aria-label", { ok: "in Ordnung", warn: "eingeschränkt", bad: "aus", na: "nicht eingerichtet" }[k]); }

function renderSettings() {
  const s = S.settings;
  $("#levels").innerHTML = LEVELS.map(([id, n, d]) => `<button class="level ${s.level === id ? "active" : ""}" data-level="${id}"><b>${n}</b><span class="muted small">${d}</span></button>`).join("");
  const sel = $("#upstream");
  if (!sel.dataset.filled) { sel.innerHTML = S.upstreams.map((u) => `<option value="${u.ID}">${esc(u.Label)} (${esc(u.URL)})</option>`).join("") + `<option value="CUSTOM">Eigener Server</option>`; sel.dataset.filled = 1; }
  if (document.activeElement !== sel) sel.value = s.upstream;
  $("#customBox").classList.toggle("hidden", s.upstream !== "CUSTOM");
  $("#lists").innerHTML = S.lists.map((l) => `<li><label class="switch" style="padding:0"><input type="checkbox" data-list="${l.id}" ${l.enabled ? "checked" : ""}><span>${esc(l.name)}<br><span class="muted small">${l.category}${l.rules ? " · " + fmt(l.rules) + " Regeln" : ""}${l.updatedAt ? " · " + ago(l.updatedAt) : ""}${l.error ? " · ⚠ " + esc(l.error) : ""}</span></span></label></li>`).join("");
  $("#rules").innerHTML = (S.ownRules || []).map((r) => `<li><span>${esc(r.pattern)} <span class="${r.allow ? "dot ok" : "dot bad"}">${r.allow ? "erlaubt" : "blockiert"}</span></span><button class="btn outline" data-unrule="${esc(r.pattern)}">Löschen</button></li>`).join("") || `<li class="muted">Noch keine eigenen Regeln</li>`;
  for (const id of ["autostart", "autoHeal", "noHistory", "aiEnabled", "killSwitch"]) if (document.activeElement !== $("#" + id)) $("#" + id).checked = !!s[id];
  $("#aiKeyBox").classList.toggle("hidden", S.ai.hasKey); $("#aiKeySaved").classList.toggle("hidden", !S.ai.hasKey);
  if (document.activeElement !== $("#aiModel")) $("#aiModel").value = s.aiModel;
  $("#aiCheck").disabled = !S.ai.hasKey || S.ai.running;
  $("#profiles").innerHTML = (S.profiles || []).map((p) => `<li><label class="switch" style="padding:0"><input type="radio" name="prof" data-prof="${p.id}" ${s.activeProfile === p.id ? "checked" : ""}><span>${esc(p.name)}<br><span class="muted small">${esc(p.endpoint)}${s.activeProfile === p.id && S.vpn.connected ? " · verbunden" : ""}</span></span></label><button class="btn outline" data-delprof="${p.id}">Löschen</button></li>`).join("")
    + `<li><label class="switch" style="padding:0"><input type="radio" name="prof" data-prof="" ${!s.activeProfile ? "checked" : ""}><span>Kein VPN (nur Blocker)</span></label></li>`;
  $("#about").textContent = `Shield für Windows ${S.version} · GPL-3.0 · Daten: %ProgramData%\\Shield · Kein Schutz ist lückenlos – Shield zeigt, welche Schichten aktiv und geprüft sind.`;
}

async function refresh() { try { S = await api("/api/state"); render(); } catch (e) { $("#errorText").textContent = "Keine Verbindung zum Shield-Dienst: " + e.message; } }
async function loadLog() {
  if (sub === "queries") {
    const q = await api(`/api/queries?result=${qFilter}&q=${encodeURIComponent($("#search").value)}`);
    $("#queries").innerHTML = q.map((e) => `<tr><td>${time(e.time)}</td><td class="dom">${esc(e.domain)}</td><td class="res-${e.result}">${{ BLOCKED: "Blockiert", ALLOWED: "Erlaubt", ERROR: "Fehler" }[e.result]}</td><td class="muted small">${esc(e.rule)}</td><td><button class="chip" data-act="${e.result === "BLOCKED" ? "allow" : "block"}" data-dom="${esc(e.domain)}">${e.result === "BLOCKED" ? "Erlauben" : "Blockieren"}</button></td></tr>`).join("") || `<tr><td class="muted">${S && S.settings.noHistory ? "„Kein Verlauf speichern“ ist an" : "Noch keine Anfragen"}</td></tr>`;
  } else {
    const ev = await api("/api/events");
    $("#events").innerHTML = ev.map((e) => `<tr><td>${new Date(e.time).toLocaleString("de-DE")}</td><td class="${e.severity === "CRITICAL" ? "res-BLOCKED" : e.severity === "WARNING" ? "res-ERROR" : ""}">${e.severity === "CRITICAL" ? "✗" : e.severity === "WARNING" ? "!" : "i"} ${esc(e.type)}</td><td>${esc(e.message)}</td></tr>`).join("");
  }
}
async function loadDebug() { $("#dbgState").textContent = JSON.stringify({ phase: S?.phase, status: S?.status, reasons: S?.reasons, vpn: S?.vpn, selftest: S?.selftest, rules: S?.rules }, null, 2); $("#dbgLog").textContent = (await api("/api/log")).slice(-400).reverse().join("\n"); }
async function act(fn, ok) { try { await fn(); if (ok) toast(ok); } catch (e) { toast("⚠ " + e.message); } refresh(); }
const set = (o, msg) => act(() => api("/api/settings", o), msg || "Gespeichert");

// events
$$(".tabs button").forEach((b) => b.onclick = () => { $$(".tabs button").forEach((x) => x.classList.toggle("active", x === b)); $$(".tab").forEach((t) => t.classList.toggle("active", t.id === b.dataset.tab)); if (b.dataset.tab === "log") loadLog(); if (b.dataset.tab === "debug") loadDebug(); });
const go = (tab) => document.querySelector(`.tabs button[data-tab=${tab}]`).click();
$("#power").onclick = () => { const on = S && (S.phase === "RUNNING" || S.phase === "STARTING"); act(() => api("/api/toggle", { on: !on }), on ? "Schutz ausgeschaltet" : "Schutz wird eingeschaltet …"); };
$("#levelChip").onclick = () => go("settings");
$$("[data-go]").forEach((c) => c.onclick = () => go(c.dataset.go));
$("#testBtn").onclick = () => act(() => api("/api/selftest", {}), "Selbsttest fertig");
$("#panicBtn").onclick = () => { if (S.panic || confirm("Alle DNS-Anfragen sofort verwerfen? Websites und Programme sind dann offline, bis du den Panik-Modus beendest.")) act(() => api("/api/panic", { on: !S.panic })); };
$$("[data-sub]").forEach((b) => b.onclick = () => { sub = b.dataset.sub; $$("[data-sub]").forEach((x) => x.classList.toggle("active", x === b)); $("#queriesPane").classList.toggle("hidden", sub !== "queries"); $("#eventsPane").classList.toggle("hidden", sub !== "events"); loadLog(); });
$$("[data-filter]").forEach((b) => b.onclick = () => { qFilter = b.dataset.filter; $$("[data-filter]").forEach((x) => x.classList.toggle("active", x === b)); loadLog(); });
$("#search").oninput = () => loadLog();
document.addEventListener("click", (ev) => {
  const t = ev.target.closest("[data-act],[data-unrule],[data-level],[data-delprof]");
  if (!t) return;
  if (t.dataset.act) act(() => api("/api/rules", { pattern: t.dataset.dom, allow: t.dataset.act === "allow" }), t.dataset.act === "allow" ? "Erlaubt" : "Blockiert").then(loadLog);
  if (t.dataset.unrule) act(() => api("/api/rules", { pattern: t.dataset.unrule, remove: true }), "Regel gelöscht");
  if (t.dataset.level) set({ level: t.dataset.level }, "Schutzstufe geändert – Listen werden geladen");
  if (t.dataset.delprof && confirm("Profil und privaten Schlüssel löschen?")) act(() => api("/api/vpn/delete", { id: t.dataset.delprof }), "Profil gelöscht");
});
document.addEventListener("change", (ev) => {
  const t = ev.target;
  if (t.dataset.list !== undefined) { const lists = [...$$("[data-list]")].filter((x) => x.checked).map((x) => x.dataset.list); set({ lists }); }
  if (t.dataset.prof !== undefined) set({ activeProfile: t.dataset.prof }, "VPN-Profil gewechselt");
  if (["autostart", "autoHeal", "noHistory", "aiEnabled", "killSwitch"].includes(t.id)) set({ [t.id]: t.checked });
});
$("#upstream").onchange = (e) => set({ upstream: e.target.value }, "DNS-Server geändert");
$("#customSave").onclick = () => set({ customDoh: $("#customUrl").value.trim(), customBootstrap: $("#customBoot").value.trim() });
$("#listsUpdate").onclick = () => act(() => api("/api/lists/update", {}), "Listen werden aktualisiert …");
$("#ruleBlock").onclick = () => act(() => api("/api/rules", { pattern: $("#rulePattern").value, allow: false }), "Blockiert").then(() => ($("#rulePattern").value = ""));
$("#ruleAllow").onclick = () => act(() => api("/api/rules", { pattern: $("#rulePattern").value, allow: true }), "Erlaubt").then(() => ($("#rulePattern").value = ""));
$("#clearHistory").onclick = () => act(() => api("/api/history/clear", {}), "Verlauf gelöscht");
$("#confFile").onchange = async (e) => { const f = e.target.files[0]; if (!f) return; const txt = await f.text(); act(() => api("/api/vpn/import", { name: f.name.replace(/\.conf$/i, ""), conf: txt }), "Profil importiert – VPN wird verbunden"); e.target.value = ""; };
let protonTimer = null;
function showProton(st) {
  const t = $("#protonStatus");
  if (st.state === "WAITING") t.textContent = "⏳ Warte auf die heruntergeladene Datei …";
  else if (st.state === "DONE") t.textContent = "✓ „" + st.profile + "“ eingerichtet – Shield verbindet und prüft jetzt alles.";
  else if (st.state === "ERROR") t.textContent = "⚠ Fehler: " + st.message;
  else if (st.state === "TIMEOUT") t.textContent = "⚠ " + st.message + ". Bitte erneut versuchen.";
  else t.textContent = "";
  if (st.state !== "WAITING" && protonTimer) { clearInterval(protonTimer); protonTimer = null; refresh(); }
}
$("#protonBtn").onclick = () => api("/api/proton/connect", {}).then((st) => {
  showProton(st);
  if (protonTimer) clearInterval(protonTimer);
  protonTimer = setInterval(() => api("/api/proton/status", {}).then(showProton).catch((e) => showProton({ state: "ERROR", message: e.message })), 2000);
}).catch((e) => showProton({ state: "ERROR", message: e.message }));
$("#pasteBtn").onclick = () => $("#pasteBox").classList.toggle("hidden");
$("#pasteSave").onclick = () => act(() => api("/api/vpn/import", { name: $("#pasteName").value, conf: $("#pasteConf").value }), "Profil importiert").then(() => { $("#pasteConf").value = ""; $("#pasteBox").classList.add("hidden"); });
$("#aiKeySave").onclick = () => act(() => api("/api/ai/key", { key: $("#aiKey").value }), "Key gespeichert").then(() => ($("#aiKey").value = ""));
$("#aiKeyDelete").onclick = () => act(() => api("/api/ai/key", { delete: true }), "Key gelöscht");
$("#aiModel").onchange = (e) => set({ aiModel: e.target.value });
$("#aiCheck").onclick = () => { $("#aiResult").textContent = "KI prüft …"; api("/api/ai/check", {}).then((v) => ($("#aiResult").textContent = `${v.status}: ${v.summary}`)).catch((e) => ($("#aiResult").textContent = "⚠ " + e.message)).finally(refresh); };
$("#quitBtn").onclick = () => { if (confirm("Shield beenden? Der Schutz endet und Windows nutzt wieder die normalen DNS-Server.")) api("/api/quit", {}).then(() => (document.body.innerHTML = "<main><h2>Shield wurde beendet.</h2></main>")); };
$("#dbgTest").onclick = () => act(() => api("/api/selftest", {}), "Selbsttest fertig").then(loadDebug);
$("#dbgRestart").onclick = () => act(() => api("/api/restart", {}), "Neu gestartet").then(loadDebug);
$("#dbgLists").onclick = () => act(() => api("/api/lists/update", {}), "Listen werden geladen");
document.addEventListener("click", (ev) => { const a = ev.target.closest("a[target=_blank]"); if (a) { ev.preventDefault(); api("/api/open", { url: a.href }).catch(() => toast("Bitte im Browser öffnen: " + a.href)); } });

refresh();
setInterval(() => { refresh(); if ($("#log").classList.contains("active")) loadLog(); if ($("#debug").classList.contains("active")) loadDebug(); }, 2500);
