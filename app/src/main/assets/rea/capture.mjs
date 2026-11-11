// Reloads one page through the browser's debugging port and records what REA's passive tools cannot:
// the requests made while the page loads, and the colours, fonts and visible text of the finished page.
// Usage: node capture.mjs http://127.0.0.1:9222 TARGET_ID out.json [seconds]
import { writeFileSync } from "node:fs";
const [endpoint, targetId, outFile, secs] = process.argv.slice(2);
const waitMs = Math.max(3, Number(secs || 10)) * 1000;
const list = await (await fetch(endpoint + "/json/list")).json();
const target = list.find((x) => x.id === targetId) || list.find((x) => x.type === "page");
if (!target) { console.error("no page target"); process.exit(1); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
const requests = new Map();
const send = (method, params = {}) => new Promise((resolve) => {
  const id = nextId++;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params }));
  setTimeout(() => { if (pending.has(id)) { pending.delete(id); resolve(null); } }, 20000);
});
ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result || null); pending.delete(msg.id); return; }
  if (msg.method === "Network.requestWillBeSent") {
    const r = msg.params;
    requests.set(r.requestId, { url: r.request.url, method: r.request.method, type: r.type || "", hasBody: !!r.request.hasPostData });
  } else if (msg.method === "Network.responseReceived") {
    const r = requests.get(msg.params.requestId);
    if (r) { r.status = msg.params.response.status; r.mime = msg.params.response.mimeType; }
  }
});
await new Promise((resolve, reject) => { ws.addEventListener("open", resolve); ws.addEventListener("error", reject); });
await send("Network.enable");
await send("Page.enable");
await send("Page.reload", { ignoreCache: false });
await new Promise((r) => setTimeout(r, waitMs));
const pageScript = `(() => {
  const count = (m, k) => { if (k) m[k] = (m[k] || 0) + 1; };
  const bg = {}, fg = {}, fonts = {}, sizes = {};
  const els = Array.from(document.querySelectorAll("body *")).slice(0, 4000);
  for (const el of els) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    const s = getComputedStyle(el);
    if (s.backgroundColor && s.backgroundColor !== "rgba(0, 0, 0, 0)") count(bg, s.backgroundColor);
    if (el.childElementCount === 0 && (el.textContent || "").trim()) {
      count(fg, s.color); count(fonts, s.fontFamily.split(",")[0].replace(/["']/g, "").trim()); count(sizes, s.fontSize);
    }
  }
  const top = (m, n) => Object.entries(m).sort((a, b) => b[1] - a[1]).slice(0, n).map(([k, v]) => k + " x" + v);
  const text = (sel, n) => Array.from(document.querySelectorAll(sel)).map((e) => (e.getAttribute("aria-label") || e.innerText || e.value || e.getAttribute("placeholder") || e.getAttribute("title") || "").trim().replace(/\\s+/g, " ").slice(0, 80)).filter(Boolean).slice(0, n);
  const b = getComputedStyle(document.body);
  return JSON.stringify({
    title: document.title, url: location.href, lang: document.documentElement.lang || "",
    viewport: innerWidth + "x" + innerHeight, scrollHeight: document.documentElement.scrollHeight,
    bodyBackground: b.backgroundColor, bodyColor: b.color, bodyFont: b.fontFamily, bodyFontSize: b.fontSize,
    backgrounds: top(bg, 12), textColors: top(fg, 10), fonts: top(fonts, 8), fontSizes: top(sizes, 8),
    headings: text("h1,h2,h3", 40), buttons: text("button,[role=button],input[type=submit],input[type=button]", 80),
    links: Array.from(document.querySelectorAll("a[href]")).map((a) => ((a.innerText || a.getAttribute("aria-label") || "").trim().replace(/\\s+/g, " ").slice(0, 60) + " -> " + a.getAttribute("href").slice(0, 120))).filter((x) => !x.startsWith(" ->")).slice(0, 80),
    inputs: Array.from(document.querySelectorAll("input,textarea,select")).map((e) => (e.type || e.tagName.toLowerCase()) + ": " + (e.getAttribute("aria-label") || e.getAttribute("placeholder") || e.name || e.id || "")).slice(0, 60),
    landmarks: Array.from(document.querySelectorAll("header,nav,main,aside,footer,section,dialog,[role=dialog],[role=tablist],[role=menu],[role=grid],canvas,svg,table,form")).map((e) => { const r = e.getBoundingClientRect(); return (e.getAttribute("role") || e.tagName.toLowerCase()) + " " + Math.round(r.width) + "x" + Math.round(r.height) + " at " + Math.round(r.left) + "," + Math.round(r.top); }).slice(0, 60),
    localStorageKeys: (() => { try { return Object.keys(localStorage).slice(0, 40); } catch (e) { return []; } })(),
    frameworks: [window.React || document.querySelector("[data-reactroot],#__next,#root") ? "React (likely)" : "", window.__NEXT_DATA__ ? "Next.js" : "", window.__NUXT__ ? "Nuxt" : "", window.Vue || document.querySelector("[data-v-app]") ? "Vue (likely)" : "", document.querySelector("[ng-version]") ? "Angular " + document.querySelector("[ng-version]").getAttribute("ng-version") : "", window.jQuery ? "jQuery" : "", document.querySelector("[data-svelte-h],[class*=svelte-]") ? "Svelte (likely)" : ""].filter(Boolean),
    visibleText: (document.body.innerText || "").replace(/\\n{2,}/g, "\\n").slice(0, 3000),
  });
})()`;
const evalResult = await send("Runtime.evaluate", { expression: pageScript, returnByValue: true });
let page = null;
try { page = JSON.parse(evalResult.result.value); } catch (e) { page = { error: "page script did not return" }; }
writeFileSync(outFile, JSON.stringify({ requests: Array.from(requests.values()).slice(0, 400), page }, null, 1));
ws.close();
process.exit(0);
