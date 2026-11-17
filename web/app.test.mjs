import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

// Run the shipped script with a small DOM double; browser QA covers layout.
function loadApp(storage = new Map(), hash = "", { hidden = false, webgl = null, navigator = {} } = {}) {
  class Element {
    constructor(tagName = "div") {
      this.tagName = tagName.toUpperCase();
      this.children = []; this.dataset = {}; this.style = {};
      this.value = ""; this.textContent = ""; this.scrollTop = 0;
      this.classList = { toggle() {}, add() {}, remove() {} };
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(name, value) { this[name] = value; }
    addEventListener() {}
    focus() {}
    requestSubmit() { return this.onsubmit?.({ preventDefault() {} }); }
    close() {}
    showModal() {}
    contains(node) { return this === node || this.children.some((n) => n.contains?.(node)); }
    querySelectorAll(selector) {
      return this.children.flatMap((n) => [
        ...(selector.split(",").map((s) => s.trim().toUpperCase()).includes(n.tagName) ? [n] : []),
        ...(n.querySelectorAll?.(selector) || []),
      ]);
    }
    get options() { return this.children.flatMap((node) => node.tagName === "OPTGROUP" ? node.options : [node]); }
  }
  const elements = new Map();
  const documentListeners = new Map();
  const windowListeners = new Map();
  const timers = new Map();
  let nextTimer = 0;
  let timerNow = 0;
  const contextRequests = [];
  const document = {
    hidden,
    get visibilityState() { return this.hidden ? "hidden" : "visible"; },
    getElementById: (id) => {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    createElement: (tag) => {
      const element = new Element(tag);
      if (tag === "canvas") element.getContext = (kind) => {
        contextRequests.push(kind);
        return typeof webgl === "function" ? webgl(kind) : webgl;
      };
      return element;
    },
    querySelectorAll: () => [], querySelector: () => null,
    addEventListener(type, listener) { documentListeners.set(type, listener); }, body: new Element(), documentElement: new Element(),
    activeElement: new Element("body"),
  };
  const claims = [];
  const profiles = [];
  const history = { replaceState(_state, _title, url) { this.url = url; } };
  const context = vm.createContext({
    document, localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    window: { addEventListener(type, listener) { windowListeners.set(type, listener); } },
    location: { origin: "http://localhost", href: `http://localhost/?keep=1${hash}`, protocol: "http:", pathname: "/", search: "?keep=1", hash },
    history, URL, URLSearchParams, console, navigator,
    fetch: async (path, options) => {
      if (path === "/api/v1/client/claim") claims.push(JSON.parse(options.body));
      if (path === "/api/v1/client/profile") profiles.push({ ...JSON.parse(options.body), authorization: options.headers.Authorization });
      return { ok: true, status: 200, json: async () => ({ token: "token", agent: { agentId: "device", serverName: "Computer" } }) };
    },
    Option: class extends Element { constructor(label, value) { super("option"); this.textContent = label; this.value = value; } },
    setTimeout: (callback, delay = 0) => { const id = ++nextTimer; timers.set(id, { callback, delay, deadline: timerNow + delay }); return id; },
    clearTimeout: (id) => timers.delete(id), setInterval() {}, clearInterval() {},
    requestAnimationFrame() {}, WebSocket: class { static OPEN = 1; close() {} },
  });
  vm.runInContext(readFileSync(new URL("app.js", import.meta.url), "utf8"), context);
  const advance = (milliseconds) => {
    const deadline = timerNow + milliseconds;
    while (true) {
      const next = [...timers].filter(([, timer]) => timer.deadline <= deadline)
        .sort((a, b) => a[1].deadline - b[1].deadline)[0];
      if (!next) break;
      timerNow = next[1].deadline;
      timers.delete(next[0]);
      next[1].callback();
    }
    timerNow = deadline;
  };
  return { context, elements, claims, profiles, contextRequests, history, document, documentListeners, windowListeners, timers,
    advance,
    profile: vm.runInContext("({browserClientProfile, syncClientProfile})", context),
    app: vm.runInContext("({state, workspaceGroups, displayedTurnStatus, loadThreads, onEvent, renderApprovals, sandboxPolicy, answerRequest, newChat, selectWorkspace, restoreDraft, resizePrompt, submit, stopTurn, acceptMessageQueue, loadMessageQueue, closeCodexDesktop, connect, disconnectSocket, toast, loadModels, selectThreadModel, selectedModel, threadProviderId, modelKey, turnParams})", context) };
}

const gpuRenderer = "ANGLE (AMD, AMD Radeon 780M Graphics (0x00001900) Direct3D11 vs_5_0 ps_5_0, D3D11)";
function rendererContext(renderer = gpuRenderer) {
  const gl = {
    releases: 0,
    getExtension(name) {
      if (name === "WEBGL_debug_renderer_info") return { UNMASKED_RENDERER_WEBGL: 0x9246 };
      if (name === "WEBGL_lose_context") return { loseContext() { gl.releases++; } };
    },
    getParameter(parameter) { assert.equal(parameter, 0x9246); return renderer; },
  };
  return gl;
}

test("pairing uses the complete WebGL GPU renderer and releases the temporary context", async () => {
  const gl = rendererContext();
  const { claims, profile, contextRequests } = loadApp(new Map(), "#pair=ABCD-EFGH", {
    webgl: gl, navigator: { userAgent: "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0.0.0" },
  });
  await new Promise(setImmediate);
  assert.equal(claims[0].clientName, gpuRenderer);
  assert.equal(claims[0].deviceType, "desktop");
  assert.equal(profile.browserClientProfile().clientName, gpuRenderer);
  assert.deepEqual(contextRequests, ["webgl2"]);
  assert.equal(gl.releases, 1);
});

test("WebGL 1 devices retain their GPU name and mobile type when WebGL 2 is unavailable", () => {
  const gl = rendererContext("Adreno (TM) 750");
  const { profile, contextRequests } = loadApp(new Map(), "", {
    webgl: (kind) => kind === "webgl" ? gl : null,
    navigator: { userAgent: "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile" },
  });
  const detected = profile.browserClientProfile();
  assert.equal(detected.clientName, "Adreno (TM) 750");
  assert.equal(detected.deviceType, "mobile");
  assert.deepEqual(contextRequests, ["webgl2", "webgl"]);
  assert.equal(gl.releases, 1);
});

test("blocked, missing and empty WebGL renderer information still permits pairing with a useful fallback", async () => {
  for (const webgl of [null, () => { throw new Error("WebGL blocked"); }, { getExtension() { return null; } }, rendererContext("  ")]) {
    const { claims } = loadApp(new Map(), "#pair=ABCD-EFGH", {
      webgl, navigator: { userAgent: "Mozilla/5.0 (Windows NT 10.0) Firefox/130.0" },
    });
    await new Promise(setImmediate);
    assert.equal(claims[0].clientName, "Windows · Firefox");
    assert.equal(claims[0].deviceType, "desktop");
  }
});

test("existing pairings update their own profile on connection without re-pairing or repeated writes", async () => {
  const storage = new Map([["codexrm.devices.v2", JSON.stringify([{ token: "existing-token", agent: { agentId: "device" } }])]]);
  const { app, profiles, claims } = loadApp(storage, "", { webgl: rendererContext() });
  app.state.socket.onopen();
  await new Promise(setImmediate);
  assert.equal(claims.length, 0);
  assert.equal(profiles.length, 1);
  assert.equal(profiles[0].clientName, gpuRenderer);
  assert.equal(profiles[0].authorization, "Bearer existing-token");
  app.connect({ reconnect: true });
  app.state.socket.onopen();
  await new Promise(setImmediate);
  assert.equal(profiles.length, 1);
});

test("profile synchronization failures leave the connection usable and retry on reconnect", async () => {
  const { app, context } = loadApp(new Map(), "", { webgl: rendererContext() });
  app.state.devices = [{ token: "existing-token", agent: { agentId: "device" } }];
  app.state.selected = "device";
  let attempts = 0;
  context.api = async () => { if (++attempts === 1) throw new Error("temporarily offline"); return {}; };
  app.connect();
  const firstSocket = app.state.socket;
  firstSocket.onopen();
  await new Promise(setImmediate);
  assert.equal(app.state.socket, firstSocket);
  app.connect({ reconnect: true });
  app.state.socket.onopen();
  await new Promise(setImmediate);
  assert.equal(attempts, 2);
});

function providerApp() {
  const loaded = loadApp();
  Object.assign(loaded.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, info: { providerModels: true, cwd: "/project" },
  });
  const model = (name) => ({ model: name, supportedReasoningEfforts: [{ reasoningEffort: "low" }, { reasoningEffort: "high" }] });
  const providers = [
    { id: "", runtimeId: "", name: "Current", models: [model("shared")] },
    { id: "ccswitch_a", runtimeId: "ccswitch_a", name: "A", models: [model("shared"), model("alternate")] },
    { id: "ccswitch_b", runtimeId: "ccswitch_b", name: "B", models: [model("shared")] },
  ];
  loaded.context.rpc = async () => ({ providers });
  return { ...loaded, providers };
}

test("provider changes create a new thread while model changes retain the current supplier", async () => {
  const { app, elements } = providerApp();
  await app.loadModels();
  app.state.current = { id: "thread-a", modelProvider: "ccswitch_a", model: "shared", cwd: "/project" };
  app.selectThreadModel({ thread: app.state.current });
  elements.get("model").value = app.modelKey("ccswitch_a", "alternate");
  elements.get("model").onchange();
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.turnParams("test").model, "alternate");
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  elements.get("model").onchange();
  assert.equal(app.state.current, null);
  assert.equal(app.selectedModel().providerId, "ccswitch_b");
});

