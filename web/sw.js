"use strict";

const VERSION = "0.6.0";
const CACHE_PREFIX = "codexrm";
const SHELL_CACHE = `${CACHE_PREFIX}-shell-${VERSION}`;
const PAGE_CACHE = `${CACHE_PREFIX}-pages-${VERSION}`;
const RUNTIME_CACHE = `${CACHE_PREFIX}-runtime-${VERSION}`;
const CURRENT_CACHES = new Set([SHELL_CACHE, PAGE_CACHE, RUNTIME_CACHE]);
const SHELL_URLS = [
  "/",
  `/app.js?v=${VERSION}`,
  `/styles.css?v=${VERSION}`,
  `/pwa.js?v=${VERSION}`,
  "/manifest.webmanifest",
  "/icons/icon.svg",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/maskable-512.png",
];
const VERSIONED_PATHS = new Set(["/app.js", "/styles.css", "/pwa.js"]);
const STATIC_PATHS = new Set([
  "/manifest.webmanifest",
  "/icons/icon.svg",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/maskable-512.png",
]);

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(SHELL_CACHE);
      await cache.addAll(SHELL_URLS);
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((name) => name.startsWith(`${CACHE_PREFIX}-`) && !CURRENT_CACHES.has(name))
          .map((name) => caches.delete(name)),
      );
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || request.headers?.get("Authorization")) return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;

  if (request.mode === "navigate") {
    event.respondWith(networkFirstPage(request));
    return;
  }

  if (VERSIONED_PATHS.has(url.pathname) && url.searchParams.has("v")) {
    event.respondWith(cacheFirstAsset(request));
    return;
  }

  if (STATIC_PATHS.has(url.pathname)) {
    const update = updateRuntimeCache(request);
    event.waitUntil(update.then(() => undefined).catch(() => undefined));
    event.respondWith(staleWhileRevalidate(request, update));
  }
});

function cacheable(response) {
  return (
    response?.ok &&
    response.status === 200 &&
    ["basic", "default"].includes(response.type) &&
    response.headers.get("Vary") !== "*"
  );
}

async function fetchWithTimeout(request, milliseconds) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), milliseconds);
  try {
    return await fetch(request, { signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function networkFirstPage(request) {
  const pages = await caches.open(PAGE_CACHE);
  try {
    const response = await fetchWithTimeout(request, 3000);
    if (cacheable(response) && response.headers.get("Content-Type")?.includes("text/html")) {
      await pages.put(request, response.clone());
      await trimCache(PAGE_CACHE, 10);
    }
    return response;
  } catch (error) {
    const cached = (await pages.match(request)) || (await pages.match("/"));
    if (cached) return cached;
    const shell = await caches.open(SHELL_CACHE);
    const fallback = await shell.match("/");
    if (fallback) return fallback;
    throw error;
  }
}

async function cacheFirstAsset(request) {
  const shell = await caches.open(SHELL_CACHE);
  const cached = (await shell.match(request)) || (await (await caches.open(RUNTIME_CACHE)).match(request));
  if (cached) return cached;

  const response = await fetch(request);
  if (cacheable(response)) {
    const runtime = await caches.open(RUNTIME_CACHE);
    await runtime.put(request, response.clone());
    await trimCache(RUNTIME_CACHE, 40);
  }
  return response;
}

async function updateRuntimeCache(request) {
  const response = await fetch(request);
  if (cacheable(response)) {
    const runtime = await caches.open(RUNTIME_CACHE);
    await runtime.put(request, response.clone());
    await trimCache(RUNTIME_CACHE, 40);
  }
  return response;
}

async function staleWhileRevalidate(request, update) {
  const runtime = await caches.open(RUNTIME_CACHE);
  const cached = (await runtime.match(request)) || (await (await caches.open(SHELL_CACHE)).match(request));
  if (cached) {
    update.catch(() => undefined);
    return cached;
  }
  return update;
}

async function trimCache(name, maximum) {
  const cache = await caches.open(name);
  const keys = await cache.keys();
  await Promise.all(keys.slice(0, Math.max(0, keys.length - maximum)).map((request) => cache.delete(request)));
}
