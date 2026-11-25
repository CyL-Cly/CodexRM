import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

const ORIGIN = "http://codexrm.test";

function createHarness() {
  const listeners = new Map();
  const stores = new Map();
  const networkCalls = [];
  let networkHandler = async (input) => {
    const url = new URL(typeof input === "string" ? input : input.url, ORIGIN);
    const html = url.pathname === "/" || url.pathname.endsWith(".html");
    return new Response(`network:${url.href}`, {
      status: 200,
      headers: { "Content-Type": html ? "text/html; charset=utf-8" : "application/octet-stream" },
    });
  };

  const fetchMock = async (input, init) => {
    networkCalls.push(typeof input === "string" ? input : input.url);
    return networkHandler(input, init);
  };
  const keyOf = (request) => new URL(typeof request === "string" ? request : request.url, ORIGIN).href;

  class MemoryCache {
    constructor() { this.entries = new Map(); }
    async addAll(urls) {
      for (const url of urls) {
        const response = await fetchMock(url);
        if (!response.ok) throw new Error(`precache failed: ${url}`);
        await this.put(url, response);
      }
    }
    async put(request, response) { this.entries.set(keyOf(request), response.clone()); }
    async match(request) {
      const response = this.entries.get(keyOf(request));
      return response?.clone();
    }
    async keys() { return [...this.entries.keys()].map((url) => ({ url })); }
    async delete(request) { return this.entries.delete(keyOf(request)); }
  }

  const cacheStorage = {
    async open(name) {
      if (!stores.has(name)) stores.set(name, new MemoryCache());
      return stores.get(name);
    },
    async keys() { return [...stores.keys()]; },
    async delete(name) { return stores.delete(name); },
  };
  const worker = {
    location: new URL(`${ORIGIN}/`),
    clients: { claimed: false, async claim() { this.claimed = true; } },
    skipped: false,
    async skipWaiting() { this.skipped = true; },
    addEventListener(type, listener) {
      const handlers = listeners.get(type) || [];
      handlers.push(listener);
      listeners.set(type, handlers);
    },
  };
  const context = vm.createContext({
    self: worker,
    caches: cacheStorage,
    fetch: fetchMock,
    URL,
    Request,
    Response,
    Headers,
    AbortController,
    setTimeout,
    clearTimeout,
    console,
  });
  vm.runInContext(readFileSync(new URL("sw.js", import.meta.url), "utf8"), context);

  async function lifecycle(type) {
    const pending = [];
    const event = { waitUntil: (promise) => pending.push(Promise.resolve(promise)) };
    for (const listener of listeners.get(type) || []) listener(event);
    await Promise.all(pending);
  }

  async function dispatchMessage(data) {
    const pending = [];
    const event = { data, waitUntil: (promise) => pending.push(Promise.resolve(promise)) };
    for (const listener of listeners.get("message") || []) listener(event);
    await Promise.all(pending);
  }

  async function dispatchFetch(url, { mode = "same-origin", headers = {}, method = "GET" } = {}) {
    const request = { url: new URL(url, ORIGIN).href, method, mode, headers: new Headers(headers) };
    const pending = [];
    let responsePromise;
    let controlled = false;
    const event = {
      request,
      waitUntil: (promise) => pending.push(Promise.resolve(promise)),
      respondWith: (promise) => { controlled = true; responsePromise = Promise.resolve(promise); },
    };
    for (const listener of listeners.get("fetch") || []) listener(event);
    const response = responsePromise ? await responsePromise : null;
    await Promise.all(pending);
    return { controlled, response };
  }

  return {
    worker,
    stores,
    networkCalls,
    lifecycle,
    dispatchMessage,
    dispatchFetch,
    cache: (name) => {
      if (!stores.has(name)) stores.set(name, new MemoryCache());
      return stores.get(name);
    },
    setNetworkHandler: (handler) => { networkHandler = handler; },
  };
}

const VERSION = "20261124-2";
const SHELL = `codexrm-shell-${VERSION}`;
const PAGES = `codexrm-pages-${VERSION}`;
const RUNTIME = `codexrm-runtime-${VERSION}`;