test("refresh preserves an unsent model and effort selection within the current supplier", async () => {
  const { app, elements } = providerApp();
  await app.loadModels();
  app.state.current = { id: "thread-a", modelProvider: "ccswitch_a", model: "shared", cwd: "/project" };
  app.selectThreadModel({ thread: app.state.current });
  elements.get("model").value = app.modelKey("ccswitch_a", "alternate");
  elements.get("model").onchange();
  elements.get("effort").value = "low";
  await app.loadModels(true);
  assert.equal(app.selectedModel().model, "alternate");
  assert.equal(app.turnParams("test").effort, "low");
});

test("opening another thread clears the previous thread's explicit reasoning choice", async () => {
  const { app, elements } = providerApp();
  await app.loadModels();
  const first = { id: "first", modelProvider: "ccswitch_a", model: "shared" };
  app.state.current = first;
  app.selectThreadModel({ thread: first, reasoningEffort: "high" });
  elements.get("effort").value = "high";
  const second = { id: "second", modelProvider: "ccswitch_b", model: "shared" };
  app.state.current = second;
  app.selectThreadModel({ thread: second, reasoningEffort: null });
  assert.equal(elements.get("effort").value, "");
  assert.equal(app.turnParams("test").effort, undefined);
});

test("a missing supplier remains distinct from the current Codex configuration", async () => {
  const { app, context, elements, providers } = providerApp();
  await app.loadModels();
  app.state.current = { id: "thread-a", modelProvider: "ccswitch_a", model: "shared", cwd: "/project" };
  app.selectThreadModel({ thread: app.state.current });
  context.rpc = async () => ({ providers: providers.filter((p) => p.id !== "ccswitch_a") });
  await app.loadModels(true);
  assert.equal(app.threadProviderId(), "ccswitch_a");
  assert.equal(app.selectedModel().providerId, "ccswitch_a");
  elements.get("model").value = app.modelKey("", "shared");
  elements.get("model").onchange();
  assert.equal(app.state.current, null, "current Codex model must start a new thread instead of using the missing supplier");
});

