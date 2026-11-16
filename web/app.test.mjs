import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

// Run the shipped script with a small DOM double; browser QA covers layout.
function loadApp(storage = new Map()) {
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
    close() {}
    showModal() {}
    contains(node) { return this === node || this.children.some((n) => n.contains?.(node)); }
    querySelectorAll(selector) {
      return this.children.flatMap((n) => [
        ...(selector.split(",").map((s) => s.trim().toUpperCase()).includes(n.tagName) ? [n] : []),
        ...(n.querySelectorAll?.(selector) || []),
      ]);
    }
    get options() { return this.children; }
  }
  const elements = new Map();
  const document = {
    getElementById: (id) => {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    createElement: (tag) => new Element(tag),
    querySelectorAll: () => [], querySelector: () => null,
    addEventListener() {}, body: new Element(), documentElement: new Element(),
    activeElement: new Element("body"),
  };
  const context = vm.createContext({
    document, localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    window: { addEventListener() {} }, location: { origin: "http://localhost" },
    Option: class extends Element { constructor(label, value) { super("option"); this.textContent = label; this.value = value; } },
    setTimeout: () => 1, clearTimeout() {}, setInterval() {}, clearInterval() {},
    requestAnimationFrame() {}, WebSocket: { OPEN: 1 }, URL, console,
  });
  vm.runInContext(readFileSync(new URL("app.js", import.meta.url), "utf8"), context);
  return { context, elements, app: vm.runInContext("({state, workspaceGroups, displayedTurnStatus, loadThreads, onEvent, renderApprovals, sandboxPolicy, answerRequest, newChat, selectWorkspace, restoreDraft, resizePrompt, submit})", context) };
}

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
