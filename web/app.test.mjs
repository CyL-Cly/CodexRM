import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

// Run the shipped script with a small DOM double; browser QA covers layout.
function loadApp(storage = new Map(), hash = "", { hidden = false, webgl = null, navigator = {} } = {}) {
  class Element {
    constructor(tagName = "div") {
      this.tagName = tagName.toUpperCase();
      this.children = []; this.dataset = {}; this.style = {
        setProperty(name, value) { this[name] = value; },
        removeProperty(name) { delete this[name]; },
      };
      this.value = ""; this.textContent = ""; this.scrollTop = 0;
      const classes = new Set();
      this.classList = { toggle(name, force = !classes.has(name)) { if (force) classes.add(name); else classes.delete(name); }, add(name) { classes.add(name); }, remove(name) { classes.delete(name); }, contains(name) { return classes.has(name); } };
    }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(name, value) { this[name] = value; }
    removeAttribute(name) { delete this[name]; }
    getAttribute(name) { return this[name] ?? null; }
    addEventListener(type, listener) { (this.listeners ||= new Map()).set(type, listener); }
    closest() { return null; }
    querySelector() { return null; }
    getBoundingClientRect() { return { left: 100, top: 500 }; }
    focus() {}
    select() { this.selected = true; }
    requestSubmit() { return this.onsubmit?.({ preventDefault() {} }); }
    close() { this.open = false; this.listeners?.get("close")?.(); }
    showModal() { this.open = true; }
    setPointerCapture(id) { this.pointerID = id; }
    hasPointerCapture(id) { return this.pointerID === id; }
    releasePointerCapture() { delete this.pointerID; }
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
    window: { innerWidth: 1200, innerHeight: 800, addEventListener(type, listener) { windowListeners.set(type, listener); } },
    location: { origin: "http://localhost", href: `http://localhost/?keep=1${hash}`, protocol: "http:", pathname: "/", search: "?keep=1", hash },
    history, URL, URLSearchParams, console, navigator, Blob, Uint8Array, TextEncoder, btoa, atob,
    matchMedia: () => ({ matches: false }),
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
    app: vm.runInContext("({state, workspaceGroups, renderThreads, renderWorkspacePath, setHome, displayedTurnStatus, loadThreads, onEvent, renderApprovals, sandboxPolicy, answerRequest, newChat, selectWorkspace, restoreDraft, resizePrompt, submit, stopTurn, acceptMessageQueue, loadMessageQueue, connect, disconnectSocket, toast, loadModels, selectThreadModel, selectedModel, threadProviderId, modelKey, turnParams, makeOutboxEntry, stageOutbox, bindOutbox, clearOutbox, syncOutbox, keepOutboxDraft, renderPersistentNotice, addAttachments, currentAttachments, userContent, messageInput, readAttachment, itemHTML, groupOperationItems, operationGroupHTML, rememberDisclosure, disclosureOpen, renderMessages, requestThreadDeletion, archiveThread, openThread, olderTurns, scheduleSnapshot, bootstrap, resetView, selectDevice, settings, updateAgent, createDeviceButton})", context) };
}

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};
const flush = () => new Promise(setImmediate);
function newThreadApp(storage = new Map()) {
  const harness = loadApp(storage);
  Object.assign(harness.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, info: { cwd: "/project" }, current: null,
  });
  return harness;
}

test("home uses a task-focused hero and live device/workspace context", () => {
  const { app, elements } = newThreadApp();
  app.state.devices[0].agent.serverName = "Workstation";
  app.state.info.cwd = "E:\\repo\\CodexRM";
  app.state.info.os = "windows";
  app.renderWorkspacePath();
  assert.match(readFileSync(new URL("index.html", import.meta.url), "utf8"), /<h1 id="welcome-title">要构建什么？<\/h1>/);
  assert.equal(elements.get("welcome-description").textContent, "在 CodexRM 中开始，任务由 Workstation 执行。");
  app.state.browserOnline = false;
  app.renderWorkspacePath();
  assert.match(elements.get("welcome-description").textContent, /浏览器已离线/);
  app.state.browserOnline = true;
  app.state.online = false;
  app.renderWorkspacePath();
  assert.match(elements.get("welcome-description").textContent, /等待本地连接器上线/);
  const empty = loadApp();
  empty.app.renderWorkspacePath();
  assert.equal(empty.elements.get("welcome-description").textContent, "连接你的电脑，让 Codex 继续手边的工作。");
});

test("consecutive operations use one closed card headed by the latest operation", () => {
  const { app } = loadApp();
  const items = [
    { id: "think", type: "reasoning", reasoningText: "先检查实现" },
    { id: "command", type: "commandExecution", command: "pnpm test", aggregatedOutput: "ok", status: "completed", exitCode: 0 },
    { id: "search", type: "webSearch", query: "latest <query>", action: {}, status: "已完成" },
    { id: "reply", type: "agentMessage", text: "处理完成" },
    { id: "single", type: "fileChange", changes: [], status: "completed" },
  ];
  const groups = app.groupOperationItems(items);
  assert.equal(groups.length, 3);
  assert.equal(groups[0].operation, true);
  assert.deepEqual(Array.from(groups[0].items, (item) => item.id), ["think", "command", "search"]);
  assert.deepEqual(Array.from(groups[1].items, (item) => item.id), ["reply"]);
  assert.deepEqual(Array.from(groups[2].items, (item) => item.id), ["single"]);

  const html = app.operationGroupHTML(groups[0].items);
  assert.match(html, /^<details class="operation-group" data-detail="operations:think"/);
  assert.doesNotMatch(html, /^<details[^>]*\sopen[\s>]/);
  assert.match(html, /<span class="operation-group-title[^"]*">搜索 · latest &lt;query&gt;<\/span>/);
  assert.match(html, /<span class="operation-group-meta">3 条操作 · 已完成<\/span>/);
  assert.ok(html.indexOf('data-item-id="think"') < html.indexOf('data-item-id="command"'));
  assert.ok(html.indexOf('data-item-id="command"') < html.indexOf('data-item-id="search"'));
  assert.doesNotMatch(html.match(/<summary[^>]*>.*?<\/summary>/s)[0], /pnpm test/);
});

test("active reasoning shimmers and completed reasoning collapses unless the user chose otherwise", () => {
  const { app } = loadApp();
  const item = { id: "think", type: "reasoning", reasoningText: "先检查实现" };
  const active = app.itemHTML(item, undefined, true);
  assert.match(active, /data-active="true" open/);
  assert.match(active, /aria-expanded="true"/);
  assert.match(active, /class="reasoning-title thinking-shimmer" role="status">正在思考/);
  const completed = app.itemHTML(item, undefined, false);
  assert.match(completed, /data-active="false"/);
  assert.doesNotMatch(completed, /^<details[^>]*\sopen[\s>]/);
  assert.match(completed, /<span class="reasoning-title" role="status">已思考<\/span>/);
  assert.match(completed, /先检查实现/);
  app.rememberDisclosure("think", true);
  assert.match(app.itemHTML(item, undefined, false), /^<details[^>]*\sopen[\s>]/);
  app.rememberDisclosure("think", false);
  assert.doesNotMatch(app.itemHTML(item, undefined, true), /^<details[^>]*\sopen[\s>]/);
});

test("operation groups stay collapsed by default and preserve manual disclosure", () => {
  const { app } = loadApp();
  const items = [
    { id: "think", type: "reasoning", reasoningText: "分析输入" },
    { id: "command", type: "commandExecution", command: "node test.mjs", status: "inProgress" },
  ];
  const active = app.operationGroupHTML(items, true);
  assert.match(active, /data-active="true"/);
  assert.doesNotMatch(active, /^<details[^>]*\sopen[\s>]/);
  assert.match(active, /aria-expanded="false" aria-controls="operations:think-body"/);
  assert.match(active, /aria-label="展开：node test.mjs"/);
  assert.match(active, /operation-group-title thinking-shimmer/);
  assert.match(active, /2 条操作 · 执行中/);
  const completed = app.operationGroupHTML(items, false);
  assert.doesNotMatch(completed, /^<details[^>]*\sopen[\s>]/);
  assert.match(completed, /aria-expanded="false"/);
  app.rememberDisclosure("operations:think", true);
  assert.match(app.operationGroupHTML(items, true), /^<details[^>]*\sopen[\s>]/);
  assert.match(app.operationGroupHTML(items, false), /^<details[^>]*\sopen[\s>]/);
  app.rememberDisclosure("operations:think", false);
  assert.doesNotMatch(app.operationGroupHTML(items, true), /^<details[^>]*\sopen[\s>]/);
});

test("streaming keeps operation groups collapsed unless the user opened them", () => {
  const { app, elements } = loadApp();
  app.state.current = { id: "thread" };
  app.state.turns = [{ id: "turn", status: "inProgress", items: [
    { id: "think", type: "reasoning", reasoningText: "分析" },
    { id: "command", type: "commandExecution", command: "node test.mjs", status: "inProgress" },
  ] }];
  app.renderMessages();
  assert.match(elements.get("messages").innerHTML, /data-detail="operations:think" data-active="true">/);
  assert.doesNotMatch(elements.get("messages").innerHTML, /data-detail="operations:think"[^>]*\sopen/);
  app.rememberDisclosure("operations:think", false);
  app.renderMessages();
  assert.match(elements.get("messages").innerHTML, /data-detail="operations:think" data-active="true">/);
  app.state.turns[0].status = "completed";
  app.renderMessages();
  assert.match(elements.get("messages").innerHTML, /data-detail="operations:think" data-active="false">/);
  app.rememberDisclosure("operations:think", true);
  app.renderMessages();
  assert.match(elements.get("messages").innerHTML, /data-detail="operations:think" data-active="false" open/);
});

test("429 exhaustion enables Continue in the same thread and suppresses duplicate clicks", async () => {
  const { app, context, elements } = newThreadApp();
  const thread = { id: "existing-thread", cwd: "/project", turns: [{ id: "original-turn", status: "inProgress", items: [] }] };
  context.rpc = async () => ({ thread });
  await app.openThread(thread.id);
  const button = elements.get("continue-turn");
  const reportError = (willRetry) => app.onEvent({ method: "error", params: {
    threadId: thread.id, turnId: "original-turn", willRetry, error: { message: "429 Too Many Requests" },
  } });
  reportError(true);
  assert.equal(button.hidden, true);
  reportError(false);
  assert.equal(button.hidden, false);
  assert.equal(button.disabled, false);
  const started = deferred();
  const calls = [];
  context.rpc = (method, params) => { calls.push({ method, params }); return started.promise; };
  const click = button.onclick();
  await button.onclick();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, "turn/start");
  assert.equal(calls[0].params.threadId, thread.id);
  assert.match(calls[0].params.input[0].text, /请从中断处继续/);
  assert.equal(app.state.outbox[0].afterTurnId, "original-turn");
  elements.get("prompt").value = "new draft";
  started.resolve({ turn: { id: "new-turn", status: "inProgress", items: [] } });
  await click;
  assert.equal(elements.get("prompt").value, "new draft");
  assert.equal(button.hidden, true);
  assert.equal(app.state.outbox.length, 0);
});