function connectionApp(options = {}) {
  const harness = loadApp(new Map(), "", options);
  const { app, context, timers } = harness;
  const sockets = [];
  context.WebSocket = class {
    static CONNECTING = 0;
    static OPEN = 1;
    constructor() { this.readyState = 0; this.sent = []; sockets.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    send(message) { this.sent.push(JSON.parse(message)); }
    receive(envelope) { this.onmessage?.({ data: JSON.stringify(envelope) }); }
    close() { this.readyState = 3; return this.onclose?.(); }
  };
  context.bootstrap = async () => { app.state.loaded = true; context.renderConnection(); };
  app.state.devices = [{ token: "test-token", agent: { agentId: "device", serverName: "Computer" } }];
  app.state.selected = "device";
  app.connect();
  const tick = (id) => {
    assert.ok(timers.has(id), `expected timer ${id}`);
    const { callback } = timers.get(id);
    timers.delete(id);
    callback();
  };
  return { ...harness, sockets, tick };
}

test("foreground reconnect keeps a spinner until the device returns, then shows one success toast", async () => {
  const { app, elements, document, documentListeners, windowListeners, sockets, timers, tick } = connectionApp();
  const first = sockets[0];
  first.open();
  first.receive({ type: "welcome", status: "connected" });
  const notice = elements.get("connection-notice");
  assert.equal(notice.hidden, true, "initial connection is not a reconnection");
  elements.get("prompt").value = "unfinished draft";
  document.hidden = true;
  await first.close();
  assert.equal(notice.dataset.status, "reconnecting");
  assert.equal(elements.get("connection-notice-text").textContent, "连接被断开，重连中");
  assert.equal(notice.hidden, false);
  assert.equal(timers.has(app.state.connectionNoticeTimer), false, "reconnecting does not auto-dismiss");
  const scheduledRetry = app.state.reconnectTimer;
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  windowListeners.get("pageshow")();
  assert.equal(sockets.length, 2, "foreground events share one retry");
  assert.equal(timers.has(scheduledRetry), false);
  const retry = sockets[1];
  retry.open();
  assert.equal(notice.dataset.status, "reconnecting", "an open relay alone is not success");
  retry.receive({ type: "welcome", status: "connecting" });
  assert.equal(notice.dataset.status, "reconnecting");
  app.toast("another notification");
  assert.equal(notice.hidden, false, "ordinary toasts cannot replace the connection notice");
  assert.equal(elements.get("prompt").value, "unfinished draft");
  assert.notEqual(elements.get("prompt").disabled, true);
  retry.receive({ type: "agent_status", status: "connected" });
  assert.equal(notice.dataset.status, "success");
  assert.equal(elements.get("connection-notice-text").textContent, "重连成功");
  const successTimer = app.state.connectionNoticeTimer;
  retry.receive({ type: "agent_status", status: "connected" });
  assert.equal(app.state.connectionNoticeTimer, successTimer, "duplicate statuses do not repeat success");
  assert.equal(timers.get(successTimer).delay, 4500);
  tick(successTimer);
  assert.equal(notice.hidden, true);
});

test("foreground probes healthy sockets and replaces sockets that silently stopped responding", () => {
  const { app, documentListeners, windowListeners, elements, sockets, timers, tick } = connectionApp();
  const first = sockets[0];
  first.open();
  first.receive({ type: "welcome", status: "connected" });
  documentListeners.get("visibilitychange")();
  windowListeners.get("online")();
  assert.equal(first.sent.length, 1, "do not send overlapping probes");
  const ping = first.sent[0];
  const deadline = app.state.pongTimer;
  first.receive({ type: "pong", pingId: "stale-ping" });
  assert.equal(timers.has(deadline), true);
  first.receive({ type: "pong", pingId: ping.pingId });
  assert.equal(timers.has(deadline), false);
  assert.equal(sockets.length, 1, "healthy foreground connections stay open");
  assert.equal(elements.get("connection-notice").hidden, true);
  documentListeners.get("visibilitychange")();
  tick(app.state.pongTimer);
  assert.equal(sockets.length, 2);
  assert.equal(first.readyState, 3);
  assert.equal(app.state.online, false);
  assert.equal(elements.get("connection-notice").dataset.status, "reconnecting");
});

test("retries continue when the auth check stalls; expired pairings stop retrying on foreground", async () => {
  const { app, context, sockets, timers, tick, documentListeners, elements } = connectionApp();
  let rejectAuth;
  context.api = () => new Promise((_resolve, reject) => { rejectAuth = reject; });
  const closing = sockets[0].close();
  tick(app.state.reconnectTimer);
  rejectAuth(Object.assign(new Error("old unauthorized response"), { status: 401 }));
  await closing;
  assert.equal(app.state.pairingExpired, false, "old auth checks cannot change a new connection");
  context.api = async () => { throw Object.assign(new Error("unauthorized"), { status: 401 }); };
  await sockets[1].close();
  assert.equal(app.state.pairingExpired, true);
  assert.equal(timers.has(app.state.reconnectTimer), false);
  assert.equal(elements.get("connection-notice").hidden, true);
  assert.equal(elements.get("connection-label").textContent, "配对已过期");
  documentListeners.get("visibilitychange")();
  assert.equal(sockets.length, 2);
});

test("agent reconnects and background success remain visible until the user returns", () => {
  const { app, sockets, document, documentListeners, elements, timers, tick } = connectionApp();
  const socket = sockets[0];
  socket.open();
  socket.receive({ type: "welcome", status: "connected" });
  socket.receive({ type: "agent_status", status: "connecting" });
  assert.equal(elements.get("connection-notice").dataset.status, "reconnecting");
  document.hidden = true;
  socket.receive({ type: "agent_status", status: "connected" });
  assert.equal(timers.has(app.state.connectionNoticeTimer), false);
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  assert.equal(elements.get("connection-notice").hidden, false);
  assert.equal(timers.get(app.state.connectionNoticeTimer).delay, 4500);
  tick(app.state.connectionNoticeTimer);
  assert.equal(elements.get("connection-notice").hidden, true);
  app.disconnectSocket();
  assert.equal(app.state.reconnecting, false);
  assert.equal(app.state.pingID, null);
  assert.equal(elements.get("connection-notice").hidden, true);
});

test("a stalled connection attempt has a deadline and manual connections clear reconnect state", () => {
  const { app, sockets, tick, elements } = connectionApp();
  tick(app.state.connectionTimer);
  assert.equal(sockets.length, 2);
  assert.equal(app.state.reconnecting, true);
  app.connect();
  assert.equal(sockets.length, 3);
  assert.equal(app.state.reconnecting, false);
  sockets[2].open();
  sockets[2].receive({ type: "welcome", status: "connected" });
  assert.equal(elements.get("connection-notice").hidden, true);
});

test("five seconds hidden suspends the socket until the page becomes visible", async () => {
  const { app, elements, document, documentListeners, windowListeners, sockets, advance } = connectionApp();
  const first = sockets[0];
  first.open();
  first.receive({ type: "welcome", status: "connected" });
  const staleClose = first.onclose;
  app.state.current = { id: "running-thread" };
  app.state.turns = [{ id: "turn", status: "inProgress", items: [] }];
  elements.get("prompt").value = "unfinished draft";
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  advance(4999);
  assert.equal(first.readyState, 1);
  assert.equal(app.state.online, true);
  advance(1);
  assert.equal(first.readyState, 3);
  assert.equal(app.state.socket, null);
  assert.equal(app.state.online, false);
  assert.equal(app.state.pending.size, 0);
  assert.equal(elements.get("connection-notice").hidden, true, "an intentional pause is silent");
  assert.equal(first.sent.length, 0, "hiding the page does not interrupt the running task");
  assert.equal(app.state.current.id, "running-thread");
  assert.equal(app.state.turns[0].status, "inProgress");
  assert.equal(elements.get("prompt").value, "unfinished draft");
  await staleClose();
  first.receive({ type: "agent_status", status: "connected" });
  windowListeners.get("pageshow")();
  windowListeners.get("online")();
  app.connect();
  advance(30000);
  assert.equal(sockets.length, 1, "background callbacks cannot reopen the socket");
  assert.equal(app.state.online, false, "stale messages cannot undo the pause");
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  windowListeners.get("pageshow")();
  assert.equal(sockets.length, 2, "foreground events create a single connection");
  sockets[1].open();
  sockets[1].receive({ type: "welcome", status: "connected" });
  assert.equal(app.state.online, true);
  assert.equal(app.state.loaded, true);
  assert.equal(elements.get("prompt").value, "unfinished draft");
});

test("brief background visits preserve the socket and restart the grace period", () => {
  const { document, documentListeners, sockets, advance } = connectionApp();
  const first = sockets[0];
  first.open();
  first.receive({ type: "welcome", status: "connected" });
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  advance(4999);
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  first.receive({ type: "pong", pingId: first.sent.at(-1).pingId });
  advance(1);
  assert.equal(first.readyState, 1);
  assert.equal(sockets.length, 1);
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  advance(4999);
  assert.equal(first.readyState, 1, "a new background visit gets a full five seconds");
  documentListeners.get("visibilitychange")();
  advance(1);
  assert.equal(first.readyState, 3, "duplicate events cannot extend the grace period");
});

test("a retry during the background grace period still suspends at the original deadline", async () => {
  const { document, documentListeners, sockets, advance } = connectionApp();
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  await sockets[0].close();
  advance(3000);
  assert.equal(sockets.length, 2);
  assert.equal(sockets[1].readyState, 0);
  advance(2000);
  assert.equal(sockets[1].readyState, 3);
  advance(30000);
  assert.equal(sockets.length, 2, "connection deadlines cannot reconnect after suspension");
});

test("suspending an in-flight send releases the composer for the next connection", async () => {
  const { app, elements, document, documentListeners, sockets, advance } = connectionApp();
  sockets[0].open();
  sockets[0].receive({ type: "welcome", status: "connected" });
  app.state.current = { id: "thread" };
  elements.get("prompt").value = "send this";
  const sending = app.submit({ preventDefault() {} });
  assert.equal(app.state.sending, true);
  assert.equal(app.state.pending.size, 1);
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  advance(5000);
  await sending;
  assert.equal(app.state.pending.size, 0);
  assert.equal(app.state.sending, false);
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  elements.get("prompt").value = "next message";
  sockets[1].open();
  sockets[1].receive({ type: "welcome", status: "connected" });
  assert.equal(elements.get("send").disabled, false);
});

test("a background pause preserves expired pairing status", async () => {
  const { app, context, document, documentListeners, sockets, advance } = connectionApp();
  context.api = async () => { throw Object.assign(new Error("unauthorized"), { status: 401 }); };
  await sockets[0].close();
  assert.equal(app.state.pairingExpired, true);
  document.hidden = true;
  documentListeners.get("visibilitychange")();
  advance(5000);
  assert.equal(app.state.pairingExpired, true);
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  advance(30000);
  assert.equal(sockets.length, 1, "visibility changes cannot revive expired credentials");
});

test("a page opened in the background also disconnects after five seconds", () => {
  const { document, documentListeners, sockets, advance } = connectionApp({ hidden: true });
  advance(4999);
  assert.equal(sockets[0].readyState, 0);
  advance(1);
  assert.equal(sockets[0].readyState, 3);
  advance(30000);
  assert.equal(sockets.length, 1);
  document.hidden = false;
  documentListeners.get("visibilitychange")();
  assert.equal(sockets.length, 2);
});

test("pairing links claim the code once and remove it from the address", async () => {
  const { app, claims, history } = loadApp(new Map(), "#view=welcome&pair=ABCD-EFGH");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(claims.length, 1);
  assert.equal(claims[0].code, "ABCD-EFGH");
  assert.equal(app.state.devices[0].token, "token");
  assert.equal(history.url, "/?keep=1#view=welcome");
});

test("pairing dialog click handlers do not use the event as a pair code", () => {
  const { elements, document } = loadApp();
  const pairCode = document.getElementById("pair-code");
  const click = { type: "click", toString: () => "[object PointerEvent]" };
  const buttons = [
    ["add-device", elements.get("add-device")],
    ["empty-device", elements.get("devices").children[0]],
  ];
  for (const [name, button] of buttons) {
    pairCode.value = "stale";
    button.onclick(click);
    assert.equal(pairCode.value, "", name);
  }
});

test("connection button opens device selection instead of the pairing dialog", () => {
  const { app, context, elements, document } = loadApp();
  const deviceDialog = document.getElementById("device-dialog");
  const pairDialog = document.getElementById("pair-dialog");
  let deviceOpen = false;
  let pairOpen = false;
  let connections = 0;
  deviceDialog.showModal = () => { deviceOpen = true; };
  deviceDialog.close = () => { deviceOpen = false; };
  pairDialog.showModal = () => { pairOpen = true; };
  context.connect = () => { connections++; };
  const click = { type: "click", toString: () => "[object PointerEvent]" };

  elements.get("connection-button").onclick(click);
  assert.equal(deviceOpen, true);
  assert.equal(pairOpen, false);
  assert.match(elements.get("device-options").children[0].textContent, /尚未配对设备/);

  app.state.devices = [
    { token: "one", agent: { agentId: "first", serverName: "First" } },
    { token: "two", agent: { agentId: "second", serverName: "Second" } },
  ];
  app.state.selected = "first";
  elements.get("connection-button").onclick(click);
  const options = elements.get("device-options").children;
  assert.equal(options.length, 2);
  assert.match(options[0].className, /selected/);
  options[1].onclick(click);
  assert.equal(deviceOpen, false);
  assert.equal(pairOpen, false);
  assert.equal(app.state.selected, "second");
  assert.equal(connections, 1);
});

test("workspace section collapse persists across reloads", () => {
  const storage = new Map();
  const { app, elements } = loadApp(storage);
  const toggle = elements.get("toggle-workspaces");
  const history = elements.get("workspace-history");
  assert.equal(history.hidden, false);
  assert.equal(toggle["aria-expanded"], "true");

  toggle.onclick({ type: "click" });
  assert.equal(app.state.workspaceSectionCollapsed, true);
  assert.equal(history.hidden, true);
  assert.equal(toggle["aria-expanded"], "false");
  assert.equal(toggle["aria-label"], "展开工作区");
  assert.equal(JSON.parse(storage.get("codexrm.workspaceSectionCollapsed")), true);

  const reloaded = loadApp(storage);
  assert.equal(reloaded.app.state.workspaceSectionCollapsed, true);
  assert.equal(reloaded.elements.get("workspace-history").hidden, true);
  reloaded.elements.get("toggle-workspaces").onclick({ type: "click" });
  assert.equal(reloaded.app.state.workspaceSectionCollapsed, false);
  assert.equal(reloaded.elements.get("workspace-history").hidden, false);
  assert.equal(JSON.parse(storage.get("codexrm.workspaceSectionCollapsed")), false);
});

test("discover every cwd, merge Windows aliases, sort folders and chats by update", () => {
  const { app } = loadApp();
  app.state.info = { os: "windows" };
  app.state.workspaces = [{ name: "Pinned", path: "C:/pinned" }];
  app.state.threads = [
    { id: "old", cwd: "C:/A/", updatedAt: 1 },
    { id: "other", cwd: "C:/B", updatedAt: 20 },
    { id: "new", cwd: "c:\\a", updatedAt: 30 },
  ];
  const groups = app.workspaceGroups();
  assert.deepEqual(Array.from(groups, (g) => g.path), ["c:\\a", "C:/B", "C:/pinned"]);
  assert.deepEqual(Array.from(groups[0].threads, (t) => t.id), ["new", "old"]);
});

test("typing and streaming deltas do not rebuild unrelated composer lists", () => {
  const { app, elements } = runningApp();
  let workspaceRebuilds = 0;
  let queueRebuilds = 0;
  for (const [id, count] of [["workspace-select", () => workspaceRebuilds++], ["message-queue", () => queueRebuilds++]]) {
    const element = elements.get(id);
    const original = element.replaceChildren;
    element.replaceChildren = (...nodes) => { count(); return original.apply(element, nodes); };
  }
  elements.get("prompt").value = "fast input";
  app.resizePrompt();
  app.onEvent({ method: "item/agentMessage/delta", params: { threadId: "thread", turnId: "active", itemId: "reply", delta: "chunk" } });
  assert.equal(workspaceRebuilds, 0);
  assert.equal(queueRebuilds, 0);
  assert.equal(elements.get("send").disabled, false);
});

test("load all pages without cwd or provider restrictions; deduplicate page overlap", async () => {
  const { app, context } = loadApp();
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push({ method, params });
    return params.cursor
      ? { data: [{ id: "a", cwd: "/a", updatedAt: 2 }, { id: "b", cwd: "/b", updatedAt: 1 }], nextCursor: null }
      : { data: [{ id: "a", cwd: "/a", updatedAt: 2 }], nextCursor: "page2" };
  };
  await app.loadThreads();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].params.cwd, undefined);
  assert.equal(calls[0].params.sortDirection, "desc");
  assert.equal(calls[0].params.modelProviders.length, 0);
  assert.equal(app.state.threads.length, 2);
  assert.equal(app.state.threadListLoading, false);
});

