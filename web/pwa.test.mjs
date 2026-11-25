import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

class FakeEvent {
  constructor(type, { cancelable = false } = {}) {
    this.type = type;
    this.cancelable = cancelable;
    this.defaultPrevented = false;
  }
  preventDefault() {
    if (this.cancelable) this.defaultPrevented = true;
  }
}

class FakeCustomEvent extends FakeEvent {
  constructor(type, options = {}) {
    super(type, options);
    this.detail = options.detail;
  }
}

class FakeEventTarget {
  constructor() {
    this.listeners = new Map();
  }
  addEventListener(type, listener, options = {}) {
    const handlers = this.listeners.get(type) || [];
    handlers.push({ listener, once: options === true || options?.once === true });
    this.listeners.set(type, handlers);
  }
  dispatchEvent(event) {
    event.target = this;
    for (const entry of [...(this.listeners.get(event.type) || [])]) {
      if (entry.once) {
        const handlers = this.listeners.get(event.type);
        handlers.splice(handlers.indexOf(entry), 1);
      }
      entry.listener.call(this, event);
    }
    return !event.defaultPrevented;
  }
}

class FakeElement extends FakeEventTarget {
  constructor(tagName) {
    super();
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.attributes = new Map();
    this.style = {};
    this.hidden = false;
    this.disabled = false;
    this._textContent = "";
  }
  set textContent(value) {
    this._textContent = String(value);
    this.children = [];
  }
  get textContent() {
    return this._textContent + this.children.map((child) => child.textContent).join("");
  }
  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }
  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }
  append(...children) {
    for (const child of children) this.appendChild(child);
  }
  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
}

function createWorker(state = "waiting") {
  const worker = new FakeEventTarget();
  worker.state = state;
  worker.messages = [];
  worker.postMessage = (message) => worker.messages.push(message);
  return worker;
}

function settle() {
  return new Promise((resolve) => setImmediate(resolve));
}

function createHarness({
  serviceWorker = false,
  controller = null,
  waiting = null,
  registerError = null,
} = {}) {
  const body = new FakeElement("body");
  const document = {
    body,
    documentElement: body,
    createElement: (tagName) => new FakeElement(tagName),
  };
  const window = new FakeEventTarget();
  window.location = {
    reloads: 0,
    reload() { this.reloads += 1; },
  };

  const navigator = {};
  let container = null;
  let registration = null;
  const registrations = [];
  if (serviceWorker) {
    registration = new FakeEventTarget();
    registration.waiting = waiting;
    registration.installing = null;
    container = new FakeEventTarget();
    container.controller = controller;
    container.register = async (path, options) => {
      registrations.push({ path, options });
      if (registerError) throw registerError;
      return registration;
    };
    navigator.serviceWorker = container;
  }

  const warnings = [];
  const context = vm.createContext({
    window,
    document,
    navigator,
    CustomEvent: FakeCustomEvent,
    console: { warn: (...args) => warnings.push(args) },
  });
  vm.runInContext(readFileSync(new URL("pwa.js", import.meta.url), "utf8"), context);

  return {
    window,
    document,
    navigator,
    registration,
    container,
    registrations,
    warnings,
    async load() {
      window.dispatchEvent(new FakeEvent("load"));
      await settle();
    },
    async click(element) {
      element.dispatchEvent(new FakeEvent("click"));
      await settle();
    },
    statusRegions() {
      const regions = [];
      const visit = (node) => {
        if (node.getAttribute?.("role") === "status") regions.push(node);
        for (const child of node.children || []) visit(child);
      };
      visit(body);
      return regions;
    },
    visibleText() {
      const visit = (node) => {
        if (node.hidden) return "";
        return node._textContent + (node.children || []).map(visit).join("");
      };
      return visit(body);
    },
    updateButton() {
      let result = null;
      const visit = (node) => {
        if (node.tagName === "BUTTON" && node.textContent === "立即更新") result = node;
        for (const child of node.children || []) visit(child);
      };
      visit(body);
      return result;
    },
  };
}

test("service-worker registration failure produces non-blocking visible feedback", async () => {
  const harness = createHarness({ serviceWorker: true, registerError: new Error("registration denied") });
  await harness.load();
  assert.equal(harness.registrations.length, 1);
  assert.match(harness.visibleText(), /离线功能暂不可用.*不影响当前使用/);
  assert.equal(harness.warnings.length, 1);
});

test("waiting update honors cancellation, activates only when allowed and reloads once", async () => {
  const worker = createWorker();
  const harness = createHarness({ serviceWorker: true, controller: {}, waiting: worker });
  await harness.load();
  assert.match(harness.visibleText(), /新版本就绪/);
  const updateButton = harness.updateButton();
  assert.ok(updateButton);

  let reloadEvent;
  harness.window.addEventListener("codexrm:pwa-reload", (event) => {
    reloadEvent = event;
    event.preventDefault();
  }, { once: true });
  await harness.click(updateButton);
  assert.equal(reloadEvent.cancelable, true);
  assert.equal(worker.messages.length, 0);
  assert.equal(harness.window.location.reloads, 0);
  assert.equal(updateButton.disabled, false);
  assert.match(harness.visibleText(), /任务结束后可更新/);

  await harness.click(updateButton);
  assert.equal(worker.messages.length, 1);
  assert.equal(worker.messages[0].type, "SKIP_WAITING");
  assert.equal(updateButton.disabled, true);
  harness.container.dispatchEvent(new FakeEvent("controllerchange"));
  harness.container.dispatchEvent(new FakeEvent("controllerchange"));
  assert.equal(harness.window.location.reloads, 1);
});

test("updatefound shows ready UI only after an updated worker is installed", async () => {
  const harness = createHarness({ serviceWorker: true, controller: {} });
  await harness.load();
  assert.equal(harness.statusRegions().length, 0);

  const worker = createWorker("installing");
  harness.registration.installing = worker;
  harness.registration.dispatchEvent(new FakeEvent("updatefound"));
  assert.equal(harness.statusRegions().length, 0);
  worker.state = "installed";
  harness.registration.waiting = worker;
  worker.dispatchEvent(new FakeEvent("statechange"));
  assert.match(harness.visibleText(), /新版本就绪/);
  assert.ok(harness.updateButton());

  const firstInstall = createHarness({ serviceWorker: true });
  await firstInstall.load();
  const installing = createWorker("installing");
  firstInstall.registration.installing = installing;
  firstInstall.registration.dispatchEvent(new FakeEvent("updatefound"));
  installing.state = "installed";
  installing.dispatchEvent(new FakeEvent("statechange"));
  assert.equal(firstInstall.statusRegions().length, 0);
});