test("reopening a stopped thread restores Continue and preserves its paused queue", async () => {
  const storage = new Map();
  const { app, context, elements } = newThreadApp(storage);
  const thread = { id: "existing-thread", cwd: "/project", turns: [{ id: "stopped", status: "interrupted", items: [] }] };
  context.rpc = async () => ({ thread });
  await app.openThread(thread.id);
  const button = elements.get("continue-turn");
  assert.equal(button.hidden, false);
  app.acceptMessageQueue({ threadId: thread.id, entries: [{ id: "queued", input: [] }], paused: true });
  context.rpc = async (method) => {
    assert.equal(method, "turn/start");
    throw new Error("device rejected continuation");
  };
  await button.onclick();
  assert.equal(button.hidden, false);
  assert.equal(button.disabled, false);
  assert.equal(elements.get("prompt").value, "");
  assert.equal(app.state.messageQueues.get(thread.id).entries.length, 1);
  context.rpc = async () => { throw Object.assign(new Error("connection closed"), { uncertain: true }); };
  await button.onclick();
  assert.equal(button.disabled, true);
  assert.equal(app.state.outbox.length, 1);
  const reloaded = newThreadApp(storage);
  reloaded.context.rpc = async () => ({ thread });
  await reloaded.app.openThread(thread.id);
  assert.equal(reloaded.elements.get("continue-turn").disabled, true);
});

const gpuRenderer = "ANGLE (AMD, AMD Radeon 780M Graphics (0x00001900) Direct3D11 vs_5_0 ps_5_0, D3D11)";
test("mobile device settings show the Agent version and request a remote update", async () => {
  const { app, context, elements } = loadApp();
  Object.assign(app.state, {
    selected: "device",
    devices: [{ token: "token", agent: { agentId: "device", serverName: "Computer", os: "windows", arch: "amd64", appVersion: "0.6.0" } }],
    online: true,
    info: { version: "0.6.0", remoteUpdate: true, transport: "websocket" },
  });
  context.window.confirm = () => true;
  app.settings();
  assert.match(elements.get("device-detail").textContent, /电脑 Agent v0\.6\.0/);
  assert.equal(elements.get("update-agent").hidden, false);
  assert.match(app.createDeviceButton(app.state.devices[0]).innerHTML, /v0\.6\.0/);
  let method;
  context.rpc = async (name) => { method = name; return { updateAvailable: true, updatePending: true }; };
  await app.updateAgent();
  assert.equal(method, "codexrm/update");
  assert.equal(elements.get("update-agent").disabled, false);
});

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

function providerApp(storage) {
  const loaded = loadApp(storage);
  Object.assign(loaded.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, info: { providerModels: true, cwd: "/project" },
  });
  const model = (name) => ({ model: name, supportedReasoningEfforts: [{ reasoningEffort: "low" }, { reasoningEffort: "high" }] });
  const providers = [
    { id: "", runtimeId: "", name: "Current", models: [model("shared")] },
    { id: "ccswitch_a", runtimeId: "ccswitch_a", name: "A", models: [model("shared"), model("alternate")] },
    { id: "ccswitch_b", runtimeId: "ccswitch_b", name: "B", models: [model("shared"), model("other")] },
  ];
  loaded.context.rpc = async () => ({ providers });
  return { ...loaded, providers };
}

test("the model menu combines the active catalog and searches CC Switch notes", async () => {
  const { app, elements, providers } = providerApp();
  providers[0].aliasOf = "ccswitch_a";
  providers[1].name = providers[2].name = "Sub2API";
  providers[1].notes = "日常使用";
  providers[2].notes = "备用账号";
  app.state.current = { id: "native", modelProvider: "custom", model: "shared" };
  await app.loadModels();
  elements.get("choose-model").onclick();
  const groups = () => elements.get("model-choices").children;
  const labels = (group) => group.children.map((child) => child.textContent || child.children[0]?.textContent);
  assert.equal(groups().length, 2);
  assert.deepEqual(labels(groups()[0]), ["Sub2API", "日常使用", "shared", "alternate"]);
  assert.deepEqual(labels(groups()[1]), ["Sub2API", "备用账号", "shared", "other"]);
  assert.equal(groups()[0].children[2].getAttribute("aria-checked"), "true");
  elements.get("model-search").value = "备用账号";
  elements.get("model-search").oninput();
  assert.deepEqual(groups().map(labels), [["Sub2API", "备用账号", "shared", "other"]]);
});

test("same-provider model changes keep the chat and unsupported changes never open a new chat", async () => {
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
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.selectedModel().providerId, "ccswitch_a");
});

test("switching to a different model and endpoint keeps the chat, history and draft", async () => {
  const { app, context, elements } = providerApp();
  await app.loadModels();
  app.state.info.threadModelProviderSwitch = true;
  app.state.current = { id: "thread-a", modelProvider: "ccswitch_a", model: "shared" };
  app.state.turns = [{ id: "previous", items: [] }];
  app.selectThreadModel({ thread: app.state.current });
  elements.get("effort").value = "high";
  elements.get("prompt").value = "draft";
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push([method, { ...params }]);
    if (method === "codexrm/thread/switchProvider") return { providerId: "ccswitch_b", model: "other" };
    return { turn: { id: "next", status: "inProgress", items: [] } };
  };
  elements.get("model").value = app.modelKey("ccswitch_b", "other");
  await elements.get("model").onchange();
  assert.deepEqual(calls[0], ["codexrm/thread/switchProvider", { threadId: "thread-a", providerId: "ccswitch_b", model: "other" }]);
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.threadProviderId(), "ccswitch_b");
  assert.equal(app.state.turns[0].id, "previous");
  assert.equal(elements.get("prompt").value, "draft");
  assert.equal(app.state.current.model, "other");
  await app.submit();
  assert.deepEqual(calls.map(([method]) => method), ["codexrm/thread/switchProvider", "turn/start"]);
  assert.equal(calls[1][1].threadId, "thread-a");
  assert.equal(calls[1][1].model, "other");
});

test("a failed same-model endpoint switch keeps the old provider and draft", async () => {
  const { app, context, elements } = providerApp();
  await app.loadModels();
  app.state.info.sameModelProviderSwitch = true;
  app.state.current = { id: "thread-a", modelProvider: "ccswitch_a", model: "shared" };
  app.selectThreadModel({ thread: app.state.current });
  elements.get("prompt").value = "draft";
  context.rpc = async () => { throw new Error("busy"); };
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  await elements.get("model").onchange();
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.selectedModel().providerId, "ccswitch_a");
  assert.equal(elements.get("prompt").value, "draft");
  assert.equal(app.state.loading, false);
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
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.selectedModel().providerId, "ccswitch_a");
});

test("opening a conversation with a missing supplier shows history and blocks only sending until switch", async () => {
  const { app, context, elements, providers } = providerApp();
  providers.splice(1, 1);
  await app.loadModels();
  app.state.info.threadModelProviderSwitch = true;
  const calls = [];
  context.rpc = async (method) => {
    calls.push(method);
    if (method === "thread/resume") return { nextCursor: "older", thread: {
      id: "thread-a", modelProvider: "ccswitch_a", model: "shared", historyMode: "paginated",
      codexrmProviderUnavailable: true, turns: [{ id: "previous", status: "interrupted", items: [] }],
    } };
    if (method === "codexrm/thread/switchProvider") return { providerId: "ccswitch_b", model: "other" };
    if (method === "turn/start") return { turn: { id: "next", status: "inProgress", items: [] } };
    throw new Error(`unexpected ${method}`);
  };
  await app.openThread("thread-a");
  assert.equal(app.state.turns[0].id, "previous");
  assert.equal(app.state.historyCursor, "older");
  assert.equal(elements.get("model").disabled, false);
  elements.get("prompt").value = "draft";
  app.resizePrompt();
  assert.equal(elements.get("send").disabled, true);
  assert.equal(elements.get("continue-turn").hidden, true);
  assert.match(elements.get("composer-hint").textContent, /切换到可用的供应商/);
  await app.submit();
  assert.deepEqual(calls, ["thread/resume"]);
  elements.get("model").value = app.modelKey("ccswitch_b", "other");
  await elements.get("model").onchange();
  assert.equal(app.state.current.id, "thread-a");
  assert.equal(app.state.turns[0].id, "previous");
  assert.equal(elements.get("prompt").value, "draft");
  assert.equal(elements.get("send").disabled, false);
  await app.submit();
  assert.deepEqual(calls, ["thread/resume", "codexrm/thread/switchProvider", "turn/start"]);
});

test("new chats keep the current Codex model instead of substituting a catalog default", async () => {
  const { app, providers } = providerApp();
  providers[0].models.push({ model: "gpt-6.2-sol", displayName: "6.2 Sol", isDefault: true });
  await app.loadModels();
  assert.equal(app.selectedModel(), undefined);
  app.newChat();
  assert.equal(app.selectedModel(), undefined);
});

test("new chats reuse the last provider, model and effort across workspaces and reloads", async () => {
  const storage = new Map();
  const { app, elements } = providerApp(storage);
  await app.loadModels();
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  elements.get("model").onchange();
  elements.get("effort").value = "high";
  elements.get("effort").onchange();
  await app.selectWorkspace("/other");
  assert.equal(app.selectedModel().providerId, "ccswitch_b");
  assert.equal(elements.get("effort").value, "high");
  const reloaded = providerApp(storage);
  await reloaded.app.loadModels();
  assert.equal(reloaded.app.selectedModel().providerId, "ccswitch_b");
  assert.equal(reloaded.elements.get("effort").value, "high");
  reloaded.app.newChat();
  assert.equal(reloaded.elements.get("effort").value, "high");
});

test("the last model wins over older workspace preferences and stays scoped to its device", async () => {
  const { app, elements } = providerApp();
  await app.loadModels();
  elements.get("model").value = app.modelKey("ccswitch_a", "alternate");
  elements.get("model").onchange();
  await app.selectWorkspace("/other");
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  elements.get("model").onchange();
  await app.selectWorkspace("/project");
  assert.equal(app.selectedModel().providerId, "ccswitch_b");
  app.state.selected = "another-device";
  app.newChat();
  assert.equal(app.selectedModel(), undefined);
});