test("external interrupted snapshot requires durable evidence before showing stopped", () => {
  const { app } = loadApp();
  app.state.readOnly = true;
  app.state.current = { codexrmTurnStates: { running: "inProgress", stopped: "interrupted", done: "completed" } };
  for (const [id, want] of [["running", "inProgress"], ["stopped", "interrupted"], ["done", "completed"], ["missing", "unknown"]])
    assert.equal(app.displayedTurnStatus({ id, status: "interrupted" }), want);
  app.state.readOnly = false;
  assert.equal(app.displayedTurnStatus({ id: "running", status: "interrupted" }), "interrupted");
});

test("parallel approvals retain answers, await acknowledgements and permit retries", () => {
  const { app, elements } = loadApp();
  const sent = [];
  app.state.online = true;
  app.state.socket = { readyState: 1, send: (raw) => sent.push(JSON.parse(raw)) };
  app.state.current = { id: "thread" };
  app.onEvent({ id: "question", method: "item/tool/requestUserInput", params: { threadId: "thread", questions: [{ id: "q", question: "Answer?" }] } });
  const question = app.state.approvalCards.get("question");
  question.querySelectorAll("input")[0].value = "keep this answer";
  for (const id of ["cmd1", "cmd2", "cmd3"])
    app.onEvent({ id, method: "item/commandExecution/requestApproval", params: { threadId: "thread", turnId: "turn", itemId: "same-item" } });
  assert.equal(app.state.approvalCards.get("question"), question);
  assert.equal(question.querySelectorAll("input")[0].value, "keep this answer");
  elements.get("approval-area").querySelectorAll("button").find((b) => b.textContent === "全部允许 (3)").onclick();
  assert.deepEqual(sent.map((s) => s.message.id), ["cmd1", "cmd2", "cmd3"]);
  assert.equal(app.state.requests.size, 4);
  assert.equal(app.state.responding.size, 3);
  app.onEvent({ method: "codexrm/request/resolved", params: { id: "cmd2" } });
  assert.equal(app.state.requests.has("cmd1"), true);
  assert.equal(app.state.requests.has("cmd2"), false);
  app.onEvent({ method: "codexrm/request/failed", params: { id: "cmd1", message: "retry" } });
  assert.equal(app.state.responding.has("cmd1"), false);
  assert.equal(app.answerRequest("cmd1", { decision: "accept" }), true);
  assert.equal(question.querySelectorAll("input")[0].value, "keep this answer");
});

