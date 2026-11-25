(() => {
  "use strict";

  let statusUI = null;

  function ensureStatusUI() {
    if (statusUI) return statusUI;

    const element = document.createElement("div");
    element.setAttribute("role", "status");
    element.setAttribute("aria-live", "polite");
    Object.assign(element.style, {
      position: "fixed",
      right: "16px",
      bottom: "16px",
      zIndex: "10000",
      maxWidth: "min(420px, calc(100vw - 32px))",
      padding: "12px",
      border: "1px solid #52525b",
      borderRadius: "10px",
      background: "#202024",
      color: "#f4f4f5",
      boxShadow: "0 8px 30px rgb(0 0 0 / 35%)",
      font: "14px/1.5 system-ui, sans-serif",
    });
    element.hidden = true;

    const feedback = document.createElement("p");
    feedback.style.margin = "0";
    feedback.hidden = true;

    const updateSection = document.createElement("div");
    updateSection.style.marginTop = "8px";
    updateSection.hidden = true;
    const updateText = document.createElement("span");
    const updateButton = document.createElement("button");
    updateButton.type = "button";
    updateButton.textContent = "立即更新";
    updateButton.style.marginLeft = "12px";
    updateSection.append(updateText, updateButton);

    const dismiss = document.createElement("button");
    dismiss.type = "button";
    dismiss.textContent = "关闭提示";
    dismiss.style.marginTop = "8px";
    dismiss.hidden = true;

    element.append(feedback, updateSection, dismiss);
    (document.body || document.documentElement).appendChild(element);

    statusUI = { element, feedback, updateSection, updateText, updateButton, dismiss, updateAction: null };

    const syncVisibility = () => {
      statusUI.dismiss.hidden = statusUI.feedback.hidden;
      statusUI.element.hidden = statusUI.feedback.hidden && statusUI.updateSection.hidden;
    };
    dismiss.addEventListener("click", () => {
      statusUI.feedback.hidden = true;
      statusUI.feedback.textContent = "";
      syncVisibility();
    });
    updateButton.addEventListener("click", () => statusUI.updateAction?.());
    statusUI.syncVisibility = syncVisibility;
    return statusUI;
  }

  function showFeedback(message) {
    const ui = ensureStatusUI();
    ui.feedback.textContent = message;
    ui.feedback.hidden = false;
    ui.syncVisibility();
  }

  function showUpdate(action) {
    const ui = ensureStatusUI();
    ui.updateAction = action;
    ui.updateText.textContent = "新版本就绪";
    ui.updateButton.disabled = false;
    ui.updateSection.hidden = false;
    ui.syncVisibility();
  }

  function setUpdateMessage(message, busy = false) {
    const ui = ensureStatusUI();
    ui.updateText.textContent = message;
    ui.updateButton.disabled = busy;
    ui.updateSection.hidden = false;
    ui.syncVisibility();
  }

  if (!("serviceWorker" in navigator)) return;

  let registration = null;
  let waitingWorker = null;
  let updateRequested = false;
  let reloading = false;
  const watchedWorkers = new WeakSet();

  function showReadyUpdate(worker) {
    waitingWorker = worker;
    showUpdate(requestUpdate);
  }

  function requestUpdate() {
    const worker = waitingWorker || registration?.waiting;
    if (!worker || updateRequested || reloading) return;

    let allowed;
    try {
      allowed = window.dispatchEvent(new CustomEvent("codexrm:pwa-reload", { cancelable: true }));
    } catch (error) {
      console.warn("CodexRM update preparation failed", error);
      setUpdateMessage("新版本就绪 · 无法确认任务状态，请稍后重试");
      return;
    }
    if (!allowed) {
      setUpdateMessage("新版本就绪 · 当前任务结束后可更新");
      return;
    }

    updateRequested = true;
    setUpdateMessage("正在更新…", true);
    try {
      worker.postMessage({ type: "SKIP_WAITING" });
    } catch (error) {
      updateRequested = false;
      setUpdateMessage("新版本就绪 · 更新未能启动，请重试");
      console.warn("CodexRM update activation failed", error);
      showFeedback("无法启动更新，当前页面保持不变。");
    }
  }

  function watchInstalling(worker) {
    if (!worker || watchedWorkers.has(worker)) return;
    watchedWorkers.add(worker);
    const checkState = () => {
      if (worker.state === "installed" && navigator.serviceWorker.controller) {
        showReadyUpdate(registration?.waiting || worker);
      }
    };
    worker.addEventListener("statechange", checkState);
    checkState();
  }

  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!updateRequested || reloading) return;
    reloading = true;
    window.location.reload();
  });

  window.addEventListener(
    "load",
    async () => {
      try {
        registration = await navigator.serviceWorker.register("/sw.js", { scope: "/" });
        if (registration.waiting) showReadyUpdate(registration.waiting);
        watchInstalling(registration.installing);
        registration.addEventListener("updatefound", () => watchInstalling(registration.installing));
      } catch (error) {
        console.warn("CodexRM offline cache registration failed", error);
        showFeedback("离线功能暂不可用，但不影响当前使用；可稍后刷新重试。");
      }
    },
    { once: true },
  );
})();