test("removed and unavailable last models fall back without leaking the previous effort", async () => {
  const { app, context, elements, providers } = providerApp();
  await app.loadModels();
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  elements.get("model").onchange();
  elements.get("effort").value = "high";
  elements.get("effort").onchange();
  context.rpc = async () => ({ providers: providers.map((p) => ({ ...p, unavailable: p.id === "ccswitch_b" })) });
  await app.loadModels(true);
  assert.equal(app.selectedModel(), undefined);
  assert.equal(elements.get("effort").value, "");
  context.rpc = async () => ({ providers: [] });
  await app.loadModels(true);
  app.newChat();
  assert.equal(app.selectedModel(), undefined);
  elements.get("effort").onchange();
  context.rpc = async () => ({ providers });
  await app.loadModels();
  assert.equal(app.selectedModel().providerId, "ccswitch_b");
});

test("reading history preserves the default until a message uses that model", async () => {
  const { app, context, elements } = providerApp();
  await app.loadModels();
  elements.get("model").value = app.modelKey("ccswitch_b", "shared");
  elements.get("model").onchange();
  app.state.current = { id: "history", modelProvider: "ccswitch_a", model: "alternate" };
  app.selectThreadModel({ thread: app.state.current });
  app.newChat();
  assert.equal(app.selectedModel().providerId, "ccswitch_b");
  app.state.current = { id: "history", modelProvider: "ccswitch_a", model: "alternate" };
  app.selectThreadModel({ thread: app.state.current });
  elements.get("prompt").value = "continue";
  context.rpc = async () => ({ turn: { id: "next", status: "inProgress", items: [] } });
  await app.submit();
  app.newChat();
  assert.equal(app.selectedModel().providerId, "ccswitch_a");
  assert.equal(app.selectedModel().model, "alternate");
});

test("accepted queued and priority messages remember their model; failed sends preserve the earlier choice", async () => {
  for (const mode of ["queue", "steer"]) for (const fail of [false, true]) {
    const { app, context, elements } = providerApp();
    await app.loadModels();
    elements.get("model").value = app.modelKey("ccswitch_b", "shared");
    elements.get("model").onchange();
    app.state.info.messageQueue = true;
    app.state.current = { id: "history", modelProvider: "ccswitch_a", model: "alternate" };
    app.state.turns = [{ id: "active", status: "inProgress", items: [] }];
    app.selectThreadModel({ thread: app.state.current });
    elements.get("prompt").value = "continue";
    context.rpc = async () => {
      if (fail) throw new Error("offline");
      return { threadId: "history", version: 1, entries: [], turnId: "active" };
    };
    await app.submit(null, mode);
    app.newChat();
    assert.equal(app.selectedModel().providerId, fail ? "ccswitch_b" : "ccswitch_a");
  }
});

test("the DSH model menu searches providers and models and applies model and effort choices", async () => {
  const { app, elements, windowListeners } = providerApp();
  await app.loadModels();
  elements.get("model-trigger").onclick();
  assert.equal(elements.get("model-trigger")["aria-expanded"], "true");
  elements.get("choose-model").onclick();
  elements.get("model-search").value = "alternate";
  elements.get("model-search").oninput();
  windowListeners.get("resize")();
  assert.equal(elements.get("model-menu").hidden, false);
  assert.equal(elements.get("model-search").value, "alternate");
  const groups = elements.get("model-choices").children;
  assert.equal(groups.length, 1);
  groups[0].children.at(-1).onclick();
  assert.equal(app.selectedModel().model, "alternate");
  assert.equal(elements.get("model-menu").hidden, true);
  elements.get("model-trigger").onclick();
  elements.get("choose-effort").onclick();
  elements.get("effort-choices").children.at(-1).onclick();
  assert.equal(elements.get("effort").value, "high");
  assert.equal(elements.get("model-effort-label").textContent, "high");
  app.newChat();
  assert.equal(app.selectedModel().model, "alternate");
  assert.equal(elements.get("effort").value, "high");
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
  assert.equal(elements.get("connection-notice").hidden, false);
  assert.equal(elements.get("connection-label").textContent, "配对已过期");
  assert.match(elements.get("connection-notice-text").textContent, /草稿会保留/);
  const repair = elements.get("connection-notice").querySelectorAll("button")[0];
  assert.equal(repair.textContent, "重新配对");
  repair.onclick();
  assert.equal(elements.get("pair-dialog").open, true);
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
  assert.equal(app.state.connectionStatus, "");
  assert.equal(app.state.pingID, null);
  assert.equal(elements.get("connection-notice").hidden, true);
});