test("desktop close requires confirmation and calls only the local control RPC", async () => {
  const { app, context, elements } = loadApp();
  const calls = [];
  app.state.online = true;
  context.rpc = async (method) => {
    calls.push(method);
    return { closed: 2, message: "已关闭 2 个 Codex 桌面进程" };
  };
  context.window.confirm = () => false;
  await app.closeCodexDesktop();
  assert.equal(calls.length, 0);
  context.window.confirm = () => true;
  await app.closeCodexDesktop();
  assert.deepEqual(calls, ["codexrm/desktop/close"]);
  assert.equal(elements.get("close-codex-desktop").disabled, false);
});

test("default turn permissions grant full access", () => {
  const { app } = loadApp();
  assert.equal(app.sandboxPolicy().type, "dangerFullAccess");
  app.state.settings.sandbox = "read-only";
  assert.equal(app.sandboxPolicy().networkAccess, false);
});

test("drafts survive workspace switches and reloads without crossing threads", async () => {
  const storage = new Map();
  const { app, elements } = loadApp(storage);
  app.state.selected = "device";
  app.state.info = { os: "windows", cwd: "C:/A" };
  app.state.workspaces = [{ name: "A", path: "C:/A" }, { name: "B", path: "C:/B" }];
  const prompt = elements.get("prompt");

  prompt.value = "workspace A draft";
  app.resizePrompt();
  app.state.current = { id: "thread-one", cwd: "C:/A" };
  prompt.value = "thread draft";
  app.resizePrompt();
  app.newChat();
  assert.equal(prompt.value, "workspace A draft");

  await app.selectWorkspace("C:/B");
  assert.equal(app.state.workspaceViews.device.selected, "C:/B");
  assert.equal(prompt.value, "");
  prompt.value = "workspace B draft";
  app.resizePrompt();
  await app.selectWorkspace("C:/A");
  assert.equal(prompt.value, "workspace A draft");

  app.state.current = { id: "thread-one", cwd: "C:/A" };
  app.restoreDraft();
  assert.equal(prompt.value, "thread draft");
  const reloaded = loadApp(storage);
  reloaded.app.state.selected = "device";
  reloaded.app.state.info = { os: "windows", cwd: "C:/A" };
  reloaded.app.state.current = { id: "thread-one", cwd: "C:/A" };
  reloaded.app.restoreDraft();
  assert.equal(reloaded.elements.get("prompt").value, "thread draft");

  reloaded.elements.get("prompt").value = "";
  reloaded.app.resizePrompt();
  assert.equal(JSON.parse(storage.get("codexrm.drafts.v1"))["device:thread:thread-one"], undefined);
});