test("HTML, manifest and service worker share the date asset version", () => {
  const html = readFileSync(new URL("index.html", import.meta.url), "utf8");
  for (const path of ["/styles.css", "/dsh-theme.css", "/theme.js", "/pwa.js", "/app.js", "/manifest.webmanifest", "/icons/icon.svg", "/icons/icon-192.png"])
    assert.ok(html.includes(`${path}?v=${VERSION}`), `${path} is missing ?v=${VERSION}`);
  assert.doesNotMatch(html, /<script(?:\s[^>]*)?>\s*try\s*\{[\s\S]*?codexrm\.theme/, "theme initialization must not be inline");
  const manifest = JSON.parse(readFileSync(new URL("manifest.webmanifest", import.meta.url), "utf8"));
  for (const icon of manifest.icons)
    assert.ok(icon.src.endsWith(`?v=${VERSION}`), `${icon.src} is missing the date version`);
  const worker = readFileSync(new URL("sw.js", import.meta.url), "utf8");
  assert.ok(worker.includes(`const VERSION = "${VERSION}"`));
});

test("manifest has installable identity and real 192/512 PNG icons", () => {
  const manifest = JSON.parse(readFileSync(new URL("manifest.webmanifest", import.meta.url), "utf8"));
  assert.equal(manifest.id, "/");
  assert.equal(manifest.display, "standalone");
  for (const size of [192, 512]) {
    const icon = manifest.icons.find((item) => item.sizes === `${size}x${size}` && item.purpose === "any");
    const bytes = readFileSync(new URL(icon.src.split("?")[0].slice(1), import.meta.url));
    assert.equal(bytes.subarray(1, 4).toString(), "PNG");
    assert.equal(bytes.readUInt32BE(16), size);
    assert.equal(bytes.readUInt32BE(20), size);
  }
  assert.ok(manifest.icons.some((item) => item.purpose === "maskable" && item.sizes === "512x512"));
});

test("install precaches the shell, waits safely and activation removes only old CodexRM caches", async () => {
  const harness = createHarness();
  harness.cache("codexrm-shell-0.5.1");
  harness.cache("unrelated-application");
  await harness.lifecycle("install");
  assert.equal((await harness.cache(SHELL).keys()).length, 11);
  assert.equal(harness.worker.skipped, false);
  await harness.lifecycle("activate");
  assert.deepEqual([...harness.stores.keys()].sort(), [SHELL, "unrelated-application"].sort());
  assert.equal(harness.worker.clients.claimed, true);
});

test("only an explicit SKIP_WAITING message activates the waiting worker", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  await harness.dispatchMessage({ type: "UNRELATED_MESSAGE" });
  assert.equal(harness.worker.skipped, false);
  await harness.dispatchMessage({ type: "SKIP_WAITING" });
  assert.equal(harness.worker.skipped, true);
});

test("versioned assets use cache-first without another network request", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  harness.networkCalls.length = 0;
  const result = await harness.dispatchFetch(`/app.js?v=${VERSION}`);
  assert.equal(result.controlled, true);
  assert.equal(await result.response.text(), `network:${ORIGIN}/app.js?v=${VERSION}`);
  assert.equal(harness.networkCalls.length, 0);
  for (const path of ["/dsh-theme.css", "/theme.js"]) {
    const theme = await harness.dispatchFetch(`${path}?v=${VERSION}`);
    assert.equal(theme.controlled, true);
    assert.equal(await theme.response.text(), `network:${ORIGIN}${path}?v=${VERSION}`);
    assert.equal(harness.networkCalls.length, 0);
  }
});

test("API, authenticated, non-GET and cross-origin requests bypass service-worker caches", async () => {
  const harness = createHarness();
  for (const [url, options] of [
    ["/api/v1/health", {}],
    ["/manifest.webmanifest", { headers: { Authorization: "Bearer secret" } }],
    ["/manifest.webmanifest", { method: "POST" }],
    [`https://outside.invalid/app.js?v=${VERSION}`, {}],
  ]) {
    const result = await harness.dispatchFetch(url, options);
    assert.equal(result.controlled, false);
    assert.equal(result.response, null);
  }
  assert.equal(harness.networkCalls.length, 0);
});

test("navigation is network-first, then reuses the newest cached page offline", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  harness.setNetworkHandler(async () => new Response("fresh page", { headers: { "Content-Type": "text/html" } }));
  const online = await harness.dispatchFetch("/", { mode: "navigate" });
  assert.equal(await online.response.text(), "fresh page");
  assert.equal(await (await harness.cache(PAGES).match("/")).text(), "fresh page");

  harness.setNetworkHandler(async () => { throw new Error("offline"); });
  const offline = await harness.dispatchFetch("/?from=pwa", { mode: "navigate" });
  assert.equal(await offline.response.text(), "fresh page");
  harness.cache(PAGES).entries.clear();
  const fallback = await harness.dispatchFetch("/", { mode: "navigate" });
  assert.equal(await fallback.response.text(), `network:${ORIGIN}/`);
});

test("cache write failures never displace successful network responses", async () => {
  const navigation = createHarness();
  await navigation.lifecycle("install");
  const pages = navigation.cache(PAGES);
  await pages.put("/", new Response("stale cached page", { headers: { "Content-Type": "text/html" } }));
  pages.put = async () => { throw new DOMException("storage full", "QuotaExceededError"); };
  navigation.setNetworkHandler(async () => new Response("fresh online page", { headers: { "Content-Type": "text/html" } }));
  const page = await navigation.dispatchFetch("/", { mode: "navigate" });
  assert.equal(await page.response.text(), "fresh online page");

  const assets = createHarness();
  const runtime = assets.cache(RUNTIME);
  runtime.put = async () => { throw new DOMException("storage full", "QuotaExceededError"); };
  assets.setNetworkHandler(async () => new Response("fresh online asset"));
  for (const url of ["/app.js?v=uncached", "/manifest.webmanifest"]) {
    const result = await assets.dispatchFetch(url);
    assert.equal(await result.response.text(), "fresh online asset");
  }
});

test("versioned static assets use a runtime stale-while-revalidate cache", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  const path = `/manifest.webmanifest?v=${VERSION}`;
  harness.setNetworkHandler(async () => new Response("updated manifest", { headers: { "Content-Type": "application/manifest+json" } }));
  const first = await harness.dispatchFetch(path);
  assert.equal(await first.response.text(), `network:${ORIGIN}${path}`);
  assert.equal(await (await harness.cache(RUNTIME).match(path)).text(), "updated manifest");

  harness.setNetworkHandler(async () => { throw new Error("offline"); });
  const second = await harness.dispatchFetch(path);
  assert.equal(await second.response.text(), "updated manifest");
});