test("a stalled connection attempt has a deadline and manual connections clear reconnect state", () => {
  const { app, sockets, tick, elements } = connectionApp();
  tick(app.state.connectionTimer);
  assert.equal(sockets.length, 2);
  assert.equal(app.state.connectionStatus, "reconnecting");
  app.connect();
  assert.equal(sockets.length, 3);
  assert.equal(app.state.connectionStatus, "");
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

test("sidebar pointer resizing clamps width, captures the pointer and persists only the final value", () => {
  const storage = new Map();
  const { elements, document } = loadApp(storage);
  const handle = elements.get("sidebar-resize");
  elements.get("sidebar").getBoundingClientRect = () => ({ width: 260 });
  handle.onpointerdown({ button: 0, pointerId: 1, clientX: 258, preventDefault() {} });
  assert.equal(handle.hasPointerCapture(1), true);
  assert.equal(document.body.classList.contains("sidebar-resizing"), true);
  handle.onpointermove({ pointerId: 2, clientX: 500 });
  assert.equal(handle["aria-valuenow"], "260");
  handle.onpointermove({ pointerId: 1, clientX: 438 });
  assert.equal(document.documentElement.style["--sidebar-width"], "440px");
  assert.equal(storage.has("codexrm.sidebarWidth"), false);
  handle.onpointermove({ pointerId: 1, clientX: 5000 });
  assert.equal(handle["aria-valuenow"], "520");
  handle.onpointerup({ pointerId: 1 });
  assert.equal(handle.hasPointerCapture(1), false);
  assert.equal(document.body.classList.contains("sidebar-resizing"), false);
  assert.equal(JSON.parse(storage.get("codexrm.sidebarWidth")), 520);
  assert.equal(loadApp(storage).elements.get("sidebar-resize")["aria-valuenow"], "520");
  handle.onpointerdown({ button: 0, pointerId: 3, clientX: 260, preventDefault() {} });
  handle.onpointermove({ pointerId: 3, clientX: -500 });
  handle.onpointercancel({ pointerId: 3 });
  assert.equal(JSON.parse(storage.get("codexrm.sidebarWidth")), 220);
});

test("sidebar width supports keys and reset, adapts to the viewport and ignores mobile or collapsed drags", () => {
  const storage = new Map([["codexrm.sidebarWidth", "420"]]);
  const { elements, context, document, windowListeners } = loadApp(storage);
  const handle = elements.get("sidebar-resize");
  handle.onkeydown({ key: "ArrowRight", preventDefault() {} });
  assert.equal(handle["aria-valuenow"], "430");
  context.window.innerWidth = 800;
  windowListeners.get("resize")();
  assert.equal(handle["aria-valuenow"], "360");
  assert.equal(JSON.parse(storage.get("codexrm.sidebarWidth")), 430);
  handle.onkeydown({ key: "Home", preventDefault() {} });
  assert.equal(handle["aria-valuenow"], "220");
  handle.onkeydown({ key: "End", preventDefault() {} });
  assert.equal(handle["aria-valuenow"], "360");
  handle.ondblclick();
  assert.equal(handle["aria-valuenow"], "240");
  assert.equal(document.documentElement.style["--sidebar-width"], undefined);
  assert.equal(JSON.parse(storage.get("codexrm.sidebarWidth")), null);
  context.matchMedia = () => ({ matches: true });
  handle.onpointerdown({ button: 0, pointerId: 1, clientX: 240, preventDefault() { assert.fail("mobile drag"); } });
  context.matchMedia = () => ({ matches: false });
  document.body.classList.add("sidebar-collapsed");
  handle.onpointerdown({ button: 0, pointerId: 2, clientX: 240, preventDefault() { assert.fail("collapsed drag"); } });
});

function deletionApp() {
  const harness = loadApp();
  harness.app.state.online = true;
  harness.app.state.loaded = true;
  harness.app.state.selected = "device";
  harness.app.state.current = { id: "current", name: "当前对话", cwd: "/project" };
  harness.app.state.threads = [{ ...harness.app.state.current }, { id: "other", name: "另一个对话", cwd: "/project" }];
  harness.app.renderThreads();
  return harness;
}

test("right-click targets the listed conversation without opening it and confirmation deletes only that target", async () => {
  const { app, elements, context } = deletionApp();
  const calls = [];
  context.rpc = async (method, params) => { calls.push({ method, params }); return {}; };
  const row = elements.get("workspaces").children[0].children[1].children.find(row => row.dataset.threadId === "other");
  row.oncontextmenu({ clientX: 1195, clientY: 795, preventDefault() {} });
  assert.equal(elements.get("thread-menu").hidden, false);
  assert.equal(elements.get("thread-menu").style.left, "1024px");
  assert.equal(elements.get("thread-menu").style.top, "744px");
  assert.equal(app.state.current.id, "current");
  elements.get("delete-thread").onclick();
  assert.equal(elements.get("delete-thread-dialog").open, true);
  assert.equal(elements.get("delete-thread-name").textContent, "另一个对话");
  assert.equal(calls.length, 0);
  await elements.get("confirm-delete-thread").onclick();
  assert.deepEqual(calls.map(({ method, params }) => [method, params.threadId]), [["thread/archive", "other"]]);
  assert.deepEqual(Array.from(app.state.threads, t => t.id), ["current"]);
  assert.equal(app.state.current.id, "current");
  assert.equal(elements.get("delete-thread-dialog").open, false);
});

test("cancelling deletion makes no request; failures keep the conversation and allow a retry", async () => {
  const { app, elements, context } = deletionApp();
  let calls = 0;
  context.rpc = async () => { calls++; throw new Error("archive failed"); };
  app.requestThreadDeletion("other");
  elements.get("delete-thread-dialog").close();
  await elements.get("confirm-delete-thread").onclick();
  assert.equal(calls, 0);
  app.requestThreadDeletion("other");
  await elements.get("confirm-delete-thread").onclick();
  assert.equal(calls, 1);
  assert.equal(elements.get("delete-thread-dialog").open, true);
  assert.equal(elements.get("delete-thread-error").textContent, "archive failed");
  assert.equal(elements.get("confirm-delete-thread").disabled, false);
  assert.equal(app.state.threads.length, 2);
  context.rpc = async () => ({});
  await elements.get("confirm-delete-thread").onclick();
  assert.equal(app.state.threads.length, 1);
});

test("deleting the current conversation returns home and duplicate confirmation sends only once", async () => {
  const { app, elements, context } = deletionApp();
  let resolve;
  let calls = 0;
  context.rpc = () => { calls++; return new Promise(r => resolve = r); };
  app.requestThreadDeletion("current");
  const pending = elements.get("confirm-delete-thread").onclick();
  await elements.get("confirm-delete-thread").onclick();
  assert.equal(calls, 1);
  resolve({});
  await pending;
  assert.equal(app.state.current, null);
  assert.equal(elements.get("welcome").hidden, false);
  assert.deepEqual(Array.from(app.state.threads, t => t.id), ["other"]);
});

test("offline and running conversations cannot be deleted and keyboard menus close on Escape", () => {
  const { app, elements } = deletionApp();
  const row = elements.get("workspaces").children[0].children[1].children.find(row => row.dataset.threadId === "other");
  app.state.online = false;
  row.onkeydown({ key: "F10", shiftKey: true, preventDefault() {} });
  assert.equal(elements.get("delete-thread").disabled, true);
  elements.get("thread-menu").listeners.get("keydown")({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  assert.equal(elements.get("thread-menu").hidden, true);
  row.onkeydown({ key: "ContextMenu", preventDefault() {} });
  elements.get("search").oninput();
  assert.equal(elements.get("thread-menu").hidden, true);
  row.onkeydown({ key: "ContextMenu", preventDefault() {} });
  elements.get("sidebar").listeners.get("scroll")();
  assert.equal(elements.get("thread-menu").hidden, true);
  app.state.online = true;
  app.state.threads[1].status = { type: "active" };
  app.requestThreadDeletion("other");
  assert.notEqual(elements.get("delete-thread-dialog").open, true);
});

test("late archive results preserve a newly selected conversation or device", async () => {
  for (const switchDevice of [false, true]) {
    const { app, context } = deletionApp();
    let resolve;
    context.rpc = () => new Promise(r => resolve = r);
    const pending = app.archiveThread("current");
    app.state.current = { id: "other" };
    if (switchDevice) {
      app.state.generation++;
      app.state.selected = "new-device";
      app.state.threads = [{ id: "current" }];
    }
    resolve({});
    await pending;
    assert.equal(app.state.current.id, "other");
    assert.equal(app.state.threads.some(t => t.id === "current"), switchDevice);
  }
});

test("an in-flight history response cannot restore an archived conversation", async () => {
  const { app, context } = deletionApp();
  let resolve;
  context.rpc = () => new Promise(r => resolve = r);
  const pending = app.loadThreads();
  app.onEvent({ method: "thread/archived", params: { threadId: "other" } });
  resolve({ data: [{ id: "current" }, { id: "other" }], nextCursor: null });
  await pending;
  assert.deepEqual(Array.from(app.state.threads, t => t.id), ["current"]);
  assert.equal(app.state.threadListLoading, false);
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

test("folders over 15 show their 10 most recently active chats and reveal older chats independently", () => {
  const { app, elements } = loadApp();
  app.state.selected = "device-a";
  app.state.threads = [["large", 16], ["other", 17], ["small", 15]].flatMap(([folder, size]) =>
    Array.from({ length: size }, (_, index) => ({
      id: `${folder}-${index + 1}`,
      name: `${folder} chat ${index + 1}`,
      cwd: `/${folder}`,
      updatedAt: index + 1,
    })),
  );
  const history = (folder) => elements.get("workspaces").children.find((g) => g.dataset.path === `/${folder}`)?.children[1];
  const ids = (folder) => history(folder).children.flatMap((row) => row.dataset.threadId ? [row.dataset.threadId] : []);
  const older = (folder) => history(folder).children.find((row) => row.className === "workspace-older-threads");
  app.renderThreads();
  assert.deepEqual(ids("large"), Array.from({ length: 10 }, (_, i) => `large-${16 - i}`));
  assert.equal(ids("small").length, 15);
  assert.equal(older("small"), undefined);
  assert.equal(older("large").textContent, "展开其余 6 条对话");
  assert.equal(older("large").getAttribute("aria-expanded"), "false");

  older("large").onclick();
  assert.equal(ids("large").length, 16);
  assert.equal(older("large").getAttribute("aria-expanded"), "true");
  assert.equal(ids("other").length, 10);
  app.state.selected = "device-b";
  app.renderThreads();
  assert.equal(ids("large").length, 10);
  app.state.selected = "device-a";
  app.renderThreads();
  assert.equal(ids("large").length, 16);
  older("large").onclick();
  assert.equal(ids("large").length, 10);

  elements.get("search").value = "large chat 2";
  app.renderThreads();
  assert.deepEqual(ids("large"), ["large-2"]);
  assert.equal(older("large"), undefined);
  elements.get("search").value = "";
  app.onEvent({ method: "turn/started", params: { threadId: "large-1", turn: { id: "turn" } } });
  assert.equal(ids("large")[0], "large-1");
  assert.equal(ids("large").length, 10);
});

test("running conversations show a spinner before the title and clear it on completion", () => {
  const { app, elements } = loadApp();
  app.state.current = { id: "thread", name: "运行任务", cwd: "/project" };
  app.state.threads = [{ ...app.state.current, status: { type: "idle" } }];
  app.renderThreads();
  const button = () => elements.get("workspaces").children[0].children[1].children[0].children[0];
  assert.equal(button().children[0].className, "thread-name");
  app.onEvent({ method: "turn/started", params: {
    threadId: "thread", turn: { id: "turn", status: "inProgress", items: [] },
  } });
  assert.equal(button().children[0].className, "thread-spinner");
  assert.equal(button().children[0]["aria-hidden"], "true");
  assert.equal(button().children[1].textContent, "运行任务");
  assert.equal(button().children[2].textContent, "进行中");
  app.onEvent({ method: "turn/completed", params: {
    threadId: "thread", turn: { id: "turn", status: "completed", items: [] },
  } });
  assert.equal(button().children.length, 1);
  assert.equal(button().children[0].className, "thread-name");
});

test("streaming lifecycle is announced once without flooding the message log", () => {
  const { app, elements } = loadApp();
  app.state.current = { id: "thread", name: "任务", cwd: "/project" };
  app.onEvent({ method: "turn/started", params: {
    threadId: "thread", turn: { id: "turn", status: "inProgress", items: [] },
  } });
  assert.match(elements.get("turn-status").textContent, /开始处理/);
  app.onEvent({ method: "item/agentMessage/delta", params: { threadId: "thread", turnId: "turn", itemId: "reply", delta: "chunk" } });
  assert.match(elements.get("turn-status").textContent, /开始处理/, "deltas must not re-announce");
  app.onEvent({ method: "turn/completed", params: {
    threadId: "thread", turn: { id: "turn", status: "completed", items: [] },
  } });
  assert.match(elements.get("turn-status").textContent, /已完成/);
  app.onEvent({ method: "turn/completed", params: {
    threadId: "thread", turn: { id: "turn2", status: "completed", error: { message: "boom" }, items: [] },
  } });
  assert.match(elements.get("turn-status").textContent, /失败：boom/);
});

test("conversation title edits inline and retains the dialog rename entry", async () => {
  const { app, context, elements } = loadApp();
  app.state.current = { id: "thread", name: "旧标题", cwd: "/project" };
  app.state.threads = [{ ...app.state.current }];
  app.setHome(false);
  assert.equal(elements.get("header-title").hidden, true);
  assert.equal(elements.get("header-divider").hidden, true);
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push({ method, params });
    return {};
  };
  const button = elements.get("header-subtitle");
  const form = elements.get("title-rename-form");
  const input = elements.get("title-rename-input");
  button.onclick();
  assert.equal(form.hidden, false);
  assert.equal(button.hidden, true);
  assert.equal(input.value, "旧标题");
  assert.equal(input.selected, true);
  input.value = "  新标题  ";
  await form.requestSubmit();
  assert.equal(calls[0].method, "thread/name/set");
  assert.equal(calls[0].params.threadId, "thread");
  assert.equal(calls[0].params.name, "新标题");
  assert.equal(app.state.current.name, "新标题");
  assert.equal(app.state.threads[0].name, "新标题");
  assert.equal(button.textContent, "新标题");
  assert.equal(form.hidden, true);
  assert.equal(button.hidden, false);

  elements.get("thread-actions").onclick();
  elements.get("thread-name").value = "对话框标题";
  await elements.get("rename-form").requestSubmit();
  assert.equal(app.state.current.name, "对话框标题");
  assert.equal(calls.length, 2);

  button.onclick();
  input.value = "不保存";
  let prevented = false;
  input.onkeydown({ key: "Escape", preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(form.hidden, true);
  assert.equal(button.hidden, false);
  assert.equal(app.state.current.name, "对话框标题");
  assert.equal(calls.length, 2);
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
  question.querySelectorAll("textarea")[0].value = "keep this answer";
  for (const id of ["cmd1", "cmd2", "cmd3"])
    app.onEvent({ id, method: "item/commandExecution/requestApproval", params: { threadId: "thread", turnId: "turn", itemId: "same-item" } });
  assert.equal(app.state.approvalCards.get("question"), question);
  assert.equal(question.querySelectorAll("textarea")[0].value, "keep this answer");
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
  assert.equal(question.querySelectorAll("textarea")[0].value, "keep this answer");
});

test("command approvals expose only decisions offered by Codex", () => {
  const { app, elements } = loadApp();
  const sent = [];
  app.state.online = true;
  app.state.socket = { readyState: 1, send: (raw) => sent.push(JSON.parse(raw)) };
  app.state.current = { id: "thread" };
  const method = "item/commandExecution/requestApproval";
  app.onEvent({ id: "one-off", method, params: { threadId: "thread", availableDecisions: ["accept", "cancel"] } });
  app.onEvent({ id: "session", method, params: { threadId: "thread", availableDecisions: ["accept", "acceptForSession", "decline"] } });
  const buttons = app.state.approvalCards.get("one-off").querySelectorAll("button");
  assert.deepEqual(buttons.map((button) => button.textContent), ["允许一次", "取消当前任务"]);
  assert.equal(elements.get("approval-area").querySelectorAll("button").some((b) => b.textContent === "全部拒绝"), false);
  buttons[1].onclick();
  assert.deepEqual(sent.map(({ message }) => message.result), [{ decision: "cancel" }]);
});

test("Codex questions submit selected or custom answers, multiline text and secrets with retry", () => {
  for (const customAnswer of [false, true]) {
    const { app } = loadApp();
    const sent = [];
    app.state.online = true;
    app.state.socket = { readyState: 1, send: (raw) => sent.push(JSON.parse(raw)) };
    app.state.current = { id: "thread" };
    app.onEvent({ id: 0, method: "item/tool/requestUserInput", params: {
      threadId: "thread", turnId: "turn", isBlocking: false,
      questions: [
        { id: "choice", header: "方式", question: "选择实现方式？", options: [
          { label: "直接实现（推荐）", description: "修改并测试。" },
          { label: "先看方案", description: "先解释方案。" },
        ] },
        { id: "details", header: "要求", question: "补充要求？" },
        { id: "secret", header: "密钥", question: "输入测试值？", isSecret: true },
      ],
    } });
    const card = app.state.approvalCards.get(0);
    assert.equal(card.querySelectorAll("span").some((span) => span.textContent === "先解释方案。"), true);
    const form = card.querySelectorAll("form")[0];
    form.requestSubmit();
    assert.equal(sent.length, 0, "blank answers must not silently submit");
    const [choice, details] = card.querySelectorAll("textarea");
    const radios = card.querySelectorAll("input").filter((input) => input.type === "radio");
    assert.equal(radios[0].checked, true);
    if (customAnswer) {
      choice.value = "我自己的方案";
      choice.oninput();
      assert.equal(radios.some((radio) => radio.checked), false);
    } else {
      choice.value = "previous custom draft";
      radios[0].checked = false;
      radios[1].checked = true;
      radios[1].onchange();
      assert.equal(choice.value, "");
    }
    details.value = "保留中文\n以及第二行";
    const secret = card.querySelectorAll("input").find((input) => input.type === "password");
    assert.equal(secret.autocomplete, "off");
    secret.value = "test-only-value";
    assert.equal(sent.length, 0, "selecting an option requires an explicit submit");
    form.requestSubmit();
    form.requestSubmit();
    assert.equal(sent.length, 1, "an in-flight answer must not be sent twice");
    assert.deepEqual(sent[0].message, { id: 0, result: { answers: {
      choice: { answers: [customAnswer ? "我自己的方案" : "先看方案"] },
      details: { answers: ["保留中文\n以及第二行"] },
      secret: { answers: ["test-only-value"] },
    } } });
    assert.equal(card.querySelectorAll("button, input, textarea").every((control) => control.disabled), true);
    app.onEvent({ method: "codexrm/request/failed", params: { id: 0, message: "test retry" } });
    assert.equal(details.disabled, false);
    assert.equal(details.value, "保留中文\n以及第二行");
    form.requestSubmit();
    assert.equal(sent.length, 2);
    app.onEvent({ method: "codexrm/request/resolved", params: { id: 0 } });
    assert.equal(app.state.approvalCards.has(0), false);
  }
});

test("questions can be explicitly skipped without fabricating empty answers", () => {
  const { app } = loadApp();
  const sent = [];
  app.state.online = true;
  app.state.socket = { readyState: 1, send: (raw) => sent.push(JSON.parse(raw)) };
  app.onEvent({ id: "skip-question", method: "item/tool/requestUserInput", params: {
    questions: [{ id: "q", question: "可选问题？" }],
  } });
  app.state.approvalCards.get("skip-question").querySelectorAll("button").find((button) => button.textContent === "跳过").onclick();
  assert.deepEqual(sent[0].message, { id: "skip-question", result: { answers: {} } });
});

test("async questions submit the selected answer in the original thread and recognize it in history", async () => {
  const { app, context, elements } = loadApp();
  const { openAsyncQuestions, questionStatuses } = vm.runInContext("({ openAsyncQuestions, questionStatuses })", context);
  const item = {
    type: "agentMessage", id: "call_1", delivery: "async", text: "选择？\n- 第一项\n- 第二项",
    questions: [{ title: "选择？", options: ["第一项", "第二项"] }, { title: "补充要求？" }],
  };
  app.state.current = { id: "thread" };
  app.state.online = true;
  app.state.turns = [{ id: "turn", status: "completed", items: [item] }];
  elements.get("prompt").value = "未发送草稿";
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push({ method, params });
    return { turn: { id: "next" } };
  };
  assert.match(app.itemHTML(item), /data-answer-question="call_1"/);
  openAsyncQuestions(item);
  const fields = elements.get("async-question-fields");
  const form = fields.querySelectorAll("form")[0];
  await form.requestSubmit();
  assert.equal(calls.length, 0);
  const radios = fields.querySelectorAll("input");
  radios[0].checked = false;
  radios[1].checked = true;
  radios[1].onchange();
  fields.querySelectorAll("textarea")[1].value = "保留\n换行";
  await Promise.all([form.requestSubmit(), form.requestSubmit()]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, "turn/start");
  assert.equal(calls[0].params.threadId, "thread");
  assert.equal(elements.get("prompt").value, "未发送草稿");
  const { input } = calls[0].params;
  assert.deepEqual(JSON.parse(input[0].text.split("\n")[1]), [
    { questionItemId: '["request_user_input_async","call_1",0]', question: "选择？", answer: "第二项" },
    { questionItemId: '["request_user_input_async","call_1",1]', question: "补充要求？", answer: "保留\n换行" },
  ]);
  const restored = loadApp();
  const restoredStatuses = vm.runInContext("questionStatuses", restored.context);
  assert.equal(restoredStatuses([item, { type: "userMessage", content: input }]).get(item.id), "已回答");
  assert.equal(restored.app.userContent(input).text, "选择？\n第二项\n\n补充要求？\n保留\n换行");
  assert.equal(questionStatuses([item]).get(item.id), "已回答");
});

test("nonblocking questions remain answerable after completion until resolved or interrupted", () => {
  const { app } = loadApp();
  app.state.current = { id: "thread" };
  for (const [id, isBlocking] of [["async", false], ["blocking", true], ["legacy", undefined]])
    app.onEvent({ id, method: "item/tool/requestUserInput", params: {
      threadId: "thread", turnId: "turn", isBlocking, questions: [{ id: "q", question: "继续？" }],
    } });
  const card = app.state.approvalCards.get("async");
  const field = card.querySelectorAll("textarea")[0];
  field.value = "keep this draft";
  app.onEvent({ method: "turn/completed", params: { threadId: "thread", turn: { id: "turn", status: "completed", items: [] } } });
  assert.deepEqual([...app.state.requests.keys()], ["async"]);
  assert.equal(app.state.approvalCards.get("async"), card);
  assert.equal(field.value, "keep this draft");
  app.onEvent({ method: "serverRequest/resolved", params: { threadId: "thread", requestId: "async" } });
  assert.equal(app.state.requests.size, 0);
  for (const status of ["interrupted", "failed"]) {
    app.onEvent({ id: status, method: "item/tool/requestUserInput", params: {
      threadId: "thread", turnId: status, isBlocking: false, questions: [{ id: "q", question: "继续？" }],
    } });
    app.onEvent({ method: "turn/completed", params: { threadId: "thread", turn: { id: status, status, items: [] } } });
    assert.equal(app.state.requests.size, 0);
  }
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

test("an uncertain send keeps the outbox record and never restores an ambiguous draft", async () => {
  const storage = new Map();
  const { app, context, elements } = loadApp(storage);
  app.state.selected = "device";
  app.state.devices = [{ token: "token", agent: { agentId: "device" } }];
  app.state.online = true;
  app.state.loaded = true;
  app.state.current = { id: "thread-one", cwd: "/project" };
  const prompt = elements.get("prompt");
  prompt.value = "maybe delivered";
  app.resizePrompt();

  context.rpc = async () => {
    const error = new Error("请求超时，请检查本地 Codex 是否正常运行");
    error.uncertain = true;
    throw error;
  };
  await app.submit();
  assert.equal(JSON.parse(storage.get("codexrm.outbox.v1")).length, 1, "an unknown outcome must keep the pending record");
  assert.equal(prompt.value, "", "ambiguous text must not be restored into the composer");
  assert.equal(elements.get("connection-notice").hidden, false);
  assert.match(elements.get("connection-notice-text").textContent, /发送状态未确认/);
});

test("the Agent's unknown-outcome response preserves the outbox while a rejection restores the draft", async () => {
  for (const code of [-32098, -32602]) {
    const { app, elements, sockets } = connectionApp();
    const socket = sockets[0];
    socket.open();
    socket.receive({ type: "welcome", status: "connected" });
    app.state.current = { id: "thread", cwd: "/project" };
    const prompt = elements.get("prompt");
    prompt.value = "original message";
    app.resizePrompt();
    const submitted = app.submit();
    const request = socket.sent.findLast((envelope) => envelope.message?.method === "turn/start");
    assert.ok(request);
    socket.receive({ type: "server_message", message: { id: request.message.id, error: { code, message: "upstream error" } } });
    await submitted;
    assert.equal(app.state.outbox.length, code === -32098 ? 1 : 0);
    assert.equal(prompt.value, code === -32098 ? "" : "original message");
  }
});

test("outbox persists complete send context until explicit RPC success and preserves newer edits", async () => {
  const storage = new Map();
  const { app, context, elements } = runningApp(storage);
  app.state.turns = [];
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
  const file = { ...readyAttachment(), file: new File(["secret"], "报告.txt") };
  app.state.attachmentDrafts["device:thread:thread"] = [file];
  elements.get("prompt").value = "message";
  const turn = deferred();
  context.rpc = () => turn.promise;
  const sending = app.submit();
  const notice = elements.get("connection-notice");
  assert.equal(notice.hidden, true, "waiting for the send response does not show a connection notice");
  const stored = JSON.parse(storage.get("codexrm.outbox.v1"));
  assert.equal(stored.length, 1);
  assert.ok(stored[0].id);
  assert.ok(stored[0].createdAt);
  assert.equal(stored[0].agentId, "device");
  assert.equal(stored[0].threadId, "thread");
  assert.equal(stored[0].cwd, "/project");
  assert.equal(stored[0].text, "message");
  assert.equal(stored[0].files[0].path, file.path);
  assert.equal(stored[0].files[0].file, undefined);
  elements.get("prompt").value = "new edit";
  app.resizePrompt();
  turn.resolve({ turn: { id: "turn", status: "inProgress", items: [] } });
  await sending;
  assert.deepEqual(JSON.parse(storage.get("codexrm.outbox.v1")), []);
  assert.equal(elements.get("prompt").value, "new edit");
  assert.equal(notice.hidden, true, "waiting for the AI reply does not show a connection notice");
});

test("an interrupted first turn reloads bound to its new thread without resending", async () => {
  const storage = new Map();
  const { app, context, elements } = newThreadApp(storage);
  const started = deferred();
  const turn = deferred();
  context.rpc = (method) => method === "thread/start" ? started.promise : turn.promise;
  elements.get("prompt").value = "possibly sent";
  const sending = app.submit();
  started.resolve({ thread: { id: "created", cwd: "/project", turns: [] } });
  await flush();
  const entry = JSON.parse(storage.get("codexrm.outbox.v1"))[0];
  assert.equal(entry.threadId, "created");
  assert.equal(entry.draftKey, "device:thread:created");

  const reloaded = loadApp(storage);
  Object.assign(reloaded.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, current: { id: "created", cwd: "/project" }, info: { cwd: "/project" },
  });
  reloaded.context.rpc = async () => { assert.fail("recovery must not resend automatically"); };
  reloaded.app.renderPersistentNotice();
  const notice = reloaded.elements.get("connection-notice");
  assert.equal(notice.hidden, false);
  assert.match(reloaded.elements.get("connection-notice-text").textContent, /发送状态未确认.*不会自动重发/);
  const buttons = notice.querySelectorAll("button");
  assert.deepEqual(buttons.map((button) => button.textContent), ["同步会话", "确认已发送", "保留为新草稿"]);
  let synced;
  reloaded.context.openThread = async (id) => { synced = id; };
  await buttons[0].onclick();
  assert.equal(synced, "created");
  assert.equal(JSON.parse(storage.get("codexrm.outbox.v1")).length, 1, "sync alone does not assume failure");
  reloaded.elements.get("prompt").value = "new edit";
  buttons[2].onclick();
  assert.equal(reloaded.elements.get("prompt").value, "possibly sent\n\nnew edit");
  assert.deepEqual(JSON.parse(storage.get("codexrm.outbox.v1")), []);
  assert.equal(notice.hidden, true, "clearing the outbox dismisses its notice");
  void sending;
});

test("outbox entries persist only attachment metadata", () => {
  const { app } = loadApp();
  const entry = app.makeOutboxEntry({
    id: "id", createdAt: "now", agentId: "agent", threadId: "thread", cwd: "/cwd",
    draftKey: "agent:thread:thread", text: "text",
    files: [{ id: "file", name: "a.txt", path: "/a", size: 1, mimeType: "text/plain", file: "bytes", uploading: true }],
  });
  assert.deepEqual(JSON.parse(JSON.stringify(entry.files)), [{ id: "file", name: "a.txt", path: "/a", size: 1, mimeType: "text/plain" }]);
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

function runningApp(storage) {
  const loaded = loadApp(storage);
  Object.assign(loaded.app.state, {
    selected: "device", devices: [{ token: "token", agent: { agentId: "device" } }],
    online: true, loaded: true, info: { messageQueue: true },
    current: { id: "thread", cwd: "/project" },
    turns: [{ id: "active", status: "inProgress", items: [] }],
  });
  return loaded;
}

const readyAttachment = (overrides = {}) => ({
  id: "12345678-1234-4234-9234-123456789abc", name: "报告.txt", path: "/agent/attachments/file-report.txt",
  size: 3, mimeType: "text/plain", ...overrides,
});

test("image paste reaches the composer from the editor and elsewhere on the page exactly once", async () => {
  for (const source of ["prompt", "body"]) {
    const { app, context, elements, document, documentListeners } = runningApp();
    app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
    const image = new File(["png"], "clipboard.png", { type: "image/png" });
    let uploads = 0;
    context.rpc = async (method) => {
      if (method.endsWith("begin")) { uploads++; return { id: "image" }; }
      if (method.endsWith("chunk")) return { offset: 3 };
      return readyAttachment({ name: image.name, mimeType: image.type });
    };
    const event = {
      target: source === "prompt" ? elements.get("prompt") : document.body,
      clipboardData: { items: [{ kind: "string" }, { kind: "file", getAsFile: () => image }], files: [image] },
      preventDefault() { this.defaultPrevented = true; },
    };
    await documentListeners.get("paste")(event);
    assert.equal(event.defaultPrevented, true);
    assert.equal(uploads, 1);
    assert.equal(app.currentAttachments()[0].mimeType, "image/png");
    await documentListeners.get("paste")(event);
    assert.equal(uploads, 1);
  }
});

test("clipboard file lists work without items and plain text keeps native paste", async () => {
  const { app, context, document, documentListeners } = runningApp();
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
  context.rpc = async (method) => method.endsWith("begin") ? { id: "file" } : method.endsWith("chunk") ? { offset: 3 } : readyAttachment();
  const event = {
    target: document.body, clipboardData: { files: [new File(["abc"], "file.txt")] },
    preventDefault() { this.defaultPrevented = true; },
  };
  await documentListeners.get("paste")(event);
  assert.equal(event.defaultPrevented, true);
  assert.equal(app.currentAttachments().length, 1);
  event.defaultPrevented = false;
  event.clipboardData = { items: [{ kind: "string" }], files: [] };
  await documentListeners.get("paste")(event);
  assert.equal(event.defaultPrevented, false);
  assert.equal(app.currentAttachments().length, 1);
});

test("paste respects other editors, shadow editors, modal dialogs and blocked composer states", async () => {
  for (const blocked of ["offline", "unloaded", "loading", "readOnly", "sending", "unsupported", "editor", "shadow", "dialog", "hidden", "menu"]) {
    const { app, context, elements, document, documentListeners } = runningApp();
    app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
    if (blocked === "offline") app.state.online = false;
    else if (blocked === "unloaded") app.state.loaded = false;
    else if (["loading", "readOnly", "sending"].includes(blocked)) app.state[blocked] = true;
    else if (blocked === "unsupported") delete app.state.info.attachments;
    else if (blocked === "dialog") document.querySelector = () => ({ contains: () => false });
    else if (blocked === "hidden") elements.get("composer").closest = () => ({});
    else if (blocked === "menu") elements.get("model-menu").hidden = false;
    context.rpc = async () => { assert.fail(`paste uploaded while blocked: ${blocked}`); };
    const editor = { closest: () => editor };
    const event = {
      target: blocked === "editor" ? editor : document.body,
      composedPath: () => blocked === "shadow" ? [editor, document.body] : [],
      clipboardData: { items: [{ kind: "file", getAsFile: () => new File(["abc"], "file.txt") }] },
      preventDefault() { assert.fail(`paste was consumed while blocked: ${blocked}`); },
    };
    await documentListeners.get("paste")(event);
    assert.equal(app.currentAttachments().length, 0, blocked);
  }
});

test("attachments upload in bounded chunks and persist only finalized metadata", async () => {
  const { app, context, elements } = runningApp();
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
  const data = new Uint8Array(262144 + 17).fill(65);
  const received = [];
  const file = readyAttachment({ name: "report.txt", size: data.length });
  context.rpc = async (method, params) => {
    if (method.endsWith("begin")) return { id: file.id, chunkSize: 262144 };
    if (method.endsWith("chunk")) {
      const bytes = Buffer.from(params.data, "base64");
      assert.ok(bytes.length <= 262144);
      assert.equal(params.offset, received.reduce((sum, b) => sum + b.length, 0));
      received.push(bytes);
      return { offset: params.offset + bytes.length };
    }
    assert.equal(method, "codexrm/attachment/finish");
    return file;
  };
  await app.addAttachments([new File([data], "report.txt", { type: "text/plain" })]);
  assert.deepEqual(Buffer.concat(received), Buffer.from(data));
  assert.equal(app.currentAttachments()[0].path, file.path);
  assert.equal(app.currentAttachments()[0].file, undefined);
  assert.equal(elements.get("queue-send").disabled, false);
  const storage = JSON.parse(vm.runInContext('localStorage.getItem("codexrm.attachments.v1")', context));
  assert.deepEqual(storage["device:thread:thread"], [file]);
  app.state.current = { id: "other" };
  app.restoreDraft();
  assert.equal(app.currentAttachments().length, 0);
  assert.equal(elements.get("attachments").hidden, true);
});

test("attachment-only messages work for new turns, queues and steering", async () => {
  for (const mode of ["auto", "queue", "steer"]) {
    const { app, context, elements } = runningApp();
    app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
    const file = readyAttachment({ name: "图片.png", mimeType: "image/png" });
    app.state.attachmentDrafts["device:thread:thread"] = [file];
    if (mode === "auto") app.state.turns = [];
    const calls = [];
    context.rpc = async (method, params) => {
      calls.push({ method, params });
      return method === "turn/start" ? { turn: { id: "new", status: "inProgress", items: [] } }
        : { threadId: "thread", entries: [{ id: "q", input: params.input }] };
    };
    app.resizePrompt();
    assert.equal(elements.get(mode === "auto" ? "send" : "queue-send").disabled, false);
    await app.submit(null, mode);
    assert.equal(calls[0].method, { auto: "turn/start", queue: "codexrm/queue/add", steer: "turn/steer" }[mode]);
    assert.equal(calls[0].params.input[1].type, "localImage");
    assert.equal(calls[0].params.input[1].path, file.path);
    assert.equal(app.currentAttachments().length, 0);
  }
});

test("a direct-only relay allows chat image upload and preview without enabling other files", async () => {
  const { app, context, elements } = runningApp();
  app.state.fileTransferDirectOnly = true;
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024, relayImages: true };
  const file = readyAttachment({ name: "image.PNG", mimeType: "image/png", path: "/agent/photo.png" });
  const methods = [];
  context.rpc = async (method, params) => {
    methods.push(method);
    switch (method) {
      case "codexrm/chatImage/begin": return { id: file.id, chunkSize: 2 };
      case "codexrm/chatImage/chunk": return { offset: params.offset + Buffer.from(params.data, "base64").length };
      case "codexrm/chatImage/finish": return file;
      default: assert.fail(`Unexpected upload method: ${method}`);
    }
  };
  await app.addAttachments([new File(["abc"], "notes.txt", { type: "text/plain" }), new File(["abc"], file.name)]);
  assert.deepEqual(methods, ["codexrm/chatImage/begin", "codexrm/chatImage/chunk", "codexrm/chatImage/chunk", "codexrm/chatImage/finish"]);
  assert.equal(elements.get("attach-files").disabled, false);
  assert.match(elements.get("file-input").accept, /\.png/);
  assert.equal(app.currentAttachments().length, 1);
  const input = app.messageInput("look", app.currentAttachments());
  assert.equal(input[2].type, "localImage");
  assert.equal(input[2].path, file.path);

  app.state.attachmentCache.clear();
  context.rpc = async (method, params) => {
    assert.equal(method, "codexrm/chatImage/read");
    assert.equal(params.id, file.id);
    return { file, offset: 3, data: "YWJj" };
  };
  assert.equal(await (await app.readAttachment(file.id, true)).blob.text(), "abc");
  await assert.rejects(app.readAttachment("ordinary-file"), /P2P/);
  const readHistoryImage = vm.runInContext("readHistoryImage", context);
  await assert.rejects(readHistoryImage("/workspace/output.png", "item", "/workspace"), /P2P/);
});

test("failed attachment sends retain files alongside text typed during the request", async () => {
  for (const mode of ["auto", "queue", "steer"]) {
    const { app, context, elements } = runningApp();
    app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
    if (mode === "auto") app.state.turns = [];
    const file = readyAttachment();
    app.state.attachmentDrafts["device:thread:thread"] = [file];
    elements.get("prompt").value = "original";
    let reject;
    context.rpc = () => new Promise((_, fail) => { reject = fail; });
    const sending = app.submit(null, mode);
    assert.equal(app.currentAttachments().length, 0);
    elements.get("prompt").value = "new draft";
    reject(new Error("connection lost"));
    await sending;
    assert.equal(app.currentAttachments()[0].id, file.id);
    assert.equal(elements.get("prompt").value, "original\n\nnew draft");
  }
});

test("uploads in progress or in error block sending, and a removed upload aborts", async () => {
  const { app, context, elements } = runningApp();
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
  let finishChunk;
  const methods = [];
  context.rpc = async (method, params) => {
    methods.push(method);
    if (method.endsWith("begin")) return { id: "upload", chunkSize: 262144 };
    if (method.endsWith("chunk")) return new Promise((resolve) => { finishChunk = () => resolve({ offset: 3 }); });
    return {};
  };
  const uploading = app.addAttachments([new File(["abc"], "report.txt")]);
  await new Promise(setImmediate);
  assert.equal(elements.get("queue-send").disabled, true);
  await app.submit();
  assert.equal(methods.includes("codexrm/queue/add"), false);
  elements.get("attachments").children[0].children.at(-1).onclick();
  finishChunk();
  await uploading;
  assert.equal(app.currentAttachments().length, 0);
  assert.equal(methods.includes("codexrm/attachment/abort"), true);
  assert.equal(methods.includes("codexrm/attachment/finish"), false);
});

test("attachment rendering restores merged history and escapes filenames", () => {
  const { app } = runningApp();
  const file = readyAttachment({ name: '<img src=x onerror="alert(1)">.txt' });
  const content = app.messageInput("look", [file]);
  const merged = [{ type: "text", text: content.map((c) => c.text).join("\n") }];
  const view = app.userContent(merged);
  assert.equal(view.text, "look");
  assert.equal(view.files[0].name, file.name);
  const html = app.itemHTML({ type: "userMessage", content: merged });
  assert.ok(html.includes("&lt;img"));
  assert.equal(html.includes('<img src=x onerror="alert(1)">'), false);
  assert.equal(html.includes("/agent/attachments/file-report.txt"), false);
});

test("history images reuse disk bytes and stay scoped to the pairing and message", async () => {
  const stored = new Map();
  const creates = () => {
    const harness = newThreadApp();
    const { app, context } = harness;
    app.state.devices[0].clientId = "paired-client";
    const disk = vm.runInContext("historyImageDisk", context);
    disk.get = async (key) => stored.get(key);
    disk.put = async (key, _scope, blob, file) => { stored.set(key, { blob, file }); };
    return harness;
  };
  const before = creates();
  let calls = 0;
  before.context.rpc = async (method, params) => {
    assert.equal(method, "codexrm/attachment/read");
    calls++;
    return { file: { id: params.id, mimeType: "image/png", size: 1 }, offset: 1, data: "YQ==" };
  };
  const results = await Promise.all([before.app.readAttachment("image"), before.app.readAttachment("image")]);
  assert.equal(calls, 1);
  assert.equal(results[0].url, results[1].url);
  const after = creates();
  after.context.rpc = async () => { throw new Error("cache miss"); };
  assert.equal(await (await after.app.readAttachment("image")).blob.text(), "a");
  after.app.state.devices[0].clientId = "different-pairing";
  await assert.rejects(after.app.readAttachment("image"), /cache miss/);
});

test("a restricted computer accepts a known workspace path without browsing its directory", async () => {
  const { app, context, elements } = newThreadApp();
  app.state.info = { workspaces: true, directFiles: false, cwd: "/root" };
  app.state.online = true;
  const calls = [];
  context.rpc = async (method, params) => {
    calls.push([method, params?.path]);
    if (method === "codexrm/workspace/add") throw new Error("fixture stopped after verifying add");
    throw new Error(`unexpected ${method}`);
  };
  elements.get("add-workspace").onclick();
  assert.equal(elements.get("workspace-dialog").open, true);
  assert.equal(elements.get("browse-directory").hidden, true);
  assert.deepEqual(calls, []);
  const path = elements.get("directory-path");
  path.value = "/project";
  path.oninput();
  assert.equal(elements.get("select-directory").disabled, false);
  await elements.get("select-directory").onclick();
  assert.deepEqual(calls, [["codexrm/workspace/add", "/project"]]);
});

test("local history previews resolve the path and keep separate message cache identities", async () => {
  const { app, context } = newThreadApp();
  const html = app.itemHTML({ id: "old", type: "agentMessage", text: "![图](file:///E:/a%20b.png)" });
  assert.match(html, /data-history-image="E:\/a b\.png" data-image-item="old"/);
  assert.doesNotMatch(app.itemHTML({ id: "old", type: "agentMessage", text: "![图](javascript:alert(1))" }), /<img/);
  const readHistoryImage = vm.runInContext("readHistoryImage", context);
  let calls = 0;
  context.rpc = async (method, params) => {
    assert.equal(method, "codexrm/image/read");
    assert.equal(params.path, "E:/a b.png");
    assert.equal(params.threadId, "thread-a");
    calls++;
    return { file: { mimeType: "image/png", size: 1, version: "v1" }, offset: 1, data: "YQ==" };
  };
  await readHistoryImage("E:/a b.png", "old", "/project", "thread-a");
  await readHistoryImage("E:/a b.png", "old", "/project", "thread-a");
  assert.equal(calls, 1);
  await readHistoryImage("E:/a b.png", "new", "/project", "thread-a");
  assert.equal(calls, 2);
});

test("attachment drafts survive reload without storing file bytes", async () => {
  const storage = new Map();
  const loaded = loadApp(storage);
  const { app, context } = loaded;
  Object.assign(app.state, {
    selected: "device", online: true, loaded: true, current: { id: "thread" },
    info: { attachments: { maxSize: 20 * 1024 * 1024 } },
  });
  context.rpc = async (method) => method.endsWith("begin") ? { id: "ready" } : method.endsWith("chunk") ? { offset: 3 } : readyAttachment();
  await app.addAttachments([new File(["abc"], "report.txt")]);
  const reloaded = loadApp(storage);
  reloaded.app.state.selected = "device";
  reloaded.app.state.current = { id: "thread" };
  reloaded.app.restoreDraft();
  assert.equal(reloaded.app.currentAttachments()[0].name, "报告.txt");
  assert.equal(reloaded.app.currentAttachments()[0].file, undefined);
  assert.equal(reloaded.elements.get("attachments").hidden, false);
});

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

test("thread/start success preserves text typed while waiting and scopes it to the new thread", async () => {
  const storage = new Map();
  const { app, context, elements } = newThreadApp(storage);
  const started = deferred();
  const turn = deferred();
  context.rpc = (method) => method === "thread/start" ? started.promise : turn.promise;
  elements.get("prompt").value = "original message";
  app.resizePrompt();
  const sending = app.submit();
  assert.equal(elements.get("prompt").value, "");
  elements.get("prompt").value = "new draft";
  app.resizePrompt();
  started.resolve({ thread: { id: "created", cwd: "/project", turns: [] } });
  await flush();
  assert.equal(app.state.current.id, "created");
  assert.equal(elements.get("prompt").value, "new draft");
  const drafts = JSON.parse(storage.get("codexrm.drafts.v1"));
  assert.equal(drafts["device:workspace:/project"], undefined);
  assert.equal(drafts["device:thread:created"], "new draft");
  turn.resolve({ turn: { id: "turn", status: "inProgress", items: [] } });
  await sending;
  assert.equal(elements.get("prompt").value, "new draft");
  assert.equal(app.state.sending, false);
});

test("thread/start and first-turn failures restore both the original and newer draft", async () => {
  for (const failure of ["thread/start", "turn/start"]) {
    const storage = new Map();
    const { app, context, elements } = newThreadApp(storage);
    context.rpc = async (method) => {
      if (method === failure) throw new Error("request failed");
      if (method === "thread/start") return { thread: { id: "created", cwd: "/project", turns: [] } };
      return {};
    };
    elements.get("prompt").value = "original message";
    app.resizePrompt();
    const sending = app.submit();
    elements.get("prompt").value = "new draft";
    app.resizePrompt();
    await sending;
    const key = failure === "thread/start" ? "device:workspace:/project" : "device:thread:created";
    assert.equal(elements.get("prompt").value, "original message\n\nnew draft", failure);
    assert.equal(JSON.parse(storage.get("codexrm.drafts.v1"))[key], "original message\n\nnew draft", failure);
    assert.equal(app.state.sending, false, failure);
  }
});

test("opening another thread cancels only the pending creation and ignores its late result", async () => {
  const { app, context, elements } = newThreadApp();
  const started = deferred();
  const calls = [];
  context.rpc = async (method) => {
    calls.push(method);
    if (method === "thread/start") return started.promise;
    if (method === "thread/resume") return { thread: { id: "other", cwd: "/project", turns: [] } };
    throw new Error(`unexpected ${method}`);
  };
  elements.get("prompt").value = "original message";
  app.resizePrompt();
  const sending = app.submit();
  elements.get("prompt").value = "new draft";
  app.resizePrompt();
  await app.openThread("other");
  assert.equal(app.state.current.id, "other");
  assert.equal(app.state.sending, false);
  started.resolve({ thread: { id: "created", cwd: "/project", turns: [] } });
  await sending;
  assert.equal(app.state.current.id, "other");
  assert.equal(calls.includes("turn/start"), false);
  assert.equal(app.state.drafts["device:workspace:/project"], "original message\n\nnew draft");
  assert.equal(app.state.drafts["device:thread:other"], undefined);
});

test("a paginated resume cannot restore a thread archived while its turn page was in flight", async () => {
  const { app, context } = deletionApp();
  const page = deferred();
  context.rpc = async (method) => {
    if (method === "thread/resume") return { thread: { id: "other", historyMode: "paginated", cwd: "/project" } };
    if (method === "thread/turns/list") return page.promise;
    throw new Error(`unexpected ${method}`);
  };
  const opening = app.openThread("other");
  await flush();
  app.onEvent({ method: "thread/archived", params: { threadId: "other" } });
  page.resolve({ data: [{ id: "turn", items: [] }], nextCursor: null });
  await opening;
  assert.equal(app.state.current.id, "current");
  assert.equal(app.state.threads.some((thread) => thread.id === "other"), false);
  assert.equal(app.state.archivedThreads.has("other"), true);
});

test("refresh after an early bootstrap failure retries full initialization and restores loaded", async () => {
  const { app, context, elements } = loadApp();
  app.state.online = true;
  let infos = 0;
  context.rpc = async () => { infos++; throw new Error("info unavailable"); };
  await app.bootstrap(app.state.generation);
  assert.equal(app.state.loaded, false);
  assert.equal(app.state.bootstrapPromise, null);
  context.rpc = async (method) => {
    if (method === "codexrm/info") { infos++; return { name: "Computer", cwd: "/project", workspaces: false }; }
    if (method === "thread/list") return { data: [], nextCursor: null };
    if (method === "model/list") return { data: [] };
    throw new Error(`unexpected ${method}`);
  };
  await elements.get("refresh").onclick();
  assert.equal(infos, 2);
  assert.equal(app.state.loaded, true);
  assert.equal(app.state.info.name, "Computer");
  assert.equal(app.state.bootstrapPromise, null);
});

test("older turns have one in-flight request, deduplicate overlap and ignore stale responses", async () => {
  const { app, context } = loadApp();
  app.state.current = { id: "thread" };
  app.state.turns = [{ id: "current", items: [{ id: "newest" }] }];
  app.state.historyCursor = "page-2";
  const firstPage = deferred();
  const stalePage = deferred();
  let calls = 0;
  context.rpc = () => ++calls === 1 ? firstPage.promise : stalePage.promise;
  const first = app.olderTurns();
  await app.olderTurns();
  assert.equal(calls, 1);
  firstPage.resolve({ data: [{ id: "older", items: [] }, { id: "current", items: [] }], nextCursor: "page-3" });
  await first;
  assert.deepEqual(Array.from(app.state.turns, (turn) => turn.id), ["older", "current"]);
  assert.equal(app.state.turns[1].items[0].id, "newest");
  const stale = app.olderTurns();
  app.newChat();
  stalePage.resolve({ data: [{ id: "stale", items: [] }], nextCursor: null });
  await stale;
  assert.equal(app.state.current, null);
  assert.equal(app.state.turns.length, 0);
  assert.equal(app.state.historyCursor, null);
});

test("detached snapshots enter read-only on active and loaded restores normal cleanup", async () => {
  const { app, context, advance } = loadApp();
  Object.assign(app.state, {
    online: true, loaded: true, info: { threadSnapshots: true },
    current: { id: "thread", turns: [] }, turns: [],
  });
  let snapshots = 0;
  context.rpc = async (method) => {
    assert.equal(method, "codexrm/thread/snapshot");
    snapshots++;
    return { active: snapshots >= 2, thread: { id: "thread", turns: [] }, nextCursor: null };
  };
  app.onEvent({ method: "codexrm/thread/detached", params: { threadId: "thread" } });
  assert.equal(app.state.detached, true);
  advance(2999);
  assert.equal(snapshots, 0);
  advance(1);
  await flush();
  assert.equal(snapshots, 1);
  assert.equal(app.state.detached, true, "inactive snapshot keeps detached polling");
  assert.equal(app.state.readOnly, false);
  advance(3000);
  await flush();
  assert.equal(snapshots, 2);
  assert.equal(app.state.detached, false, "active snapshot enters read-only");
  assert.equal(app.state.readOnly, true);
  advance(3000);
  await flush();
  assert.equal(snapshots, 3);
  app.onEvent({ method: "codexrm/thread/loaded", params: { threadId: "thread" } });
  assert.equal(app.state.detached, false);
  assert.equal(app.state.readOnly, false);
  advance(6000);
  await flush();
  assert.equal(snapshots, 3);
});

test("shrinking snapshots retain previous pages and disjoint snapshots reset the cursor", async () => {
  const { app, context, advance } = loadApp();
  const turn = (id) => ({ id, items: [] });
  Object.assign(app.state, {
    online: true, loaded: true, info: { threadSnapshots: true },
    current: { id: "thread" }, turns: [turn("one"), turn("two"), turn("three")],
    historyCursor: "before-one",
  });
  const replies = [
    { thread: { id: "thread", turns: [turn("three"), turn("four")] }, nextCursor: "before-three" },
    { thread: { id: "thread", turns: [turn("eight")] }, nextCursor: "before-eight" },
  ];
  context.rpc = async (method) => {
    assert.equal(method, "codexrm/thread/snapshot");
    return replies.shift();
  };
  app.onEvent({ method: "codexrm/thread/detached", params: { threadId: "thread" } });
  advance(3000);
  await flush();
  assert.deepEqual(Array.from(app.state.turns, (item) => item.id), ["one", "two", "three", "four"]);
  assert.equal(app.state.historyCursor, "before-one");
  advance(3000);
  await flush();
  assert.deepEqual(Array.from(app.state.turns, (item) => item.id), ["eight"]);
  assert.equal(app.state.historyCursor, "before-eight");
});

test("open, new chat, device switch and reset clear detached polling", async () => {
  for (const action of ["open", "new", "device", "reset"]) {
    const { app, context, advance } = loadApp();
    Object.assign(app.state, {
      online: true, loaded: true, info: { threadSnapshots: true },
      current: { id: "thread", turns: [] }, turns: [],
      devices: [{ token: "one", agent: { agentId: "device" } }, { token: "two", agent: { agentId: "other-device" } }],
      selected: "device",
    });
    let snapshots = 0;
    context.rpc = async (method) => {
      if (method === "codexrm/thread/snapshot") { snapshots++; return { active: false, thread: { id: "thread", turns: [] } }; }
      if (method === "thread/resume") return { thread: { id: "other", turns: [] } };
      throw new Error(`unexpected ${method}`);
    };
    app.onEvent({ method: "codexrm/thread/detached", params: { threadId: "thread" } });
    if (action === "open") await app.openThread("other");
    else if (action === "new") app.newChat();
    else if (action === "device") app.selectDevice(app.state.devices[1]);
    else app.resetView();
    assert.equal(app.state.detached, false, action);
    assert.equal(app.state.readOnly, false, action);
    advance(6000);
    await flush();
    assert.equal(snapshots, 0, action);
  }
});

test("loading dragover and drop both reject files with a visible reason", async () => {
  const { app, context, elements } = runningApp();
  app.state.info.attachments = { maxSize: 20 * 1024 * 1024 };
  app.state.loading = true;
  context.rpc = async () => { assert.fail("loading drop must not upload"); };
  const dataTransfer = { types: ["Files"], files: [new File(["abc"], "file.txt")], dropEffect: "copy" };
  const event = { dataTransfer, preventDefault() { this.defaultPrevented = true; } };
  elements.get("composer").listeners.get("dragover")(event);
  assert.equal(dataTransfer.dropEffect, "none");
  elements.get("composer").listeners.get("drop")(event);
  assert.equal(event.defaultPrevented, true);
  assert.equal(elements.get("toast").hidden, false);
  assert.match(elements.get("toast").textContent, /正在同步工作区/);
  app.state.loading = false;
  elements.get("composer").listeners.get("dragover")(event);
  assert.equal(dataTransfer.dropEffect, "copy");
});

function loadAgenthost() {
  class Element {
    constructor() {
      this.children = []; this.dataset = {}; this.value = ""; this.hidden = false;
      this.classList = { toggle() {} };
    }
    get childElementCount() { return this.children.length; }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    addEventListener(type, listener) { (this.listeners ||= new Map()).set(type, listener); }
    setAttribute(name, value) { this[name] = value; }
  }
  const elements = new Map();
  const document = {
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
    createElement() { return new Element(); },
  };
  let interval;
  let settingsCalls = 0;
  let deviceCalls = 0;
  const settings = { server: "https://server.example", status: "已连接", version: "1.2.3", packaged: false };
  const context = vm.createContext({
    document, location: { hash: "#token" }, console, URL, Date, JSON, Error, Promise, Headers, FormData,
    window: { settingsNavigation: { init() {}, lock() {} } },
    Option: class Option { constructor(text, value) { this.text = text; this.value = value; } },
    navigator: { clipboard: { writeText: async () => {} } }, confirm: () => true,
    setInterval(callback) { interval = callback; },
    setTimeout() {}, clearTimeout() {},
    fetch: async (path) => {
      if (path === "/api/settings") {
        settingsCalls++;
        if (settingsCalls === 1) throw new Error("temporarily unavailable");
        return { ok: true, text: async () => JSON.stringify(settings) };
      }
      if (path === "/api/wx/accounts") return { ok: true, text: async () => "[]" };
      if (path === "/api/devices") {
        deviceCalls++;
        return { ok: true, text: async () => JSON.stringify([{ clientId: "browser", clientName: "Browser", deviceType: "desktop" }]) };
      }
      throw new Error(`unexpected ${path}`);
    },
  });
  document.getElementById("login-form").hidden = true;
  for (const id of ["server", "version", "status", "devices"]) document.getElementById(id);
  vm.runInContext(readFileSync(new URL("../internal/agenthost/web/app.js", import.meta.url), "utf8"), context);
  return { context, elements, settings, poll: () => interval(), counts: () => ({ settingsCalls, deviceCalls }) };
}

test("agenthost polling after the first settings failure applies settings and restores devices", async () => {
  const harness = loadAgenthost();
  await flush();
  assert.equal(harness.elements.get("notice").hidden, false);
  assert.equal(harness.elements.get("server").value, "");
  await harness.poll();
  assert.equal(harness.elements.get("server").value, harness.settings.server);
  assert.equal(harness.elements.get("allow-file-management").checked, false);
  assert.equal(harness.elements.get("version").textContent, "1.2.3");
  assert.equal(harness.elements.get("status").textContent, "已连接");
  assert.equal(harness.elements.get("devices").children.length, 1);
  assert.equal(harness.elements.get("devices").children[0].children[1].children[0].textContent, "Browser");
  assert.deepEqual(harness.counts(), { settingsCalls: 2, deviceCalls: 1 });
  assert.equal(vm.runInContext("saved.server", harness.context), harness.settings.server);
});