test("failed sends restore drafts and successful sends clear them", async () => {
  const storage = new Map();
  const { app, context, elements } = loadApp(storage);
  app.state.selected = "device";
  app.state.devices = [{ token: "token", agent: { agentId: "device" } }];
  app.state.online = true;
  app.state.loaded = true;
  app.state.current = { id: "thread-one", cwd: "/project" };
  const prompt = elements.get("prompt");
  prompt.value = "keep on failure";
  app.resizePrompt();

  context.rpc = async () => { throw new Error("offline"); };
  await app.submit();
  assert.equal(prompt.value, "keep on failure");
  assert.equal(JSON.parse(storage.get("codexrm.drafts.v1"))["device:thread:thread-one"], "keep on failure");

  context.rpc = async () => ({ turn: { id: "turn-one", status: "inProgress", items: [] } });
  await app.submit();
  assert.equal(prompt.value, "");
  assert.equal(JSON.parse(storage.get("codexrm.drafts.v1"))["device:thread:thread-one"], undefined);
});

test("turn completion clears only that thread's requests, including background threads", () => {
  const { app } = loadApp();
  app.state.current = { id: "foreground" };
  for (const threadId of ["foreground", "background"])
    app.onEvent({ id: threadId, method: "item/fileChange/requestApproval", params: { threadId, turnId: "same-turn" } });
  app.onEvent({ method: "turn/completed", params: { threadId: "foreground", turn: { id: "same-turn", status: "completed" } } });
  assert.equal(app.state.requests.has("foreground"), false);
  assert.equal(app.state.requests.has("background"), true);
  app.onEvent({ method: "turn/completed", params: { threadId: "background", turn: { id: "same-turn", status: "completed" } } });
  assert.equal(app.state.requests.size, 0);
});

