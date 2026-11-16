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
    dispatchFetch,
    cache: (name) => {
      if (!stores.has(name)) stores.set(name, new MemoryCache());
      return stores.get(name);
    },
    setNetworkHandler: (handler) => { networkHandler = handler; },
  };
}

const SHELL = "codexrm-shell-0.6.0";
const PAGES = "codexrm-pages-0.6.0";
const RUNTIME = "codexrm-runtime-0.6.0";

test("manifest has installable identity and real 192/512 PNG icons", () => {
  const manifest = JSON.parse(readFileSync(new URL("manifest.webmanifest", import.meta.url), "utf8"));
  assert.equal(manifest.id, "/");
  assert.equal(manifest.display, "standalone");
  for (const size of [192, 512]) {
    const icon = manifest.icons.find((item) => item.sizes === `${size}x${size}` && item.purpose === "any");
    const bytes = readFileSync(new URL(icon.src.slice(1), import.meta.url));
    assert.equal(bytes.subarray(1, 4).toString(), "PNG");
    assert.equal(bytes.readUInt32BE(16), size);
    assert.equal(bytes.readUInt32BE(20), size);
  }
  assert.ok(manifest.icons.some((item) => item.purpose === "maskable" && item.sizes === "512x512"));
});

test("install precaches the shell and activation removes only old CodexRM caches", async () => {
  const harness = createHarness();
  harness.cache("codexrm-shell-0.5.1");
  harness.cache("unrelated-application");
  await harness.lifecycle("install");
  assert.equal((await harness.cache(SHELL).keys()).length, 9);
  assert.equal(harness.worker.skipped, true);
  await harness.lifecycle("activate");
  assert.deepEqual([...harness.stores.keys()].sort(), [SHELL, "unrelated-application"].sort());
  assert.equal(harness.worker.clients.claimed, true);
});

test("versioned assets use cache-first without another network request", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  harness.networkCalls.length = 0;
  const result = await harness.dispatchFetch(`/app.js?v=0.6.0`);
  assert.equal(result.controlled, true);
  assert.equal(await result.response.text(), `network:${ORIGIN}/app.js?v=0.6.0`);
  assert.equal(harness.networkCalls.length, 0);
});

test("API, authenticated, non-GET and cross-origin requests bypass service-worker caches", async () => {
  const harness = createHarness();
  for (const [url, options] of [
    ["/api/v1/health", {}],
    ["/manifest.webmanifest", { headers: { Authorization: "Bearer secret" } }],
    ["/manifest.webmanifest", { method: "POST" }],
    ["https://outside.invalid/app.js?v=0.6.0", {}],
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

test("unversioned static assets use a runtime stale-while-revalidate cache", async () => {
  const harness = createHarness();
  await harness.lifecycle("install");
  harness.setNetworkHandler(async () => new Response("updated manifest", { headers: { "Content-Type": "application/manifest+json" } }));
  const first = await harness.dispatchFetch("/manifest.webmanifest");
  assert.equal(await first.response.text(), `network:${ORIGIN}/manifest.webmanifest`);
  assert.equal(await (await harness.cache(RUNTIME).match("/manifest.webmanifest")).text(), "updated manifest");

  harness.setNetworkHandler(async () => { throw new Error("offline"); });
  const second = await harness.dispatchFetch("/manifest.webmanifest");
  assert.equal(await second.response.text(), "updated manifest");
});