function runningApp() {
  const loaded = loadApp();
  Object.assign(loaded.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, info: { messageQueue: true },
    current: { id: "thread", cwd: "/project" },
    turns: [{ id: "active", status: "inProgress", items: [] }],
  });
  return loaded;
}

test("Enter queues during a running turn; Ctrl and Command Enter steer that exact turn", async () => {
  const { app, context, elements } = runningApp();
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push({ method, params });
    if (method === "turn/steer") return { turnId: "active" };
    return { threadId: "thread", version: calls.length, entries: [{ id: "queued", input: params.input }] };
  };
  const prompt = elements.get("prompt");
  prompt.value = "do this after";
  app.resizePrompt();
  assert.equal(elements.get("queue-send").disabled, false);
  assert.equal(elements.get("send").hidden, true);
  assert.equal(elements.get("stop").hidden, false);
  await prompt.onkeydown({ key: "Enter", preventDefault() {} });
  assert.equal(calls[0].method, "codexrm/queue/add");
  assert.equal(calls[0].params.input[0].text, "do this after");
  assert.equal(app.state.turns.length, 1);
  assert.equal(prompt.value, "");
  assert.equal(elements.get("message-queue").hidden, false);
  for (const modifier of ["ctrlKey", "metaKey"]) {
    prompt.value = modifier;
    await prompt.onkeydown({ key: "Enter", [modifier]: true, preventDefault() {} });
    assert.equal(calls.at(-1).method, "turn/steer");
    assert.equal(calls.at(-1).params.expectedTurnId, "active");
    assert.equal(calls.at(-1).params.threadId, "thread");
    assert.equal(calls.at(-1).params.model, undefined);
  }
  assert.equal(app.state.turns.length, 1);
  assert.equal(calls.some((c) => c.method === "turn/interrupt"), false);
});

test("steering echoes replace only the matching optimistic message", async () => {
  const { app, context, elements } = runningApp();
  context.rpc = async () => ({ turnId: "active" });
  for (const text of ["first", "second"]) {
    elements.get("prompt").value = text;
    await app.submit(null, "steer");
  }
  const item = { id: "real-first", type: "userMessage", content: [{ type: "text", text: "first", text_elements: [] }] };
  for (const method of ["item/started", "item/completed"])
    app.onEvent({ method, params: { threadId: "thread", turnId: "active", item } });
  assert.equal(app.state.turns[0].items.length, 2);
  assert.equal(app.state.turns[0].items[0].id, "real-first");
  assert.equal(app.state.turns[0].items[1].content[0].text, "second");
});

test("failed queue and steer requests retain the message and any draft typed in flight", async () => {
  for (const mode of ["queue", "steer"]) {
    const { app, context, elements } = runningApp();
    let reject;
    context.rpc = () => new Promise((resolve, fail) => { reject = fail; });
    const prompt = elements.get("prompt");
    prompt.value = "keep failed message";
    const sending = app.submit(null, mode);
    prompt.value = "new draft";
    app.resizePrompt();
    reject(new Error("turn changed or offline"));
    await sending;
    assert.equal(prompt.value, "keep failed message\n\nnew draft");
    assert.equal(app.state.turns[0].items.length, 0);
    assert.equal(app.state.sending, false);
  }
});

test("queue snapshots ignore stale versions and stay scoped to their thread", async () => {
  const { app, context, elements } = runningApp();
  const snapshot = { threadId: "thread", version: 4, entries: [{ id: "a", input: [{ type: "text", text: "waiting" }] }], paused: true };
  app.acceptMessageQueue(snapshot);
  app.acceptMessageQueue({ ...snapshot, version: 3, entries: [] });
  app.onEvent({ method: "codexrm/queue/updated", params: { threadId: "other", version: 20, entries: [] } });
  assert.equal(app.state.messageQueues.get("thread").entries.length, 1);
  assert.equal(elements.get("message-queue").hidden, false);
  assert.equal(elements.get("message-queue").querySelectorAll("button")[0].textContent, "继续排队");
  app.state.turns[0].status = "completed";
  context.rpc = async (method, params) => {
    assert.equal(method, "codexrm/queue/add");
    return { ...snapshot, version: 5, entries: [...snapshot.entries, { id: "b", input: params.input }] };
  };
  elements.get("prompt").value = "after paused messages";
  await app.submit();
  assert.equal(app.state.messageQueues.get("thread").entries.length, 2);
});

test("stop is explicit; composing, read-only, offline and empty messages do not dispatch", async () => {
  const { app, context, elements } = runningApp();
  const calls = [];
  context.rpc = async (method) => { calls.push(method); return {}; };
  const prompt = elements.get("prompt");
  await prompt.onkeydown({ key: "Enter", preventDefault() {} });
  prompt.value = "message";
  await prompt.onkeydown({ key: "Enter", isComposing: true });
  await prompt.onkeydown({ key: "Enter", shiftKey: true });
  app.state.readOnly = true;
  await app.submit(null, "steer");
  app.state.readOnly = false;
  app.state.online = false;
  await app.submit(null, "queue");
  assert.deepEqual(calls, []);
  app.state.online = true;
  await app.stopTurn();
  assert.deepEqual(calls, ["turn/interrupt"]);
  assert.equal(prompt.value, "message");
});
