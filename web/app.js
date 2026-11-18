const $ = (id) => document.getElementById(id);
const paths = {
  terminal: "m5 6 6 6-6 6m9 0h5",
  edit: "M12 20H4v-8M14 4l6 6M8 16l2-6L18 2l4 4-8 8z",
  search: "m16 16 5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
  plus: "M12 5v14M5 12h14",
  refresh: "M20 7v5h-5M4 17v-5h5M6 6a8 8 0 0 1 13 2M18 18A8 8 0 0 1 5 16",
  monitor: "M3 4h18v13H3zM8 21h8m-4-4v4",
  settings:
    "m9 3 6 0 1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1zM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  close: "m6 6 12 12M6 18 18 6",
  panel: "M3 4h18v16H3zM9 4v16m-3-12v1m0 3v1",
  sun: "M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  chevron: "m8 10 4 4 4-4",
  more: "M5 12h.01M12 12h.01M19 12h.01",
  folder: "M3 6h7l2 2h9v12H3z",
  arrow: "M12 20V4m-6 6 6-6 6 6",
  "arrow-right": "M4 12h16m-6-6 6 6-6 6",
  code: "m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18",
  bug: "M8 8h8v8a4 4 0 0 1-8 0zm2-4h4l2 4M4 8l4 3m8 0 4-3M3 15h5m8 0h5M5 21l4-3m6 0 4 3",
  spark: "m12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3z",
  lock: "M6 10h12v11H6zM8 10V6a4 4 0 0 1 8 0v4",
  stop: "M7 7h10v10H7z",
  download: "M12 3v12m-5-5 5 5 5-5M5 17v4h14v-4",
  attach: "m8 13 7-7a3 3 0 0 1 4 4l-9 9a5 5 0 0 1-7-7l9-9",
  file: "M5 3h9l5 5v13H5zM14 3v6h5M8 13h8m-8 4h6",
  trash: "M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7",
};
const iconCache = new Map();
const icon = (name) => {
  if (!iconCache.has(name))
    iconCache.set(
      name,
      `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[name] || paths.code}"/></svg>`,
    );
  return iconCache.get(name);
};
function hydrateIcons(root = document) {
  root
    .querySelectorAll("[data-icon]")
    .forEach((el) => (el.innerHTML = icon(el.dataset.icon)));
}
hydrateIcons();

const escapeHTML = (text = "") =>
  String(text).replace(
    /[&<>"']/g,
    (ch) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        ch
      ],
  );
const readStorage = (key, fallback) => {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
};
const saved = readStorage("codexrm.devices.v2", []);
const state = {
  devices: Array.isArray(saved)
    ? saved.filter((d) => d?.token && d?.agent?.agentId)
    : [],
  selected: readStorage("codexrm.selected", null),
  socket: null,
  generation: 0,
  online: false,
  reconnecting: false,
  pairingExpired: false,
  backgroundSuspended: false,
  visibilityTimer: null,
  pingID: null,
  nextPingID: 0,
  loaded: false,
  threads: [],
  cursor: null,
  current: null,
  turns: [],
  pending: new Map(),
  nextID: 0,
  requests: new Map(),
  responding: new Set(),
  approvalCards: new Map(),
  info: null,
  models: [],
  providers: [],
  modelsLoading: false,
  modelListVersion: 0,
  sending: false,
  creating: null,
  messageQueues: new Map(),
  loading: false,
  readOnly: false,
  detached: false,
  started: 0,
  historyCursor: null,
  historyVersion: 0,
  olderTurnsRequest: null,
  openThreadRequest: null,
  bootstrapPromise: null,
  bootstrapGeneration: -1,
  settings: readStorage("codexrm.settings", {}),
  workspaceViews: readStorage("codexrm.workspaceViews", {}),
  workspaceSectionCollapsed: readStorage("codexrm.workspaceSectionCollapsed", false) === true,
  drafts: readStorage("codexrm.drafts.v1", {}),
  attachmentDrafts: readStorage("codexrm.attachments.v1", {}),
  attachmentCache: new Map(),
  workspaces: [],
  threadListVersion: 0,
  threadListLoading: false,
  archivingThreads: new Set(),
  archivedThreads: new Set(),
  snapshotTimer: null,
  snapshotOlder: false,
  directory: null,
  directoryVersion: 0,
  refreshTimer: null,
  toastTimer: null,
};
if (!state.devices.some((d) => d.agent.agentId === state.selected))
  state.selected = state.devices[0]?.agent.agentId || null;
const currentDevice = () =>
  state.devices.find((d) => d.agent.agentId === state.selected);
function persist() {
  try {
    localStorage.setItem("codexrm.devices.v2", JSON.stringify(state.devices));
    localStorage.setItem("codexrm.selected", JSON.stringify(state.selected));
  } catch {
    toast("浏览器存储不可用，刷新后需重新配对");
  }
}
function workspaceView() {
  const id = state.selected || "none";
  const view = (state.workspaceViews[id] ||= { selected: "" });
  view.collapsed ||= {};
  return view;
}
function pathKey(path = "") {
  const windows = state.info?.os === "windows";
  let value = windows ? path.replaceAll("\\", "/").toLowerCase() : path;
  return value.replace(/\/+$/, "") || "/";
}
function selectedCwd() {
  return workspaceView().selected || state.info?.cwd || "";
}
function draftKey() {
  if (!state.selected) return "";
  return state.current?.id
    ? `${state.selected}:thread:${state.current.id}`
    : `${state.selected}:workspace:${pathKey(selectedCwd())}`;
}
function writeDraft(key, value) {
  if (!key) return;
  delete state.drafts[key];
  if (value) state.drafts[key] = value;
  const keys = Object.keys(state.drafts);
  for (const old of keys.slice(0, Math.max(0, keys.length - 50))) delete state.drafts[old];
  try {
    localStorage.setItem("codexrm.drafts.v1", JSON.stringify(state.drafts));
  } catch {}
}
function saveDraft() {
  writeDraft(draftKey(), $("prompt").value);
}
function restoreDraft() {
  $("prompt").value = state.drafts[draftKey()] || "";
  renderAttachments();
  resizePrompt();
}

const attachmentMarker = "[CodexRM attachment] ";
const visionMimeTypes = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);
function currentAttachments() {
  return state.attachmentDrafts[draftKey()] || [];
}
function persistAttachments() {
  const drafts = {};
  for (const [key, files] of Object.entries(state.attachmentDrafts)) {
    const ready = files.filter((f) => f.path && !f.uploading && !f.error && !f.cancelled)
      .map(({ id, name, path, size, mimeType }) => ({ id, name, path, size, mimeType }));
    if (ready.length) drafts[key] = ready;
  }
  try { localStorage.setItem("codexrm.attachments.v1", JSON.stringify(drafts)); } catch {}
}
function fileSize(size) {
  return size >= 1024 * 1024 ? `${(size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.ceil(size / 1024))} KB`;
}
function renderAttachments() {
  const root = $("attachments");
  const files = currentAttachments();
  root.hidden = !files.length;
  root.replaceChildren();
  for (const file of files) {
    const card = document.createElement("div");
    card.className = `attachment-card${file.error ? " attachment-error" : ""}`;
    const preview = state.attachmentCache.get(`${state.selected}:${file.id}`)?.url;
    const image = visionMimeTypes.has(file.mimeType);
    card.innerHTML = `${image && file.id ? `<img data-attachment-image="${escapeHTML(file.id)}" ${preview ? `src="${escapeHTML(preview)}"` : ""} alt="" />` : `<span class="attachment-icon">${icon("file")}</span>`}<span class="attachment-info"><strong>${escapeHTML(file.name)}</strong><small>${escapeHTML(file.error || (file.uploading ? `上传中 ${file.progress || 0}%` : fileSize(file.size)))}</small></span>`;
    if (file.error && file.file) {
      const retry = document.createElement("button");
      retry.type = "button";
      retry.textContent = "重试";
      retry.disabled = !state.online || state.sending;
      retry.onclick = () => uploadAttachment(file, draftKey());
      card.append(retry);
    }
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "icon-button";
    remove.innerHTML = icon("close");
    remove.setAttribute("aria-label", `移除 ${file.name}`);
    remove.disabled = state.sending;
    remove.onclick = () => {
      file.cancelled = true;
      state.attachmentDrafts[draftKey()] = files.filter((f) => f !== file);
      persistAttachments();
      renderAttachments();
      renderComposerControls();
    };
    card.append(remove);
    root.append(card);
  }
  hydrateAttachmentImages();
}
function attachmentBlockedReason() {
  if (!state.online) return "设备离线，请先启动本地 Agent";
  if (!state.loaded || state.loading) return "正在同步工作区，请稍候再添加附件";
  if (state.readOnly) return "此会话由另一个 Codex 实例运行，不能添加附件";
  if (state.sending) return "请等待当前消息发送完成再添加附件";
  if (!state.info?.attachments) return "请更新本地 Agent 以支持文件和图片";
  return "";
}
async function addAttachments(inputFiles) {
  const blocked = attachmentBlockedReason();
  if (blocked) return toast(blocked);
  const key = draftKey();
  const generation = state.generation;
  const files = (state.attachmentDrafts[key] ||= []);
  const pending = [];
  for (const file of inputFiles) {
    if (files.length >= 10) { toast("每条消息最多添加 10 个附件"); break; }
    if (file.size > state.info.attachments.maxSize) { toast(`${file.name} 超过单个附件 20 MB 的限制`); continue; }
    if (files.reduce((sum, f) => sum + f.size, 0) + file.size > 50 * 1024 * 1024) { toast("每条消息的附件总大小不能超过 50 MB"); continue; }
    const staged = { name: file.name, size: file.size, mimeType: file.type, file, uploading: true, progress: 0 };
    files.push(staged);
    pending.push(staged);
  }
  renderAttachments();
  renderComposerControls();
  // Serialize uploads so a selection of many files does not flood the relay.
  for (const file of pending) if (!file.cancelled) {
    if (generation !== state.generation) {
      file.uploading = false;
      file.error = "连接已切换，请重试上传";
      continue;
    }
    await uploadAttachment(file, key);
  }
  if (key === draftKey()) { renderAttachments(); renderComposerControls(); }
}
function pasteAttachments(event) {
  if (event.defaultPrevented || !event.clipboardData || attachmentBlockedReason()) return;
  const composer = $("composer");
  const modal = document.querySelector("dialog[open], [aria-modal='true']");
  if (composer.closest("[hidden], [inert]") || (modal && !modal.contains(composer)) || !$("model-menu").hidden) return;
  const origin = event.composedPath?.().find((node) => node?.closest) || event.target;
  if (origin !== $("prompt") && origin?.closest("input, textarea, select, [contenteditable]:not([contenteditable='false'])")) return;
  const files = [...(event.clipboardData.items || [])]
    .filter((item) => item.kind === "file").map((item) => item.getAsFile()).filter(Boolean);
  if (!files.length) files.push(...(event.clipboardData.files || []));
  if (!files.length) return;
  event.preventDefault();
  return addAttachments(files).catch(showError);
}
async function uploadAttachment(file, key) {
  const generation = state.generation;
  const device = state.selected;
  let id;
  file.uploading = true;
  file.error = "";
  const active = () => generation === state.generation && device === state.selected && !file.cancelled;
  const render = () => {
    if (key === draftKey()) { renderAttachments(); renderComposerControls(); }
  };
  render();
  try {
    const begun = await rpc("codexrm/attachment/begin", { name: file.name, size: file.size });
    id = begun.id;
    if (!active()) throw new Error("上传已中断，请重试");
    const chunkSize = Math.min(begun.chunkSize || 262144, 262144);
    for (let offset = 0; offset < file.size;) {
      const bytes = new Uint8Array(await file.file.slice(offset, offset + chunkSize).arrayBuffer());
      let binary = "";
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      if (!active()) throw new Error("上传已中断，请重试");
      const result = await rpc("codexrm/attachment/chunk", { id, offset, data: btoa(binary) });
      if (!active()) throw new Error("上传已中断，请重试");
      if (result.offset !== offset + bytes.length) throw new Error("附件上传位置不匹配，请重试");
      offset = result.offset;
      file.progress = Math.round(offset / file.size * 100);
      render();
    }
    if (!active()) throw new Error("上传已中断，请重试");
    const result = await rpc("codexrm/attachment/finish", { id });
    if (!active()) throw new Error("上传已中断，请重试");
    Object.assign(file, result);
    if (visionMimeTypes.has(file.mimeType)) cacheAttachment(`${device}:${file.id}`, { url: URL.createObjectURL(file.file), size: file.size });
    delete file.file;
  } catch (error) {
    file.error = error.message;
    if (id && generation === state.generation && state.online) rpc("codexrm/attachment/abort", { id }).catch(() => {});
  } finally {
    file.uploading = false;
    persistAttachments();
    render();
  }
}
function cacheAttachment(key, value) {
  const old = state.attachmentCache.get(key);
  if (old?.url && old.url !== value.url) URL.revokeObjectURL(old.url);
  state.attachmentCache.delete(key);
  state.attachmentCache.set(key, value);
  let bytes = [...state.attachmentCache.values()].reduce((sum, entry) => sum + (entry.blob?.size || entry.size || 0), 0);
  while (state.attachmentCache.size > 40 || bytes > 64 * 1024 * 1024) {
    const oldest = state.attachmentCache.keys().next().value;
    const entry = state.attachmentCache.get(oldest);
    if (entry.url) URL.revokeObjectURL(entry.url);
    bytes -= entry.blob?.size || entry.size || 0;
    state.attachmentCache.delete(oldest);
  }
}
function messageInput(text, files = []) {
  const input = text ? [{ type: "text", text, text_elements: [] }] : [];
  for (const file of files) {
    const { id, name, path, size, mimeType } = file;
    input.push({ type: "text", text: attachmentMarker + JSON.stringify({ id, name, path, size, mimeType }), text_elements: [] });
    if (visionMimeTypes.has(mimeType)) input.push({ type: "localImage", path });
  }
  return input;
}
function clearSentDraft(key, files) {
  writeDraft(key, "");
  state.attachmentDrafts[key] = (state.attachmentDrafts[key] || []).filter((f) => !files.includes(f));
  const current = key === draftKey();
  if (current) $("prompt").value = "";
  persistAttachments();
  if (current) { renderAttachments(); resizePrompt(); }
}
function restoreSentDraft(key, original, files) {
  const current = key === draftKey();
  const newer = current ? $("prompt").value : state.drafts[key] || "";
  const text = original + (newer ? `\n\n${newer}` : "");
  writeDraft(key, text);
  const staged = state.attachmentDrafts[key] || [];
  state.attachmentDrafts[key] = [...files, ...staged.filter((f) => !files.some((sent) => sent.id === f.id))];
  if (current) $("prompt").value = text;
  persistAttachments();
  if (current) { renderAttachments(); resizePrompt(); }
}
function restoreCreatingDraft(operation) {
  if (!operation || operation.restored) return;
  operation.restored = true;
  restoreSentDraft(operation.originalKey, operation.original, operation.files);
}
function cancelThreadCreation() {
  const operation = state.creating;
  if (!operation) return;
  operation.cancelled = true;
  state.creating = null;
  restoreCreatingDraft(operation);
  state.sending = false;
}
function userContent(content = []) {
  const files = [];
  const text = [];
  for (const c of content) {
    if (c.type === "text") {
      for (const line of (c.text || "").split("\n")) {
        if (line.startsWith(attachmentMarker)) {
          try {
            const file = JSON.parse(line.slice(attachmentMarker.length));
            if (typeof file.id === "string" && typeof file.name === "string" && typeof file.path === "string") { files.push(file); continue; }
          } catch {}
        }
        text.push(line);
      }
    }
  }
  for (const c of content) {
    if ((c.type === "localImage" || c.type === "image") && !files.some((f) => f.path === c.path)) text.push("[图片]");
  }
  return { text: text.join("\n").trim(), files };
}
function attachmentHTML(file) {
  const cached = state.attachmentCache.get(`${state.selected}:${file.id}`);
  const image = visionMimeTypes.has(file.mimeType);
  return `<button type="button" class="message-attachment${image ? " image-attachment" : ""}" data-attachment="${escapeHTML(file.id)}" title="下载 ${escapeHTML(file.name)}">${image ? `<img data-attachment-image="${escapeHTML(file.id)}" ${cached?.url ? `src="${escapeHTML(cached.url)}"` : ""} alt="${escapeHTML(file.name)}" loading="lazy" />` : icon("file")}<span><strong>${escapeHTML(file.name)}</strong><small>${fileSize(file.size)} · 下载</small></span></button>`;
}
async function readAttachment(id) {
  const generation = state.generation;
  const key = `${state.selected}:${id}`;
  const cached = state.attachmentCache.get(key);
  if (cached?.blob) return cached;
  if (cached?.pending) return cached.pending;
  const pending = (async () => {
    const chunks = [];
    let offset = 0;
    let file;
    do {
      const result = await rpc("codexrm/attachment/read", { id, offset });
      if (generation !== state.generation) throw new Error("连接已切换，请重试");
      file = result.file;
      if (file.size > 20 * 1024 * 1024 || result.offset > file.size || result.offset < offset || (result.offset === offset && offset < file.size)) throw new Error("附件内容不完整");
      const bytes = Uint8Array.from(atob(result.data), (c) => c.charCodeAt(0));
      if (result.offset !== offset + bytes.length) throw new Error("附件内容不完整");
      chunks.push(bytes);
      offset = result.offset;
    } while (offset < file.size);
    const blob = new Blob(chunks, { type: file.mimeType });
    const entry = { blob, file, url: URL.createObjectURL(blob) };
    cacheAttachment(key, entry);
    return entry;
  })();
  cacheAttachment(key, { ...cached, pending });
  try { return await pending; }
  catch (error) {
    if (state.attachmentCache.get(key)?.pending === pending) {
      if (cached?.url) cacheAttachment(key, cached);
      else state.attachmentCache.delete(key);
    }
    throw error;
  }
}
function hydrateAttachmentImages() {
  if (!state.online || !state.info?.attachments || typeof IntersectionObserver === "undefined") return;
  attachmentImageObserver ||= new IntersectionObserver((entries) => {
    for (const entry of entries) if (entry.isIntersecting) {
      const img = entry.target;
      attachmentImageObserver.unobserve(img);
      const generation = state.generation;
      readAttachment(img.dataset.attachmentImage).then((file) => {
        if (img.isConnected && generation === state.generation) img.src = file.url;
      }).catch(() => { img.alt = "图片暂时无法加载，点击重试下载"; });
    }
  });
  attachmentImageObserver.disconnect();
  for (const root of [$("messages"), $("attachments")])
    root.querySelectorAll("img[data-attachment-image]:not([src])").forEach((img) => attachmentImageObserver.observe(img));
}
let attachmentImageObserver;
function saveWorkspaceView() {
  try {
    localStorage.setItem(
      "codexrm.workspaceViews",
      JSON.stringify(state.workspaceViews),
    );
  } catch {}
}
function folderName(path = "") {
  return (
    path
      .replace(/[\\/]+$/, "")
      .split(/[\\/]/)
      .pop() || path
  );
}
function renderWorkspacePath() {
  const path = state.current?.cwd || selectedCwd();
  const select = $("workspace-select");
  select.replaceChildren();
  const list = workspaceGroups().filter((w) => w.path);
  if (path && !list.some((w) => pathKey(w.path) === pathKey(path)))
    list.push({ name: folderName(path), path });
  if (!list.length) select.append(new Option("选择工作区文件夹", ""));
  for (const w of list) select.append(new Option(w.path, w.path));
  select.value =
    list.find((w) => pathKey(w.path) === pathKey(path))?.path || "";
  select.title = path ? `${path} · 切换后新建会话` : "选择工作区";
  select.disabled = state.sending || state.loading || !state.online;
}
async function loadWorkspaces() {
  const generation = state.generation;
  const result = state.info?.workspaces
    ? await rpc("codexrm/workspace/list")
    : {
        workspaces: state.info?.cwd
          ? [{ name: folderName(state.info.cwd), path: state.info.cwd }]
          : [],
      };
  if (generation !== state.generation) return;
  state.workspaces = result.workspaces || [];
  const view = workspaceView();
  if (!view.selected)
    view.selected = state.workspaces[0]?.path || state.info?.cwd || "";
  if (
    view.filter &&
    !state.workspaces.some((w) => pathKey(w.path) === pathKey(view.filter))
  )
    view.filter = null;
  saveWorkspaceView();
  renderWorkspaces();
}
function renderWorkspaceSection() {
  const collapsed = state.workspaceSectionCollapsed;
  const toggle = $("toggle-workspaces");
  $("workspace-history").hidden = collapsed;
  toggle.classList.toggle("collapsed", collapsed);
  toggle.setAttribute("aria-expanded", String(!collapsed));
  toggle.title = collapsed ? "展开工作区" : "收起工作区";
  toggle.setAttribute("aria-label", toggle.title);
}
function toggleWorkspaceSection() {
  state.workspaceSectionCollapsed = !state.workspaceSectionCollapsed;
  try {
    localStorage.setItem(
      "codexrm.workspaceSectionCollapsed",
      JSON.stringify(state.workspaceSectionCollapsed),
    );
  } catch {}
  renderWorkspaceSection();
}
function renderWorkspaces() {
  renderWorkspaceSection();
  renderThreads();
  renderWorkspacePath();
}
async function selectWorkspace(path) {
  if (state.sending || state.loading) {
    toast("请等待当前请求完成后切换工作区");
    return;
  }
  saveDraft();
  const view = workspaceView();
  view.selected = path;
  view.collapsed[pathKey(path)] = false;
  saveWorkspaceView();
  newChat(false);
  renderWorkspaces();
}
async function removeWorkspace(path) {
  const generation = state.generation;
  const result = await rpc("codexrm/workspace/remove", { path });
  if (generation !== state.generation) return;
  state.workspaces = result.workspaces || [];
  const view = workspaceView();
  if (pathKey(view.selected) === pathKey(path)) {
    view.selected = state.workspaces[0]?.path || state.info?.cwd || "";
  }
  if (view.filter && pathKey(view.filter) === pathKey(path)) view.filter = null;
  saveWorkspaceView();
  renderWorkspaces();
  await loadThreads();
  toast("已取消固定；有历史会话的文件夹仍会自动显示");
}
async function browseDirectory(path) {
  const generation = state.generation;
  const version = ++state.directoryVersion;
  $("directory-error").textContent = "";
  $("select-directory").disabled = true;
  $("directory-list").setAttribute("aria-busy", "true");
  try {
    const result = await rpc("codexrm/fs/list", {
      path,
      showHidden: $("directory-hidden").checked,
    });
    if (generation !== state.generation || version !== state.directoryVersion)
      return;
    state.directory = result;
    $("directory-path").value = result.path;
    $("directory-path").title = result.path;
    const shortcuts = $("directory-shortcuts");
    shortcuts.replaceChildren();
    for (const w of [
      { name: "上一级", path: result.parent },
      { name: "主目录", path: result.home },
      ...(result.roots || []),
    ]) {
      if (!w.path) continue;
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = w.name;
      b.title = w.path;
      b.onclick = () => browseDirectory(w.path);
      shortcuts.append(b);
    }
    const list = $("directory-list");
    list.replaceChildren();
    for (const w of result.directories || []) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "directory-entry";
      b.innerHTML = `${icon("folder")}<span>${escapeHTML(w.name)}</span>${icon("arrow-right")}`;
      b.title = w.path;
      b.onclick = () => browseDirectory(w.path);
      list.append(b);
    }
    if (!result.directories?.length) {
      const empty = document.createElement("p");
      empty.className = "empty-hint";
      empty.textContent = "此目录没有可显示的子文件夹，可以直接添加。";
      list.append(empty);
    }
    if (result.truncated)
      $("directory-error").textContent =
        "仅显示前 500 个子目录，也可以输入完整路径。";
    $("select-directory").disabled = false;
  } catch (e) {
    if (generation !== state.generation || version !== state.directoryVersion)
      return;
    state.directory = null;
    $("directory-error").textContent = e.message;
  } finally {
    if (version === state.directoryVersion)
      $("directory-list").setAttribute("aria-busy", "false");
  }
}
function openWorkspacePicker() {
  if (!state.online) return toast("先连接设备，再添加这台设备上的工作区");
  if (!state.info?.workspaces)
    return toast("请更新本地连接器，以使用多工作区和目录浏览功能");
  state.directory = null;
  $("directory-device").textContent = `浏览 ${state.info.name} 上的文件夹`;
  $("directory-list").replaceChildren();
  $("directory-shortcuts").replaceChildren();
  $("directory-hidden").checked = false;
  $("workspace-dialog").showModal();
  browseDirectory(selectedCwd() || state.info.cwd);
}
function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => {
    $("toast").hidden = true;
  }, 4500);
}
function connectionNotice(status = "") {
  state.reconnecting = status === "reconnecting";
  clearTimeout(state.connectionNoticeTimer);
  const notice = $("connection-notice");
  notice.dataset.status = status;
  notice.hidden = !status;
  $("connection-notice-text").textContent = state.reconnecting
    ? "连接被断开，重连中"
    : status === "success" ? "重连成功" : "";
  if (status === "success" && !document.hidden) {
    state.connectionNoticeTimer = setTimeout(() => {
      notice.hidden = true;
      notice.dataset.status = "";
    }, 4500);
  }
}
function showError(error) {
  toast(error?.message || String(error));
}
async function api(path, body, token) {
  const response = await fetch(path, {
    method: body ? "POST" : "GET",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await response
    .json()
    .catch(() => ({ error: `HTTP ${response.status}` }));
  if (!response.ok) {
    const error = new Error(data.error || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}
let detectedClientProfile;
function browserClientProfile() {
  if (detectedClientProfile) return detectedClientProfile;
  const nav = globalThis.navigator || {};
  const ua = nav.userAgent || "";
  const mobile = nav.userAgentData?.mobile || /Android|iPhone|iPad|Mobile/i.test(ua);
  const platform = /Android/i.test(ua) ? "Android"
    : /iPhone|iPad/i.test(ua) || (nav.platform === "MacIntel" && nav.maxTouchPoints > 1) ? "iOS"
    : /Windows/i.test(ua) ? "Windows"
    : /Macintosh|Mac OS X/i.test(ua) ? "macOS"
    : /Linux/i.test(ua) ? "Linux"
    : nav.userAgentData?.platform || nav.platform || "未知系统";
  const browser = /Edg(?:e|A|iOS)?\//i.test(ua) ? "Edge"
    : /Firefox|FxiOS/i.test(ua) ? "Firefox"
    : /Chrome|CriOS/i.test(ua) ? "Chrome"
    : /Safari/i.test(ua) ? "Safari" : "浏览器";
  let clientName = `${platform} · ${browser}`;
  let gl;
  try {
    const canvas = document.createElement("canvas");
    gl = canvas.getContext("webgl2") || canvas.getContext("webgl") || canvas.getContext("experimental-webgl");
    const info = gl?.getExtension("WEBGL_debug_renderer_info");
    const renderer = info && gl.getParameter(info.UNMASKED_RENDERER_WEBGL);
    if (typeof renderer === "string" && renderer.trim()) clientName = renderer.trim();
  } catch {
    // WebGL and renderer information may be unavailable in this browser.
  } finally {
    try { gl?.getExtension("WEBGL_lose_context")?.loseContext(); } catch {}
  }
  detectedClientProfile = { clientName, deviceType: mobile || platform === "iOS" ? "mobile" : "desktop" };
  return detectedClientProfile;
}
const syncedClientProfiles = new Map();
async function syncClientProfile(device) {
  const profile = browserClientProfile();
  const signature = JSON.stringify(profile);
  if (syncedClientProfiles.get(device.token) === signature) return;
  await api("/api/v1/client/profile", profile, device.token);
  syncedClientProfiles.set(device.token, signature);
}
function disconnectSocket({ preservePairingExpired = false } = {}) {
  closeThreadMenu();
  $("delete-thread-dialog").close();
  state.archivingThreads.clear();
  cancelThreadCreation();
  state.generation++;
  clearTimeout(state.snapshotTimer);
  state.detached = false;
  state.historyVersion++;
  state.olderTurnsRequest = null;
  state.openThreadRequest = null;
  clearTimeout(state.reconnectTimer);
  clearTimeout(state.connectionTimer);
  clearTimeout(state.pongTimer);
  clearInterval(state.pingTimer);
  state.pingID = null;
  if (!preservePairingExpired) state.pairingExpired = false;
  connectionNotice();
  if (state.socket) {
    state.socket.onclose = null;
    state.socket.close();
    state.socket = null;
  }
  for (const p of state.pending.values()) {
    clearTimeout(p.timer);
    p.reject(new Error("连接已断开"));
  }
  state.pending.clear();
  state.online = false;
  state.loaded = false;
  state.sending = false;
  state.loading = false;
  state.requests.clear();
  state.responding.clear();
  renderApprovals();
}
function rpc(method, params = {}, timeoutMs = 120000) {
  return new Promise((resolve, reject) => {
    if (!state.online || state.socket?.readyState !== WebSocket.OPEN) {
      reject(new Error("设备未连接，请确认本地 Agent 正在运行"));
      return;
    }
    const id = ++state.nextID;
    const timer = setTimeout(() => {
      state.pending.delete(id);
      reject(new Error("请求超时，请检查本地 Codex 是否正常运行"));
    }, timeoutMs);
    state.pending.set(id, { resolve, reject, timer });
    state.socket.send(
      JSON.stringify({
        type: "client_message",
        streamId: String(id),
        message: { id, method, params },
      }),
    );
  });
}
function sendResponse(id, result, error) {
  if (state.socket?.readyState !== WebSocket.OPEN || !state.online) {
    toast("连接已断开，恢复后可继续审批");
    return false;
  }
  state.socket.send(
    JSON.stringify({
      type: "client_message",
      message: { id, ...(error ? { error } : { result }) },
    }),
  );
  return true;
}
function pingSocket() {
  const socket = state.socket;
  if (socket?.readyState !== WebSocket.OPEN || state.pingID !== null) return;
  const generation = state.generation;
  state.pingID = String(++state.nextPingID);
  state.pongTimer = setTimeout(() => {
    if (generation === state.generation) connect({ reconnect: true });
  }, 5000);
  socket.send(JSON.stringify({ type: "ping", pingId: state.pingID }));
}
function resumeConnection() {
  if (document.hidden || !currentDevice() || state.pairingExpired) return;
  if ($("connection-notice").dataset.status === "success") connectionNotice("success");
  if (state.socket?.readyState === WebSocket.OPEN) {
    pingSocket();
    if (!state.loaded) void bootstrap(state.generation);
  } else if (state.socket?.readyState !== WebSocket.CONNECTING) {
    connect({ reconnect: true });
  }
}
function handleVisibilityChange() {
  if (document.visibilityState === "hidden") {
    if (state.visibilityTimer !== null || state.backgroundSuspended) return;
    state.visibilityTimer = setTimeout(() => {
      state.visibilityTimer = null;
      if (document.visibilityState !== "hidden") return;
      state.backgroundSuspended = true;
      disconnectSocket({ preservePairingExpired: true });
      renderDevices();
      renderConnection();
    }, 5000);
    return;
  }
  clearTimeout(state.visibilityTimer);
  state.visibilityTimer = null;
  state.backgroundSuspended = false;
  resumeConnection();
}
function connect({ reconnect = false } = {}) {
  if (state.backgroundSuspended && document.visibilityState === "hidden") return;
  state.backgroundSuspended = false;
  disconnectSocket();
  const device = currentDevice();
  if (device && reconnect) connectionNotice("reconnecting");
  renderDevices();
  renderConnection();
  if (!device) return;
  const generation = state.generation;
  const url = new URL("/api/v1/client/ws", location.href);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(url, [
    "codexrm",
    `codexrm.token.${device.token}`,
  ]);
  state.socket = socket;
  state.connectionTimer = setTimeout(() => {
    if (generation === state.generation) connect({ reconnect: true });
  }, 10000);
  socket.onopen = () => {
    if (generation !== state.generation) return;
    state.pingTimer = setInterval(pingSocket, 25000);
    void syncClientProfile(device).catch(() => {});
  };
  socket.onmessage = (event) => {
    if (generation !== state.generation) return;
    let envelope;
    try {
      envelope = JSON.parse(event.data);
    } catch {
      return;
    }
    if (envelope.type === "welcome" || envelope.type === "agent_status") {
      clearTimeout(state.connectionTimer);
      const wasOnline = state.online;
      state.online = envelope.status === "connected";
      if (state.online && state.reconnecting) connectionNotice("success");
      else if (!state.online && wasOnline) connectionNotice("reconnecting");
      if (envelope.agent) {
        device.agent = envelope.agent;
        persist();
      }
      renderDevices();
      renderConnection();
      if (state.online && !wasOnline) {
        state.loaded = false;
        void bootstrap(generation);
      }
      if (!state.online) {
        state.loaded = false;
        state.requests.clear();
        renderApprovals();
        rejectPending("远程设备已离线");
      }
    } else if (envelope.type === "pong") {
      if (envelope.pingId === state.pingID) {
        clearTimeout(state.pongTimer);
        state.pingID = null;
      }
    } else if (envelope.type === "server_message") {
      const msg = envelope.message;
      if (!msg) return;
      if (msg.method) {
        onEvent(msg);
        return;
      }
      const pending = state.pending.get(msg.id);
      if (!pending) return;
      state.pending.delete(msg.id);
      clearTimeout(pending.timer);
      if (msg.error) {
        const e = new Error(msg.error.message || "Codex 请求失败");
        e.code = msg.error.code;
        pending.reject(e);
      } else pending.resolve(msg.result);
    } else if (envelope.type === "error") {
      const p = state.pending.get(Number(envelope.streamId));
      if (p) {
        clearTimeout(p.timer);
        state.pending.delete(Number(envelope.streamId));
        p.reject(new Error(envelope.error));
      } else toast(envelope.error);
    }
  };
  socket.onclose = async () => {
    if (generation !== state.generation) return;
    clearInterval(state.pingTimer);
    clearTimeout(state.connectionTimer);
    clearTimeout(state.pongTimer);
    state.pingID = null;
    connectionNotice("reconnecting");
    state.online = false;
    state.loaded = false;
    state.requests.clear();
    renderApprovals();
    rejectPending("服务器连接中断");
    renderConnection();
    renderDevices();
    state.reconnectTimer = setTimeout(() => connect({ reconnect: true }), 3000);
    try {
      await api("/api/v1/agents", null, device.token);
    } catch (error) {
      if (generation !== state.generation) return;
      if (error.status === 401) {
        clearTimeout(state.reconnectTimer);
        state.pairingExpired = true;
        connectionNotice();
        renderConnection();
        toast("此浏览器的配对已失效，请解除后重新配对");
        return;
      }
    }
  };
  socket.onerror = () => {};
}
function rejectPending(message) {
  for (const p of state.pending.values()) {
    clearTimeout(p.timer);
    p.reject(new Error(message));
  }
  state.pending.clear();
}
async function bootstrap(generation) {
  if (state.bootstrapPromise) {
    if (state.bootstrapGeneration === generation) return state.bootstrapPromise;
    return;
  }
  const active = () => generation === state.generation;
  state.loaded = false;
  renderConnection();
  const run = (async () => {
    try {
      const info = await rpc("codexrm/info");
      if (!active()) return;
      state.info = info;
      state.messageQueues.clear();
      $("demo-badge").hidden = !info.mock;
      if (info.workspaces && state.settings.cwd && !workspaceView().selected) {
        try {
          await rpc("codexrm/workspace/add", { path: state.settings.cwd });
          if (!active()) return;
          workspaceView().selected = state.settings.cwd;
          saveWorkspaceView();
        } catch (e) {
          if (active()) toast(`原工作目录无法添加：${e.message}`);
        }
        if (!active()) return;
        delete state.settings.cwd;
        try {
          localStorage.setItem(
            "codexrm.settings",
            JSON.stringify(state.settings),
          );
        } catch {}
      }
      await loadWorkspaces();
      if (!active()) return;
      renderWorkspacePath();
      restoreDraft();
      $("welcome-description").textContent = info.mock
        ? "已连接演示设备，可以发送任务体验完整流程。"
        : `已连接 ${info.name}，让 Codex 继续手边的工作。`;
      state.loaded = true;
      renderConnection();
      const results = await Promise.allSettled([
        loadThreads(false),
        loadModels(),
      ]);
      if (!active()) return;
      if (results[0].status === "rejected")
        toast(`无法读取会话：${results[0].reason.message}`);
      if (results[1].status === "rejected")
        toast(`无法读取供应商模型：${results[1].reason.message}`);
      if (state.current) {
        const id = state.current.id;
        await openThread(id, true);
      }
    } catch (error) {
      if (active()) {
        state.loaded = false;
        showError(error);
      }
    }
  })();
  state.bootstrapPromise = run;
  state.bootstrapGeneration = generation;
  try {
    await run;
  } finally {
    if (state.bootstrapPromise === run) {
      state.bootstrapPromise = null;
      state.bootstrapGeneration = -1;
      if (active()) renderConnection();
    }
  }
}
function renderConnection() {
  const device = currentDevice();
  const connected = state.online;
  $("connection-button").classList.toggle("online", connected);
  $("connection-label").textContent = connected
    ? device?.agent.serverName || "已连接"
    : state.pairingExpired
      ? "配对已过期"
      : state.reconnecting
        ? "重连中…"
        : device
          ? "设备离线"
          : "连接设备";
  $("sidebar-status").textContent = connected
    ? "设备已连接 · 任务在本地运行"
    : device
      ? "等待本地连接器上线"
      : "尚未连接设备";
  renderComposerControls();
  renderWorkspacePath();
  renderMessageQueue();
}
function renderComposerControls() {
  const running = isRunning();
  const unavailable = state.readOnly || state.loading || state.sending || !state.online || !state.loaded;
  const files = currentAttachments();
  const attachmentPending = files.some((f) => f.uploading || f.error);
  const queue = state.messageQueues.get(state.current?.id);
  const hasQueue = queue?.entries?.length > 0;
  const hasText = (!!$("prompt").value.trim() || files.length > 0) && !attachmentPending;
  $("attach-files").disabled = unavailable || !state.info?.attachments;
  $("attach-files").title = state.info?.attachments ? "添加文件或图片（每个最大 20 MB）" : "请更新本地 Agent 以支持文件和图片";
  const sendLabel = hasQueue ? "排队发送" : "发送消息";
  $("send").disabled = unavailable || !hasText || (hasQueue && !state.info?.messageQueue);
  $("send").hidden = running;
  $("send").title = sendLabel;
  $("send").setAttribute("aria-label", sendLabel);
  $("stop").hidden = !running;
  $("stop").disabled = unavailable;
  $("running-actions").hidden = !running;
  $("queue-send").disabled = unavailable || !hasText || !state.info?.messageQueue;
  $("queue-send").title = state.info?.messageQueue ? "当前任务完成后发送（Enter）" : "请更新本地 Agent 以支持排队发送";
  $("steer").disabled = unavailable || !hasText;
  $("composer-hint").textContent = running ? "Enter 排队 · Ctrl / ⌘ Enter 插队 · Shift Enter 换行" : "Enter 发送 · Shift Enter 换行";
  $("thread-actions").hidden = !state.current;
  $("activity").hidden = !running;
  $("activity-text").textContent = state.online
    ? "Codex 正在处理"
    : "连接已断开，恢复后同步任务";
  $("add-workspace").disabled = !state.online;
  $("model").disabled = !state.online || state.loading || state.sending || running || state.readOnly;
  $("effort").disabled = $("model").disabled;
  $("refresh-models").disabled = !state.online || state.modelsLoading;
  renderModelControl();
}
function selectDevice(device) {
  if (device.agent.agentId !== state.selected) {
    saveDraft();
    state.selected = device.agent.agentId;
    resetView();
    persist();
    connect();
  }
  closeSidebar();
}
function createDeviceButton(device) {
  const selected = device.agent.agentId === state.selected;
  const version = selected && state.info?.version ? state.info.version : device.agent.appVersion;
  const versionLabel = version ? ` · v${version}` : "";
  const button = document.createElement("button");
  button.type = "button";
  button.className = `device ${selected ? "selected" : ""} ${selected && state.online ? "online" : ""}`;
  button.setAttribute("aria-pressed", selected);
  button.innerHTML = `<span class="device-icon">${icon("monitor")}</span><span><strong>${escapeHTML(device.agent.serverName)}</strong><small>${escapeHTML(device.agent.os || "computer")}${escapeHTML(versionLabel)} · ${selected ? (state.online ? "在线" : "离线") : "已配对"}</small></span><i class="status-dot"></i>`;
  button.onclick = () => selectDevice(device);
  return button;
}
function renderDevices() {
  const root = $("devices");
  root.replaceChildren();
  if (!state.devices.length) {
    const button = document.createElement("button");
    button.className = "empty-device";
    button.innerHTML = icon("plus") + "<span>连接第一台设备</span>";
    button.onclick = () => openPair();
    root.append(button);
    return;
  }
  for (const device of state.devices) root.append(createDeviceButton(device));
}
function openDevicePicker() {
  const root = $("device-options");
  root.replaceChildren();
  if (!state.devices.length) {
    const empty = document.createElement("p");
    empty.className = "device-picker-empty";
    empty.textContent = "尚未配对设备。连接新设备后，可在这里切换。";
    root.append(empty);
  } else {
    for (const device of state.devices) {
      const button = createDeviceButton(device);
      button.onclick = () => {
        $("device-dialog").close();
        selectDevice(device);
      };
      root.append(button);
    }
  }
  $("device-dialog").showModal();
}
async function loadThreads(more = false) {
  const generation = state.generation;
  const version = ++state.threadListVersion;
  let cursor = more ? state.cursor : null;
  const seen = new Set();
  const threads = new Map(more ? state.threads.map((t) => [t.id, t]) : []);
  const baseline = new Map(state.threads.map((t) => [t.id, threadTime(t)]));
  const active = () => generation === state.generation && version === state.threadListVersion;
  state.threadListLoading = true;
  renderThreads();
  try {
    do {
      const result = await rpc("thread/list", {
        limit: 100,
        sortKey: "updated_at",
        sortDirection: "desc",
        modelProviders: [],
        ...(cursor ? { cursor } : {}),
        archived: false,
      });
      if (!active()) return;
      for (const thread of result.data || [])
        if (!state.archivedThreads.has(thread.id)) threads.set(thread.id, thread);
      // Keep notifications received while pagination was in flight.
      for (const thread of state.threads)
        if (!state.archivedThreads.has(thread.id) && threadTime(thread) > (baseline.get(thread.id) || 0) && threadTime(thread) > threadTime(threads.get(thread.id))) threads.set(thread.id, thread);
      state.threads = [...threads.values()];
      cursor = result.nextCursor || null;
      state.cursor = cursor;
      renderWorkspaces();
      if (cursor && seen.has(cursor)) throw new Error("会话分页游标重复，请刷新重试");
      if (cursor) seen.add(cursor);
    } while (cursor);
  } finally {
    if (active()) {
      state.threadListLoading = false;
      renderThreads();
    }
  }
}
function title(thread) {
  return thread?.name || thread?.preview?.split("\n")[0] || "新会话";
}
function threadTime(thread) {
  return Number(thread?.updatedAt || thread?.createdAt || 0);
}
function workspaceGroups() {
  const groups = new Map();
  for (const w of state.workspaces)
    groups.set(pathKey(w.path), { ...w, pinned: true, threads: [], updatedAt: 0 });
  for (const t of [...state.threads].sort((a, b) => threadTime(b) - threadTime(a) || Number(b.createdAt || 0) - Number(a.createdAt || 0) || b.id.localeCompare(a.id))) {
    const key = t.cwd ? pathKey(t.cwd) : "";
    if (!groups.has(key)) groups.set(key, { path: t.cwd || "", name: t.cwd ? folderName(t.cwd) : "未指定文件夹", threads: [], updatedAt: 0 });
    const group = groups.get(key);
    group.threads.push(t);
    group.updatedAt = Math.max(group.updatedAt, threadTime(t));
  }
  return [...groups.values()].sort((a, b) => b.updatedAt - a.updatedAt || a.path.localeCompare(b.path));
}
let threadMenuTarget = null;
let deleteThreadTarget = null;
function threadActionButton(id) {
  return [...$("workspaces").querySelectorAll(".thread-row")]
    .find((row) => row.dataset.threadId === id)?.querySelector(".thread-more");
}
function closeThreadMenu(focus = false) {
  const target = threadMenuTarget;
  threadMenuTarget = null;
  $("thread-menu").hidden = true;
  if (target) {
    const button = threadActionButton(target.id);
    button?.setAttribute("aria-expanded", "false");
    if (focus) button?.focus();
  }
}
function archiveThreadReason(thread) {
  if (!state.online || !state.loaded) return "设备连接后可以删除对话";
  if (state.loading || state.sending) return "请等待当前请求完成";
  if (!thread) return "对话已不在列表中";
  if (state.archivingThreads.has(thread.id)) return "正在删除此对话";
  if (thread.status?.type === "active" || (state.current?.id === thread.id && isRunning()))
    return "请先停止正在运行的对话";
  return "";
}
function openThreadMenu(thread, x, y, origin) {
  closeThreadMenu();
  closeModelMenu();
  threadMenuTarget = { id: thread.id };
  origin?.setAttribute("aria-expanded", "true");
  const item = $("delete-thread");
  const reason = archiveThreadReason(thread);
  item.disabled = !!reason;
  item.title = reason;
  const menu = $("thread-menu");
  menu.hidden = false;
  const rect = menu.getBoundingClientRect();
  menu.style.left = `${Math.max(8, Math.min(x, window.innerWidth - (rect.width || 168) - 8))}px`;
  menu.style.top = `${Math.max(8, Math.min(y, window.innerHeight - (rect.height || 48) - 8))}px`;
  (reason ? menu : item).focus();
}
function requestThreadDeletion(id) {
  const thread = state.threads.find((t) => t.id === id);
  const reason = archiveThreadReason(thread);
  closeThreadMenu();
  if (reason) return toast(reason);
  deleteThreadTarget = { id, generation: state.generation, device: state.selected, saving: false };
  $("delete-thread-name").textContent = title(thread);
  $("delete-thread-error").hidden = true;
  $("confirm-delete-thread").disabled = false;
  $("confirm-delete-thread").textContent = "删除对话";
  $("delete-thread-dialog").showModal();
}
function removeArchivedThread(id) {
  state.archivedThreads.add(id);
  if (threadMenuTarget?.id === id) closeThreadMenu();
  if (deleteThreadTarget?.id === id) $("delete-thread-dialog").close();
  // Invalidate list, resume and history requests that may still contain the archived conversation.
  state.threadListVersion++;
  state.threadListLoading = false;
  state.historyVersion++;
  state.olderTurnsRequest = null;
  state.openThreadRequest = null;
  state.threads = state.threads.filter((thread) => thread.id !== id);
  state.messageQueues.delete(id);
  for (const [key, request] of state.requests)
    if (request.params?.threadId === id) state.requests.delete(key);
  if (state.current?.id === id) newChat(false, true);
  renderThreads();
  renderApprovals();
}
async function archiveThread(id) {
  const thread = state.threads.find((t) => t.id === id) || (state.current?.id === id ? state.current : null);
  const reason = archiveThreadReason(thread);
  if (reason) throw new Error(reason);
  const generation = state.generation;
  const device = state.selected;
  const active = () => state.generation === generation && state.selected === device;
  state.archivingThreads.add(id);
  try {
    await rpc("thread/archive", { threadId: id });
    if (!active()) return false;
    removeArchivedThread(id);
    return true;
  } finally {
    if (active()) state.archivingThreads.delete(id);
  }
}
async function confirmThreadDeletion() {
  const target = deleteThreadTarget;
  if (!target || target.saving) return;
  if (target.generation !== state.generation || target.device !== state.selected) {
    $("delete-thread-dialog").close();
    return;
  }
  target.saving = true;
  $("confirm-delete-thread").disabled = true;
  $("confirm-delete-thread").textContent = "正在删除…";
  $("delete-thread-error").hidden = true;
  try {
    if (await archiveThread(target.id)) {
      if (deleteThreadTarget === target) $("delete-thread-dialog").close();
      toast("对话已删除，历史已保留在本地归档");
    }
  } catch (error) {
    if (deleteThreadTarget === target) {
      $("delete-thread-error").textContent = error.message;
      $("delete-thread-error").hidden = false;
    }
  } finally {
    target.saving = false;
    if (deleteThreadTarget === target) {
      $("confirm-delete-thread").disabled = false;
      $("confirm-delete-thread").textContent = "删除对话";
    }
  }
}
function renderThreads() {
  const root = $("workspaces");
  root.replaceChildren();
  const query = $("search").value.trim().toLowerCase();
  let count = 0;
  for (const w of workspaceGroups()) {
    const threads = w.threads.filter((t) => `${title(t)} ${t.cwd || ""}`.toLowerCase().includes(query));
    if (query && !threads.length) continue;
    count++;
    const key = w.path ? pathKey(w.path) : "";
    const expanded = !!query || !workspaceView().collapsed[key];
    const group = document.createElement("div");
    group.className = "workspace-group";
    group.dataset.path = w.path;
    const heading = document.createElement("div");
    heading.className = `workspace-row ${expanded ? "expanded" : ""}`;
    const toggle = document.createElement("button");
    toggle.className = "workspace-button";
    toggle.title = w.path || w.name;
    toggle.setAttribute("aria-expanded", String(expanded));
    toggle.innerHTML = `<span class="workspace-caret">${icon("chevron")}</span>${icon("folder")}<span>${escapeHTML(w.name)} (${w.threads.length})</span>`;
    toggle.onclick = () => {
      workspaceView().collapsed[key] = expanded;
      saveWorkspaceView();
      renderThreads();
    };
    heading.append(toggle);
    if (w.path) {
      const start = document.createElement("button");
      start.className = "icon-button workspace-action";
      start.title = `在 ${w.name} 新建会话`;
      start.setAttribute("aria-label", start.title);
      start.innerHTML = icon("plus");
      start.onclick = () => selectWorkspace(w.path).catch(showError);
      heading.append(start);
      if (w.pinned) {
        const remove = document.createElement("button");
        remove.className = "icon-button workspace-action";
        remove.title = `取消固定 ${w.name}（保留文件和会话）`;
        remove.setAttribute("aria-label", remove.title);
        remove.innerHTML = icon("close");
        remove.disabled = !state.online || !state.info?.workspaces;
        remove.onclick = () => removeWorkspace(w.path).catch(showError);
        heading.append(remove);
      }
    }
    group.append(heading);
    const history = document.createElement("div");
    history.className = "workspace-history thread-list";
    history.hidden = !expanded;
    for (const t of threads) {
      const row = document.createElement("div");
      row.dataset.threadId = t.id;
      row.className = `thread-row ${t.id === state.current?.id ? "active" : ""}`;
      const button = document.createElement("button");
      const running = t.status?.type === "active";
      if (running) {
        const spinner = document.createElement("span");
        spinner.className = "thread-spinner";
        spinner.setAttribute("aria-hidden", "true");
        button.append(spinner);
      }
      const name = document.createElement("span");
      name.className = "thread-name";
      name.textContent = title(t);
      button.append(name);
      if (running) {
        const status = document.createElement("span");
        status.className = "sr-only";
        status.textContent = "进行中";
        button.append(status);
      }
      button.title = `${title(t)}${t.cwd ? `\n${t.cwd}` : ""}`;
      button.onclick = () => openThread(t.id).catch(showError);
      row.append(button);
      const more = document.createElement("button");
      more.type = "button";
      more.className = "thread-more icon-button";
      more.innerHTML = icon("more");
      more.setAttribute("aria-label", `对话操作：${title(t)}`);
      more.setAttribute("aria-haspopup", "menu");
      more.setAttribute("aria-controls", "thread-menu");
      more.setAttribute("aria-expanded", String(threadMenuTarget?.id === t.id));
      more.onclick = () => {
        if (threadMenuTarget?.id === t.id) return closeThreadMenu(true);
        const rect = more.getBoundingClientRect();
        openThreadMenu(t, rect.left, rect.bottom + 4, more);
      };
      row.oncontextmenu = (event) => {
        event.preventDefault();
        openThreadMenu(t, event.clientX, event.clientY, more);
      };
      row.onkeydown = (event) => {
        if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return;
        event.preventDefault();
        const rect = row.getBoundingClientRect();
        openThreadMenu(t, rect.left + 20, rect.bottom, more);
      };
      row.append(more);
      history.append(row);
    }
    if (!threads.length) {
      const hint = document.createElement("p");
      hint.className = "empty-hint";
      hint.textContent = "还没有会话";
      history.append(hint);
    }
    group.append(history);
    root.append(group);
  }
  if (!count) {
    const p = document.createElement("p");
    p.className = "empty-hint";
    p.textContent = query
      ? "没有匹配的会话"
      : state.online
        ? "还没有会话，从一个新任务开始"
        : "连接设备后，会话会出现在这里";
    root.append(p);
  }
  $("history-label").textContent = state.threadListLoading ? "正在加载全部会话…" : "所有文件夹 · 最近更新优先";
  $("load-more").hidden = state.threadListLoading || !state.cursor;
}
function upsertThread(thread) {
  if (state.archivedThreads.has(thread.id)) return;
  const index = state.threads.findIndex((t) => t.id === thread.id);
  if (index < 0) state.threads.unshift(thread);
  else state.threads[index] = { ...state.threads[index], ...thread };
  renderThreads();
}
let titleEditVersion = 0;
let titleEditSaving = false;
function cancelTitleEdit() {
  titleEditVersion++;
  titleEditSaving = false;
  $("title-rename-form").hidden = true;
  $("title-rename-input").disabled = false;
  $("header-subtitle").hidden = false;
}
function renderThreadTitle() {
  const home = !state.current;
  const text = home ? "新会话" : title(state.current);
  $("header-title").hidden = !home;
  $("header-divider").hidden = !home;
  $("header-subtitle").textContent = text;
  $("header-subtitle").disabled = home;
  $("header-subtitle").title = home ? "" : "点击重命名会话";
  $("header-subtitle").setAttribute("aria-label", home ? "新会话" : `重命名会话：${text}`);
}
function setHome(home) {
  cancelTitleEdit();
  $("main").classList.toggle("home", home);
  $("welcome").hidden = !home;
  renderThreadTitle();
  $("thread-actions").hidden = home;
}
function beginTitleEdit() {
  if (!state.current) return;
  const input = $("title-rename-input");
  input.value = title(state.current);
  $("title-rename-form").hidden = false;
  $("header-subtitle").hidden = true;
  input.focus();
  input.select();
}
async function saveTitleEdit() {
  if (!state.current || titleEditSaving) return;
  const input = $("title-rename-input");
  const name = input.value.trim();
  if (!name) {
    toast("会话名称不能为空");
    input.focus();
    return;
  }
  if (name === title(state.current)) {
    cancelTitleEdit();
    return;
  }
  const threadId = state.current.id;
  const version = ++titleEditVersion;
  titleEditSaving = true;
  input.disabled = true;
  try {
    await renameThread(threadId, name);
    if (version === titleEditVersion) cancelTitleEdit();
  } catch (error) {
    if (version === titleEditVersion) {
      input.disabled = false;
      titleEditSaving = false;
      input.focus();
      showError(error);
    }
  }
}
function resetView() {
  closeThreadMenu();
  $("delete-thread-dialog").close();
  state.archivingThreads.clear();
  state.archivedThreads.clear();
  cancelThreadCreation();
  clearTimeout(state.snapshotTimer);
  state.historyVersion++;
  state.olderTurnsRequest = null;
  state.openThreadRequest = null;
  state.current = null;
  state.turns = [];
  state.messageQueues.clear();
  state.requests.clear();
  state.info = null;
  state.workspaces = [];
  state.cursor = null;
  state.threadListVersion++;
  state.threadListLoading = false;
  state.directoryVersion++;
  state.directory = null;
  $("workspace-dialog").close();
  state.threads = [];
  state.models = [];
  state.providers = [];
  state.modelsLoading = false;
  state.modelListVersion++;
  state.historyCursor = null;
  state.started = 0;
  state.sending = false;
  state.creating = null;
  state.loading = false;
  state.readOnly = false;
  state.detached = false;
  $("demo-badge").hidden = true;
  $("messages").replaceChildren();
  $("approval-area").replaceChildren();
  $("welcome-description").textContent =
    "连接你的电脑，让 Codex 继续手边的工作。";
  setHome(true);
  renderWorkspaces();
  renderThreads();
  renderApprovals();
  renderModels();
  restoreDraft();
  renderConnection();
}
function newChat(saveCurrent = true, force = false) {
  if (!force && (state.sending || state.loading)) {
    toast("请等待当前请求完成后新建会话");
    return;
  }
  if (saveCurrent) saveDraft();
  if (force) cancelThreadCreation();
  closeThreadMenu();
  closeModelMenu();
  state.current = null;
  clearTimeout(state.snapshotTimer);
  state.historyVersion++;
  state.olderTurnsRequest = null;
  state.openThreadRequest = null;
  state.turns = [];
  state.historyCursor = null;
  state.loading = false;
  state.readOnly = false;
  state.detached = false;
  state.snapshotOlder = false;
  state.started = 0;
  const preference = defaultModelPreference();
  renderModels(preference.model || "");
  renderEfforts(preference.effort || "");
  renderWorkspacePath();
  $("messages").replaceChildren();
  setHome(true);
  renderThreads();
  renderApprovals();
  restoreDraft();
  renderConnection();
  closeSidebar();
  $("prompt").focus();
}
async function openThread(id, silent = false) {
  closeThreadMenu();
  if (state.archivingThreads.has(id) || state.archivedThreads.has(id)) return;
  if (state.loading) return;
  if (!state.online) {
    toast("设备离线，恢复连接后可以读取会话");
    return;
  }
  saveDraft();
  cancelThreadCreation();
  clearTimeout(state.snapshotTimer);
  state.historyVersion++;
  state.olderTurnsRequest = null;
  const generation = state.generation;
  const request = {};
  state.openThreadRequest = request;
  const active = () => generation === state.generation && state.openThreadRequest === request && !state.archivedThreads.has(id);
  state.loading = true;
  renderConnection();
  try {
    let result;
    let readOnly = false;
    try {
      result = await rpc("thread/resume", { threadId: id });
    } catch (error) {
      if (/active writer/i.test(error.message)) {
        result = state.info?.threadSnapshots
          ? await rpc("codexrm/thread/snapshot", { threadId: id })
          : await rpc("thread/read", { threadId: id, includeTurns: true });
        readOnly = true;
      } else if (/paginat|excludeTurns|history mode/i.test(error.message)) {
        result = await rpc("thread/resume", {
          threadId: id,
          excludeTurns: true,
        });
      } else throw error;
    }
    if (!active()) return;
    let thread = result.thread;
    let cursor = result.nextCursor || null;
    if (thread.historyMode === "paginated" && !(readOnly && state.info?.threadSnapshots)) {
      const page = await rpc("thread/turns/list", {
        threadId: id,
        limit: 30,
        sortDirection: "desc",
        itemsView: "full",
      });
      if (!active()) return;
      thread.turns = (page.data || []).reverse();
      cursor = page.nextCursor;
    }
    state.current = thread;
    if (result.model) state.current.model = result.model;
    if (thread.cwd) {
      workspaceView().selected = thread.cwd;
      saveWorkspaceView();
    }
    state.readOnly = readOnly;
    state.detached = false;
    state.snapshotOlder = false;
    state.turns = thread.turns || [];
    state.historyCursor = cursor;
    state.started = isRunning() ? Date.now() : 0;
    renderWorkspacePath();
    selectThreadModel(result);
    setHome(false);
    upsertThread(thread);
    renderMessages();
    renderApprovals();
    restoreDraft();
    renderConnection();
    if (state.info?.messageQueue) {
      await loadMessageQueue(id).catch((e) => toast(`排队列表同步失败：${e.message}`));
      if (!active()) return;
    }
    scheduleSnapshot();
    if (!silent) closeSidebar();
    scrollBottom();
  } finally {
    if (generation === state.generation && state.openThreadRequest === request) {
      state.openThreadRequest = null;
      state.loading = false;
      renderConnection();
      scheduleSnapshot();
    }
  }
}
function scheduleSnapshot() {
  clearTimeout(state.snapshotTimer);
  if (!(state.readOnly || state.detached) || !state.current || !state.online || state.loading || !state.info?.threadSnapshots) return;
  const generation = state.generation;
  const id = state.current.id;
  state.snapshotTimer = setTimeout(async () => {
    try {
      const result = await rpc("codexrm/thread/snapshot", { threadId: id });
      if (generation !== state.generation || state.current?.id !== id || !(state.readOnly || state.detached) || state.loading) return;
      if (state.detached && result.active) {
        state.detached = false;
        state.readOnly = true;
        renderConnection();
      }
      const incoming = result.thread.turns || [];
      const boundary = state.turns.findIndex((t) => t.id === incoming[0]?.id);
      state.turns = state.snapshotOlder && boundary >= 0
        ? [...state.turns.slice(0, boundary), ...incoming]
        : incoming;
      if (!state.snapshotOlder) state.historyCursor = result.nextCursor || null;
      state.current = { ...state.current, ...result.thread };
      upsertThread(state.current);
      scheduleRender();
    } catch (error) {
      if (generation === state.generation && state.online && (state.readOnly || state.detached)) toast(`会话同步失败：${error.message}`);
    } finally {
      if (generation === state.generation && state.current?.id === id) scheduleSnapshot();
    }
  }, 3000);
}
function displayedTurnStatus(turn) {
  if (state.readOnly && turn.status === "interrupted")
    return state.current?.codexrmTurnStates?.[turn.id] || "unknown";
  return turn.status;
}
async function olderTurns() {
  if (!state.current || !state.historyCursor || state.olderTurnsRequest || state.loading) return;
  const generation = state.generation;
  const version = state.historyVersion;
  const id = state.current.id;
  const cursor = state.historyCursor;
  const request = {};
  state.olderTurnsRequest = request;
  const before = $("scroll-area").scrollHeight;
  try {
    const page = await rpc("thread/turns/list", {
      threadId: id,
      cursor,
      limit: 30,
      sortDirection: "desc",
      itemsView: "full",
    });
    if (generation !== state.generation || version !== state.historyVersion || state.olderTurnsRequest !== request ||
        state.current?.id !== id || state.historyCursor !== cursor || state.archivedThreads.has(id)) return;
    const existing = new Set(state.turns.map((turn) => turn.id));
    const added = new Set();
    const older = [];
    for (const turn of (page.data || []).reverse()) {
      if (existing.has(turn.id) || added.has(turn.id)) continue;
      added.add(turn.id);
      older.push(turn);
    }
    state.turns = [...older, ...state.turns];
    state.snapshotOlder = state.readOnly || state.detached;
    state.historyCursor = page.nextCursor || null;
    renderMessages();
    $("scroll-area").scrollTop = $("scroll-area").scrollHeight - before;
  } finally {
    if (state.olderTurnsRequest === request) state.olderTurnsRequest = null;
  }
}
function modelKey(provider, model) {
  return JSON.stringify([provider || "", model]);
}
function selectedModel() {
  return state.models.find((m) => m.key === $("model").value);
}
function workspaceModelPreference() {
  const view = workspaceView();
  view.models ||= {};
  return view.models[pathKey(selectedCwd())] || {};
}
function defaultModelPreference() {
  const available = state.models.filter((m) => !state.providers.find((p) => p.id === m.providerId)?.unavailable);
  for (const preference of [workspaceView().lastModel, workspaceModelPreference()]) {
    if (preference?.model && available.some((m) => m.key === preference.model)) return preference;
  }
  const model = available.find((m) => !m.providerId && m.isDefault) ||
    available.find((m) => m.isDefault) || available[0];
  return { model: model?.key || "", effort: model?.defaultReasoningEffort || "" };
}
function saveModelPreference() {
  if (!selectedModel()) return;
  const view = workspaceView();
  view.models ||= {};
  view.lastModel = { model: $("model").value, effort: $("effort").value };
  view.models[pathKey(selectedCwd())] = view.lastModel;
  saveWorkspaceView();
}
async function loadModels(refresh = false) {
  const generation = state.generation;
  const version = ++state.modelListVersion;
  state.modelsLoading = true;
  renderConnection();
  try {
    const result = state.info?.providerModels
      ? await rpc("codexrm/model/list")
      : { providers: [{ id: "", name: "当前 Codex 配置", models: (await rpc("model/list", { limit: 100 })).data || [] }] };
    if (generation !== state.generation || version !== state.modelListVersion) return;
    const previous = $("model").value;
    const previousEffort = $("effort").value;
    state.providers = result.providers || [];
    state.models = state.providers.flatMap((p) => (p.models || []).filter((m) => !m.hidden).map((m) => ({
      ...m, model: m.model || m.id, providerId: p.id, providerName: p.name,
      key: modelKey(p.id, m.model || m.id),
    })));
    if (state.current) {
      renderModels(previous);
      selectThreadModel({ thread: state.current }, previous, previousEffort);
    } else {
      const preference = defaultModelPreference();
      const keepSelection = state.models.some((m) => m.key === previous &&
        !state.providers.find((p) => p.id === m.providerId)?.unavailable);
      renderModels(keepSelection ? previous : preference.model);
      renderEfforts(keepSelection ? previousEffort : preference.effort);
    }
    const warnings = [...(result.warnings || []), ...state.providers.filter((p) => p.warning).map((p) => `${p.name}：${p.warning}`)];
    $("refresh-models").title = warnings.length ? warnings.join("\n") : "刷新所有供应商的模型";
    if (refresh) toast(`已加载 ${state.providers.length} 组供应商、${state.models.length} 个模型${warnings.length ? `；${warnings.length} 组列表使用缓存或暂不可用` : ""}`);
    else if (result.warnings?.length) toast(result.warnings.join("；"));
  } finally {
    if (generation === state.generation && version === state.modelListVersion) {
      state.modelsLoading = false;
      renderConnection();
    }
  }
}
function renderModels(preferred = $("model").value) {
  $("model").value = "";
  $("model").replaceChildren(new Option("默认模型 · 当前 Codex 配置", ""));
  for (const p of state.providers) {
    const group = document.createElement("optgroup");
    const models = state.models.filter((m) => m.providerId === p.id);
    group.label = `${p.name} (${models.length})`;
    group.title = p.warning || p.source || "";
    for (const m of models) {
      const option = new Option(`${p.name} · ${m.displayName || m.model}`, m.key);
      option.disabled = !!p.unavailable;
      group.append(option);
    }
    if (!models.length) {
      const empty = new Option(p.warning || "暂无可用模型", `unavailable:${p.id}`);
      empty.disabled = true;
      group.append(empty);
    }
    $("model").append(group);
  }
  if ([...$("model").options].some((o) => o.value === preferred)) $("model").value = preferred;
  renderEfforts();
}
function threadProviderId(thread = state.current) {
  const runtimeId = thread?.modelProvider;
  return state.providers.find((p) => p.runtimeId && p.runtimeId === runtimeId)?.id ||
    (runtimeId?.startsWith("ccswitch_") ? runtimeId : "");
}
function selectThreadModel(result, preferred = "", effort = result.reasoningEffort || "") {
  const providerId = threadProviderId(result.thread);
  const model = result.model || result.thread?.model;
  if (providerId && !state.providers.some((p) => p.id === providerId)) {
    state.providers.push({
      id: providerId, runtimeId: result.thread.modelProvider,
      name: "当前会话供应商（目录不可用）", unavailable: true,
      warning: "未在 CCSwitch 中找到此供应商；选择其他供应商将创建新会话", models: [],
    });
  }
  if (model && !state.models.some((m) => m.key === modelKey(providerId, model))) {
    const provider = state.providers.find((p) => p.id === providerId);
    if (provider) {
      state.models.push({ model, displayName: model, providerId, providerName: provider.name, key: modelKey(providerId, model), supportedReasoningEfforts: [] });
      renderModels();
    }
  }
  const match = state.models.find((m) => m.providerId === providerId && m.key === preferred) ||
    state.models.find((m) => m.providerId === providerId && m.model === model);
  $("model").value = match?.key || "";
  renderEfforts(effort);
}
function renderEfforts(previous = $("effort").value) {
  $("effort").value = "";
  $("effort").replaceChildren(new Option("默认思考", ""));
  const model =
    selectedModel() || state.models.find((m) => !m.providerId && m.isDefault);
  const names = {
    none: "不启用",
    minimal: "极简",
    low: "较少",
    medium: "适中",
    high: "深入",
    xhigh: "更深入",
    max: "最大",
    ultra: "Ultra",
  };
  for (const r of model?.supportedReasoningEfforts || []) {
    const value = r.reasoningEffort;
    $("effort").append(new Option(names[value] || value, value));
  }
  if ([...$("effort").options].some((o) => o.value === previous))
    $("effort").value = previous;
  renderModelControl();
  if (!$("model-menu").hidden) renderModelChoices();
}
function renderModelControl() {
  const model = selectedModel();
  const effort = [...$("effort").options].find((option) => option.value === $("effort").value)?.textContent || "默认思考";
  $("model-label").textContent = model?.displayName || model?.model || (state.modelsLoading ? "加载模型…" : "当前 Codex 配置");
  $("model-effort-label").textContent = $("effort").value ? effort : "";
  $("model-trigger").title = model ? `${model.providerName} · ${model.displayName || model.model} · ${effort}` : "选择供应商与模型";
  $("model-trigger").disabled = $("model").disabled;
  $("model-menu-current").textContent = model ? `${model.providerName} · ${model.displayName || model.model}` : "当前 Codex 配置";
  $("model-menu-effort").textContent = effort;
  $("choose-effort").disabled = $("effort").options.length <= 1;
  $("permission-label").textContent = { "danger-full-access": "完整访问", "workspace-write": "工作区访问", "read-only": "只读" }[state.settings.sandbox || "danger-full-access"];
  if ($("model-trigger").disabled && !$("model-menu").hidden) closeModelMenu();
}
function closeModelMenu(focus = false) {
  const menu = $("model-menu");
  if (menu.hidden) return;
  menu.hidePopover?.();
  menu.hidden = true;
  $("model-trigger").setAttribute("aria-expanded", "false");
  if (focus) $("model-trigger").focus();
}
function modelMenuPane(pane = "root") {
  $("model-menu").dataset.pane = pane;
  $("model-menu-root").hidden = pane !== "root";
  $("model-menu-models").hidden = pane !== "models";
  $("effort-choices").hidden = pane !== "effort";
  $("model-menu-back").hidden = pane === "root";
  $("model-menu-title").textContent = { root: "模型设置", models: "选择模型", effort: "思考强度" }[pane];
  renderModelChoices();
  if (pane === "models") $("model-search").focus();
  else if (pane === "root") $("choose-model").focus();
  else $("effort-choices").querySelector("[aria-checked='true']")?.focus();
}
function renderModelChoices() {
  const root = $("model-choices");
  root.replaceChildren();
  const query = $("model-search").value.trim().toLocaleLowerCase();
  for (const provider of state.providers) {
    const models = state.models.filter((m) => m.providerId === provider.id &&
      `${provider.name} ${m.displayName || ""} ${m.model}`.toLocaleLowerCase().includes(query));
    if (!models.length && (query || !provider.warning)) continue;
    const group = document.createElement("div");
    group.className = "model-group";
    const label = document.createElement("div");
    label.className = "model-group-label";
    label.textContent = provider.name;
    group.append(label);
    if (provider.warning) {
      const warning = document.createElement("p");
      warning.className = "model-warning";
      warning.textContent = provider.warning;
      group.append(warning);
    }
    for (const model of models) {
      const option = document.createElement("button");
      option.type = "button";
      option.className = "model-choice";
      option.setAttribute("role", "radio");
      option.setAttribute("aria-checked", String(model.key === $("model").value));
      option.disabled = !!provider.unavailable;
      option.title = `${provider.name} · ${model.model}`;
      const label = document.createElement("span");
      label.textContent = model.displayName || model.model;
      const check = document.createElement("span");
      check.className = "model-check";
      check.textContent = model.key === $("model").value ? "✓" : "";
      option.append(label, check);
      option.onclick = () => {
        $("model").value = model.key;
        $("model").onchange();
        closeModelMenu(true);
      };
      group.append(option);
    }
    root.append(group);
  }
  if (!root.children.length) {
    const empty = document.createElement("p");
    empty.className = "model-empty";
    empty.textContent = state.modelsLoading ? "正在加载模型…" : query ? "没有匹配的模型" : "暂无可用模型，请刷新列表";
    root.append(empty);
  }
  const efforts = $("effort-choices");
  efforts.replaceChildren();
  for (const effort of $("effort").options) {
    const option = document.createElement("button");
    option.type = "button";
    option.className = "model-choice";
    option.setAttribute("role", "radio");
    option.setAttribute("aria-checked", String(effort.value === $("effort").value));
    option.textContent = `${effort.textContent}${effort.value === $("effort").value ? "　✓" : ""}`;
    option.onclick = () => {
      $("effort").value = effort.value;
      $("effort").onchange();
      renderModelControl();
      closeModelMenu(true);
    };
    efforts.append(option);
  }
}
function positionModelMenu() {
  const menu = $("model-menu");
  if (menu.hidden) return;
  const bounds = $("model-trigger").getBoundingClientRect();
  const viewport = window.visualViewport;
  const left = viewport?.offsetLeft || 0;
  const top = viewport?.offsetTop || 0;
  const width = viewport?.width || window.innerWidth;
  const height = viewport?.height || window.innerHeight;
  const menuWidth = Math.min(360, width - 24);
  menu.style.width = `${menuWidth}px`;
  menu.style.left = `${Math.max(left + 12, Math.min(bounds.left, left + width - menuWidth - 12))}px`;
  menu.style.top = "auto";
  const bottom = Math.min(Math.max(bounds.top - 8, top + 132), top + height - 12);
  menu.style.bottom = `${window.innerHeight - bottom}px`;
  menu.style.maxHeight = `${Math.max(40, bottom - top - 12)}px`;
}
function openModelMenu() {
  const menu = $("model-menu");
  if (!menu.hidden) return closeModelMenu(true);
  if ($("model-trigger").disabled) return;
  $("model-search").value = "";
  menu.hidden = false;
  menu.showPopover?.();
  positionModelMenu();
  $("model-trigger").setAttribute("aria-expanded", "true");
  modelMenuPane();
}
function isRunning() {
  if (state.readOnly) return false;
  return state.turns.some((t) => t.status === "inProgress");
}
function turnFor(id) {
  let t = state.turns.find((t) => t.id === id);
  if (!t) {
    t = { id, status: "inProgress", items: [] };
    state.turns.push(t);
  }
  return t;
}
function itemFor(turn, id, type) {
  let item = turn.items.find((i) => i.id === id);
  if (!item) {
    item = { id, type, text: "" };
    turn.items.push(item);
  }
  return item;
}
function upsertItem(turn, item) {
  turn.items ||= [];
  const index = turn.items.findIndex((i) => i.id === item.id);
  if (item.type === "userMessage" && index < 0) {
    const text = (i) => (i.content || []).filter((c) => c.type === "text").map((c) => c.text).join("\n");
    const optimistic = turn.items.findIndex((i) => String(i.id).startsWith("optimistic-user-") && text(i) === text(item));
    if (optimistic >= 0) {
      turn.items[optimistic] = item;
      return;
    }
  }
  if (index < 0) turn.items.push(item);
  else turn.items[index] = { ...turn.items[index], ...item };
}
function onEvent(msg) {
  const p = msg.params || {};
  const method = msg.method;
  if (msg.id !== undefined) {
    if (!state.requests.has(msg.id)) {
      state.requests.set(msg.id, msg);
      renderApprovals();
    }
    return;
  }
  if (method === "codexrm/queue/updated") {
    acceptMessageQueue(p);
    return;
  }
  if (
    method === "codexrm/request/resolved" ||
    method === "serverRequest/resolved"
  ) {
    state.requests.delete(p.id ?? p.requestId);
    state.responding.delete(p.id ?? p.requestId);
    renderApprovals();
    return;
  }
  if (method === "codexrm/request/failed") {
    state.responding.delete(p.id);
    toast(`操作未发送：${p.message || "请重试"}`);
    renderApprovals();
    return;
  }
  if (method === "thread/started") {
    if (p.thread) upsertThread(p.thread);
    return;
  }
  if (method === "thread/name/updated") {
    const t = state.threads.find((t) => t.id === p.threadId);
    if (t) t.name = p.threadName ?? p.name;
    if (state.current?.id === p.threadId) {
      state.current.name = p.threadName ?? p.name;
      renderThreadTitle();
    }
    renderThreads();
    return;
  }
  if (method === "thread/archived") {
    removeArchivedThread(p.threadId);
    return;
  }
  if (method === "codexrm/thread/detached" || method === "codexrm/thread/loaded") {
    if (state.current?.id !== p.threadId) return;
    clearTimeout(state.snapshotTimer);
    if (method === "codexrm/thread/detached") {
      state.detached = true;
      state.readOnly = false;
    } else {
      state.detached = false;
      state.readOnly = false;
      state.snapshotOlder = false;
    }
    renderConnection();
    scheduleRender();
    scheduleSnapshot();
    return;
  }
  const threadID = p.threadId || p.turn?.threadId;
  if (method === "turn/started" || method === "turn/completed") {
    const t = state.threads.find((t) => t.id === threadID);
    if (t) {
      t.status = { type: method === "turn/started" ? "active" : "idle" };
      t.updatedAt = Math.floor(Date.now() / 1000);
    }
    renderThreads();
  }
  if (method === "turn/completed") {
    for (const [id, req] of state.requests)
      if (req.params?.threadId === threadID && req.params?.turnId === p.turn.id &&
          !(req.method === "item/tool/requestUserInput" && req.params.isBlocking === false && p.turn.status === "completed"))
        state.requests.delete(id);
    renderApprovals();
  }
  if (threadID !== state.current?.id) return;
  if (method === "turn/started") {
    const existing = state.turns.find((t) => t.id === p.turn.id);
    if (existing)
      Object.assign(existing, p.turn, {
        items: p.turn.items?.length ? p.turn.items : existing.items,
      });
    else state.turns.push({ ...p.turn, items: p.turn.items || [] });
    state.started = Date.now();
  } else if (method === "turn/completed") {
    const turn = turnFor(p.turn.id);
    turn.status = p.turn.status;
    for (const item of p.turn.items || []) upsertItem(turn, item);
    if (p.turn.error) turn.error = p.turn.error;
  } else if (method === "item/started" || method === "item/completed") {
    if (p.item) {
      const t = turnFor(p.turnId);
      upsertItem(t, p.item);
    }
  } else if (method === "item/agentMessage/delta") {
    const item = itemFor(turnFor(p.turnId), p.itemId, "agentMessage");
    item.text = (item.text || "") + (p.delta || "");
    dirtyMessageItems.add(item.id);
  } else if (method === "item/commandExecution/outputDelta") {
    const item = itemFor(turnFor(p.turnId), p.itemId, "commandExecution");
    item.aggregatedOutput = (item.aggregatedOutput || "") + (p.delta || "");
    dirtyMessageItems.add(item.id);
  } else if (method.startsWith("item/reasoning/") && method.endsWith("Delta")) {
    const item = itemFor(turnFor(p.turnId), p.itemId, "reasoning");
    item.reasoningText = (item.reasoningText || "") + (p.delta || "");
    dirtyMessageItems.add(item.id);
  } else if (method === "turn/plan/updated") {
    const item = itemFor(turnFor(p.turnId), "plan-" + p.turnId, "plan");
    item.text = p.explanation || "";
    item.plan = p.plan || [];
    dirtyMessageItems.add(item.id);
  } else if (method === "turn/diff/updated") {
    const item = itemFor(turnFor(p.turnId), "diff-" + p.turnId, "diff");
    item.diff = p.diff || "";
    dirtyMessageItems.add(item.id);
  } else if (method === "error") {
    const t = turnFor(p.turnId || "error");
    t.error = p.error || { message: "Codex 发生错误" };
    if (!p.willRetry) t.status = "failed";
  }
  scheduleRender();
  if (["turn/started", "turn/completed", "error"].includes(method))
    renderConnection();
}
let renderScheduled = false;
function scheduleRender() {
  if (renderScheduled) return;
  renderScheduled = true;
  requestAnimationFrame(() => {
    renderScheduled = false;
    const area = $("scroll-area");
    const nearBottom =
      area.scrollHeight - area.scrollTop - area.clientHeight < 150;
    renderMessages();
    if (nearBottom) scrollBottom();
  });
}
function scrollBottom() {
  requestAnimationFrame(() => {
    $("scroll-area").scrollTop = $("scroll-area").scrollHeight;
  });
}

// A deliberately small renderer: escape first, allow only known Markdown tags
// and http(s) links. No upstream HTML is inserted into the document.
function inline(text) {
  const stash = [];
  let s = escapeHTML(text);
  s = s.replace(/`([^`]+)`/g, (_, v) => {
    stash.push(`<code>${v}</code>`);
    return `\u0000${stash.length - 1}\u0000`;
  });
  s = s.replace(
    /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    (_, label, url) =>
      `<a href="${url}" target="_blank" rel="noopener noreferrer">${label}</a>`,
  );
  s = s
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(?<!\*)\*([^*\n]+)\*(?!\*)/g, "<em>$1</em>");
  return s.replace(/\u0000(\d+)\u0000/g, (_, n) => stash[Number(n)]);
}
function markdown(text = "") {
  let html = "";
  // Fence parsing also handles a partially streamed, unclosed code block.
  const lines = String(text).split("\n");
  let code = false,
    buffer = [],
    paragraph = [],
    list = null;
  const flush = () => {
    if (paragraph.length) {
      html += `<p>${paragraph.map(inline).join("<br>")}</p>`;
      paragraph = [];
    }
  };
  const closeList = () => {
    if (list) {
      html += `</${list}>`;
      list = null;
    }
  };
  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      flush();
      closeList();
      if (code) {
        html += `<pre><code>${escapeHTML(buffer.join("\n"))}</code></pre>`;
        buffer = [];
      }
      code = !code;
      continue;
    }
    if (code) {
      buffer.push(line);
      continue;
    }
    if (!line.trim()) {
      flush();
      closeList();
      continue;
    }
    const heading = line.match(/^(#{1,3})\s+(.+)/);
    if (heading) {
      flush();
      closeList();
      html += `<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>`;
      continue;
    }
    const li = line.match(/^\s*(?:([-*])|\d+[.)])\s+(.*)/);
    if (li) {
      flush();
      const tag = li[1] ? "ul" : "ol";
      if (list !== tag) {
        closeList();
        html += `<${tag}>`;
        list = tag;
      }
      html += `<li>${inline(li[2])}</li>`;
      continue;
    }
    if (line.startsWith("> ")) {
      flush();
      closeList();
      html += `<blockquote>${inline(line.slice(2))}</blockquote>`;
      continue;
    }
    closeList();
    paragraph.push(line);
  }
  flush();
  closeList();
  if (code) html += `<pre><code>${escapeHTML(buffer.join("\n"))}</code></pre>`;
  return html;
}
function itemHTML(item) {
  const type = item.type;
  if (type === "userMessage") {
    const content = userContent(item.content);
    return `<div class="message user"><div class="user-bubble">${content.text ? `<div>${escapeHTML(content.text)}</div>` : ""}${content.files.length ? `<div class="message-attachments">${content.files.map(attachmentHTML).join("")}</div>` : ""}</div></div>`;
  }
  if (type === "agentMessage")
    return `<article class="message assistant"><div class="message-label"><span class="mini-mark">${icon("terminal")}</span>Codex</div><div class="markdown">${markdown(item.text || "")}</div><div class="message-tools"><button data-copy="${escapeHTML(item.id)}" aria-label="复制回复">复制回复</button></div></article>`;
  if (type === "reasoning") {
    const text =
      item.reasoningText ||
      [...(item.summary || []), ...(item.content || [])]
        .map((x) => (typeof x === "string" ? x : x.text || ""))
        .join("\n");
    return text
      ? `<details class="reasoning" data-detail="${escapeHTML(item.id)}"><summary>思考过程</summary><div class="markdown">${markdown(text)}</div></details>`
      : "";
  }
  if (type === "commandExecution")
    return toolHTML(
      item.id,
      item.command || "运行命令",
      item.aggregatedOutput || "",
      item.status === "completed"
        ? `退出码 ${item.exitCode ?? 0}`
        : item.status === "declined"
          ? "已拒绝"
          : "执行中",
    );
  if (type === "fileChange")
    return toolHTML(
      item.id,
      `文件变更 · ${(item.changes || []).length} 个文件`,
      (item.changes || [])
        .map((c) => `${c.path}\n${c.diff || ""}`)
        .join("\n\n"),
      item.status || "",
    );
  if (type === "diff")
    return item.diff
      ? toolHTML(item.id, "查看本轮变更", item.diff, "Diff")
      : "";
  if (type === "plan")
    return toolHTML(
      item.id,
      "执行计划",
      item.plan
        ?.map(
          (p) =>
            `${p.status === "completed" ? "✓" : p.status === "inProgress" ? "→" : "○"} ${p.step}`,
        )
        .join("\n") ||
        item.text ||
        "",
      "",
    );
  if (type === "mcpToolCall")
    return toolHTML(
      item.id,
      `${item.server || "MCP"} / ${item.tool || "工具"}`,
      JSON.stringify(item.result || item.arguments || {}, null, 2),
      item.status || "",
    );
  if (type === "webSearch")
    return toolHTML(
      item.id,
      `搜索 · ${item.query || ""}`,
      JSON.stringify(item.action || {}, null, 2),
      item.status || "",
    );
  if (type === "contextCompaction")
    return '<div class="turn-status">上下文已压缩</div>';
  if (type === "planMessage")
    return `<div class="message markdown">${markdown(item.text || "")}</div>`;
  return toolHTML(
    item.id,
    type || "执行事件",
    JSON.stringify(item, null, 2),
    "",
  );
}
function toolHTML(id, title, text, status) {
  return `<details class="tool-card" data-detail="${escapeHTML(id)}"><summary>${escapeHTML(title)}<span>${escapeHTML(status)}</span></summary><pre class="tool-output">${escapeHTML(text)}</pre></details>`;
}
const dirtyMessageItems = new Set();
const messageRender = { layout: "", items: new Map() };
function renderMessages() {
  const root = $("messages");
  const items = state.turns.flatMap((turn) => turn.items || []);
  const layout = JSON.stringify([
    !!state.historyCursor,
    state.readOnly,
    state.detached,
    state.turns.map((turn) => [
      (turn.items || []).map((item) => item.id),
      turn.error || null,
      displayedTurnStatus(turn),
    ]),
  ]);
  if (messageRender.layout === layout && root.childElementCount) {
    let incremental = true;
    for (const item of items) {
      const cached = messageRender.items.get(item.id);
      if (cached?.source === item && !dirtyMessageItems.has(item.id)) continue;
      if (!cached?.node?.isConnected) {
        incremental = false;
        break;
      }
      const details = cached.node.querySelector("details");
      const wasOpen = details?.open;
      cached.node.innerHTML = itemHTML(item);
      if (wasOpen) cached.node.querySelector("details").open = true;
      cached.source = item;
    }
    if (incremental) {
      dirtyMessageItems.clear();
      hydrateAttachmentImages();
      return;
    }
  }

  const open = new Set(
    [...root.querySelectorAll("details[open]")].map((n) => n.dataset.detail),
  );
  let html = state.historyCursor
    ? '<button class="text-button" id="older-turns">加载更早的消息</button>'
    : "";
  if (state.readOnly)
    html +=
      '<div class="turn-status">此会话由桌面客户端管理，自动同步已保存的消息。结束原会话后可重新打开继续。</div>';
  else if (state.detached)
    html += '<div class="turn-status">会话已分离，正在自动同步最新状态。</div>';
  const rendered = new Map();
  for (const turn of state.turns) {
    for (const item of turn.items || []) {
      html += `<div class="message-item" data-item-id="${escapeHTML(item.id)}">${itemHTML(item)}</div>`;
      rendered.set(item.id, { source: item, node: null });
    }
    if (turn.error)
      html += `<div class="error-message">${escapeHTML(turn.error.message || JSON.stringify(turn.error))}</div>`;
    const status = displayedTurnStatus(turn);
    if (status === "interrupted")
      html += '<div class="turn-status">任务已停止</div>';
    else if (state.readOnly && status === "inProgress")
      html += '<div class="turn-status">原客户端尚未完成此轮 · 自动同步中</div>';
    else if (state.readOnly && status === "unknown")
      html += '<div class="turn-status">等待原客户端的状态 · 自动同步中</div>';
  }
  root.innerHTML = html;
  root.querySelectorAll("[data-item-id]").forEach((node) => {
    const cached = rendered.get(node.dataset.itemId);
    if (cached) cached.node = node;
  });
  root.querySelectorAll("details").forEach((n) => {
    if (open.has(n.dataset.detail)) n.open = true;
  });
  messageRender.layout = layout;
  messageRender.items = rendered;
  dirtyMessageItems.clear();
  hydrateAttachmentImages();
}
$("messages").addEventListener("click", async (event) => {
  const attachmentButton = event.target.closest("[data-attachment]");
  if (attachmentButton) {
    if (!state.online) return toast("请连接设备后再下载附件");
    attachmentButton.disabled = true;
    try {
      const file = await readAttachment(attachmentButton.dataset.attachment);
      const link = document.createElement("a");
      link.href = file.url;
      link.download = file.file.name;
      link.click();
    } catch (error) { showError(error); }
    finally { attachmentButton.disabled = false; }
    return;
  }
  if (event.target.closest("#older-turns")) {
    olderTurns().catch(showError);
    return;
  }
  const button = event.target.closest("[data-copy]");
  if (!button) return;
  const item = state.turns
    .flatMap((turn) => turn.items || [])
    .find((entry) => entry.id === button.dataset.copy);
  try {
    await navigator.clipboard.writeText(item?.text || "");
    toast("回复已复制");
  } catch {
    toast("当前浏览器无法直接复制，请选择文本复制");
  }
});
function isPermissionRequest(msg) {
  return ["item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval"].includes(msg.method);
}
function permissionResult(msg, decision) {
  if (msg.method === "item/permissions/requestApproval")
    return { permissions: decision === "decline" ? {} : msg.params.permissions || {}, scope: decision === "acceptForSession" ? "session" : "turn" };
  return { decision };
}
function answerRequest(id, result, error) {
  if (!state.requests.has(id) || state.responding.has(id)) return false;
  if (!sendResponse(id, result, error)) return false;
  state.responding.add(id);
  renderApprovals();
  return true;
}
function renderUserInput(card, id, params) {
  const note = document.createElement("p");
  note.textContent = params.isBlocking === false
    ? "你可以继续操作，准备好后再提交回答。"
    : "提交回答后，Codex 会继续处理任务。";
  card.append(note);
  const form = document.createElement("form");
  form.className = "question-form";
  const fields = params.questions.map((question, index) => {
    const group = document.createElement("fieldset");
    group.className = "question-group";
    const legend = document.createElement("legend");
    legend.textContent = question.header || `问题 ${index + 1}`;
    const prompt = document.createElement("p");
    prompt.className = "question-prompt";
    prompt.textContent = question.question;
    group.append(legend, prompt);
    const custom = document.createElement(question.isSecret ? "input" : "textarea");
    if (question.isSecret) {
      custom.type = "password";
      custom.autocomplete = "off";
    } else custom.rows = 2;
    const options = (question.options || []).map((option, optionIndex) => {
      const label = document.createElement("label");
      label.className = "question-option";
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = `question-${id}-${index}`;
      radio.value = option.label;
      radio.setAttribute("aria-label", option.description ? `${option.label}：${option.description}` : option.label);
      radio.checked = optionIndex === 0;
      radio.onchange = () => { custom.value = ""; };
      const text = document.createElement("span");
      const title = document.createElement("strong");
      title.textContent = option.label;
      text.append(title);
      if (option.description) {
        const description = document.createElement("span");
        description.textContent = option.description;
        text.append(description);
      }
      label.append(radio, text);
      group.append(label);
      return radio;
    });
    custom.placeholder = options.length ? "也可以输入自己的回答" : "请输入回答";
    custom.setAttribute("aria-label", `${question.header || question.question || question.id}：${options.length ? "自定义回答" : "回答"}`);
    custom.oninput = () => {
      for (let i = 0; i < options.length; i++)
        options[i].checked = !custom.value.trim() && i === 0;
    };
    group.append(custom);
    form.append(group);
    return { question, options, custom };
  });
  const actions = document.createElement("div");
  actions.className = "approval-buttons";
  const submit = document.createElement("button");
  submit.type = "submit";
  submit.className = "accept";
  submit.textContent = "提交回答";
  form.onsubmit = (event) => {
    event.preventDefault();
    const answers = Object.create(null);
    for (const { question, options, custom } of fields) {
      const answer = custom.value.trim() ? custom.value : options.find((option) => option.checked)?.value;
      if (!answer) {
        toast(`请回答「${question.header || question.question || question.id}」后再提交`);
        custom.focus();
        return;
      }
      answers[question.id] = { answers: [answer] };
    }
    answerRequest(id, { answers });
  };
  const skip = document.createElement("button");
  skip.type = "button";
  skip.textContent = "跳过";
  skip.onclick = () => answerRequest(id, { answers: {} });
  actions.append(submit, skip);
  form.append(actions);
  card.append(form);
}
function renderApprovals() {
  const root = $("approval-area");
  const scrollTop = root.scrollTop;
  const active = root.contains(document.activeElement) ? document.activeElement : null;
  root.replaceChildren();
  for (const id of state.approvalCards.keys())
    if (!state.requests.has(id)) {
      state.approvalCards.delete(id);
      state.responding.delete(id);
    }
  const permissions = [...state.requests].filter(([id, msg]) =>
    isPermissionRequest(msg) && !state.responding.has(id) &&
    (!state.current || !msg.params?.threadId || msg.params.threadId === state.current.id));
  if (permissions.length > 1) {
    const batch = document.createElement("div");
    batch.className = "approval-buttons approval-batch";
    for (const [label, decision] of [[`全部允许 (${permissions.length})`, "accept"], ["全部拒绝", "decline"]]) {
      const button = document.createElement("button");
      button.textContent = label;
      button.className = decision === "accept" ? "accept" : "";
      button.onclick = () => {
        for (const [id, msg] of permissions) answerRequest(id, permissionResult(msg, decision));
      };
      batch.append(button);
    }
    root.append(batch);
  }
  for (const [id, msg] of state.requests) {
    const p = msg.params || {};
    if (state.current && p.threadId && p.threadId !== state.current.id)
      continue;
    const existing = state.approvalCards.get(id);
    if (existing) {
      for (const control of existing.querySelectorAll("button, input, select, textarea"))
        control.disabled = state.responding.has(id);
      existing.setAttribute("aria-busy", String(state.responding.has(id)));
      root.append(existing);
      continue;
    }
    const card = document.createElement("section");
    card.className = "approval-card";
    const heading = document.createElement("h3");
    heading.textContent = msg.method.includes("commandExecution")
      ? "允许执行这条命令？"
      : msg.method.includes("fileChange")
        ? "允许修改文件？"
        : msg.method.includes("requestUserInput")
          ? "Codex 需要你的回答"
          : "Codex 需要你的操作";
    card.append(heading);
    if (p.reason) {
      const reason = document.createElement("p");
      reason.textContent = p.reason;
      card.append(reason);
    }
    if (p.command) {
      const pre = document.createElement("pre");
      pre.textContent = p.command;
      card.append(pre);
    }
    if (p.cwd) {
      const cwd = document.createElement("p");
      cwd.textContent = p.cwd;
      card.append(cwd);
    }
    if (Array.isArray(p.questions)) {
      renderUserInput(card, id, p);
    } else if (isPermissionRequest(msg)) {
      if (p.permissions) {
        const details = document.createElement("pre");
        details.textContent = JSON.stringify(p.permissions, null, 2);
        card.append(details);
      }
      const actions = document.createElement("div");
      actions.className = "approval-buttons";
      for (const [label, decision] of [
        ["允许一次", "accept"],
        ["本次会话允许", "acceptForSession"],
        ["拒绝", "decline"],
      ]) {
        const button = document.createElement("button");
        button.textContent = label;
        if (decision === "accept") button.className = "accept";
        button.onclick = () => {
          answerRequest(id, permissionResult(msg, decision));
        };
        actions.append(button);
      }
      card.append(actions);
    } else {
      const pre = document.createElement("pre");
      pre.textContent = JSON.stringify(p, null, 2);
      card.append(pre);
      const note = document.createElement("p");
      note.textContent = "此交互尚未提供专用界面，可以返回 JSON 结果，或取消。";
      card.append(note);
      const input = document.createElement("input");
      input.placeholder = "JSON 结果，例如 {}";
      input.setAttribute("aria-label", "交互 JSON 结果");
      card.append(input);
      const actions = document.createElement("div");
      actions.className = "approval-buttons";
      const submit = document.createElement("button");
      submit.className = "accept";
      submit.textContent = "返回结果";
      submit.onclick = () => {
        try {
          const result = JSON.parse(input.value);
          answerRequest(id, result);
        } catch {
          toast("请输入有效的 JSON");
        }
      };
      const cancel = document.createElement("button");
      cancel.textContent = "取消";
      cancel.onclick = () => {
        answerRequest(id, null, {
            code: -32000,
            message: "User cancelled remote interaction",
          });
      };
      actions.append(submit, cancel);
      card.append(actions);
    }
    state.approvalCards.set(id, card);
    root.append(card);
  }
  root.scrollTop = scrollTop;
  if (active?.isConnected && !active.disabled) active.focus({ preventScroll: true });
}
function sandboxPolicy() {
  const mode = state.settings.sandbox || "danger-full-access";
  if (mode === "workspace-write") return { type: "workspaceWrite", writableRoots: [], networkAccess: true, excludeTmpdirEnvVar: false, excludeSlashTmp: false };
  if (mode === "read-only") return { type: "readOnly", networkAccess: false };
  return { type: "dangerFullAccess" };
}
function turnParams(text, files = []) {
  const model = selectedModel();
  const effort = $("effort").value || model?.defaultReasoningEffort;
  return {
    threadId: state.current.id,
    approvalPolicy: state.settings.approvalPolicy || "never",
    sandboxPolicy: sandboxPolicy(),
    input: messageInput(text, files),
    ...(model ? { model: model.model } : {}),
    ...(effort ? { effort } : {}),
  };
}
function acceptMessageQueue(queue) {
  if (!queue?.threadId) return;
  const previous = state.messageQueues.get(queue.threadId);
  if (previous && previous.version > queue.version) return;
  state.messageQueues.set(queue.threadId, queue);
  if (state.current?.id === queue.threadId) renderConnection();
}
async function loadMessageQueue(threadId) {
  const generation = state.generation;
  const result = await rpc("codexrm/queue/list", { threadId });
  if (generation === state.generation) acceptMessageQueue(result);
}
function renderMessageQueue() {
  const root = $("message-queue");
  const queue = state.messageQueues.get(state.current?.id);
  root.hidden = !queue?.entries?.length;
  root.replaceChildren();
  if (root.hidden) return;
  const header = document.createElement("div");
  header.className = "queue-header";
  const label = document.createElement("span");
  label.textContent = `${queue.paused ? "排队已暂停" : "待发送"} · ${queue.entries.length} 条`;
  header.append(label);
  const disabled = !state.online || !state.loaded || state.loading || state.readOnly || state.sending;
  if (queue.paused) {
    const resume = document.createElement("button");
    resume.type = "button";
    resume.textContent = "继续排队";
    resume.disabled = disabled;
    resume.onclick = () => changeMessageQueue("resume");
    header.append(resume);
  }
  root.append(header);
  const list = document.createElement("div");
  list.className = "queue-list";
  for (const [index, entry] of queue.entries.entries()) {
    const row = document.createElement("div");
    row.className = "queue-entry";
    const text = document.createElement("p");
    const content = userContent(entry.input);
    text.textContent = `${index + 1}. ${[content.text, ...content.files.map((f) => `📎 ${f.name}`)].filter(Boolean).join("\n")}`;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = queue.dispatching && index === 0 ? "发送中…" : "取消";
    remove.disabled = disabled || (queue.dispatching && index === 0);
    remove.setAttribute("aria-label", `取消第 ${index + 1} 条排队消息`);
    remove.onclick = () => changeMessageQueue("remove", entry.id);
    row.append(text, remove);
    list.append(row);
  }
  root.append(list);
  if (queue.error) {
    const error = document.createElement("p");
    error.className = "queue-error";
    error.textContent = queue.error;
    root.append(error);
  }
}
async function changeMessageQueue(action, id) {
  if (state.sending || state.loading || state.readOnly || !state.current) return;
  const generation = state.generation;
  const threadId = state.current.id;
  state.sending = true;
  renderConnection();
  try {
    const result = await rpc(`codexrm/queue/${action}`, { threadId, ...(id ? { id } : {}) });
    if (generation === state.generation) acceptMessageQueue(result);
  } catch (e) {
    if (generation === state.generation) showError(e);
  } finally {
    if (generation === state.generation) {
      state.sending = false;
      renderConnection();
    }
  }
}
async function stopTurn() {
  const turn = state.turns.find((t) => t.status === "inProgress");
  if (!turn || state.sending || state.loading || state.readOnly) return;
  const generation = state.generation;
  state.sending = true;
  renderConnection();
  try {
    await rpc("turn/interrupt", { threadId: state.current.id, turnId: turn.id });
    if (generation === state.generation) toast("已请求停止任务，排队消息将保留");
  } catch (e) {
    if (generation === state.generation) showError(e);
  } finally {
    if (generation === state.generation) {
      state.sending = false;
      renderConnection();
    }
  }
}
async function sendFollowup(text, mode) {
  const generation = state.generation;
  const threadId = state.current.id;
  const turn = state.turns.find((t) => t.status === "inProgress");
  if (mode === "steer" && !turn) return toast("当前任务已结束，请发送新消息");
  if (mode !== "steer" && !state.info?.messageQueue)
    return toast("请更新本地 Agent 以支持排队发送");
  const original = $("prompt").value;
  const key = draftKey();
  const files = [...currentAttachments()];
  const params = turnParams(text, files);
  const optimistic = mode === "steer" ? {
    id: `optimistic-user-${Date.now()}-${++state.nextID}`,
    type: "userMessage", content: params.input,
  } : null;
  if (optimistic) turn.items.push(optimistic);
  state.sending = true;
  clearSentDraft(key, files);
  renderMessages();
  scrollBottom();
  try {
    if (mode === "steer") {
      await rpc("turn/steer", { threadId, expectedTurnId: turn.id, input: params.input });
      if (generation === state.generation) toast("补充指令已发送到当前任务");
    } else {
      const result = await rpc("codexrm/queue/add", params);
      if (generation === state.generation) acceptMessageQueue(result);
    }
    if (generation === state.generation && state.current?.id === threadId) saveModelPreference();
  } catch (e) {
    if (optimistic) turn.items = turn.items.filter((i) => i !== optimistic);
    restoreSentDraft(key, original, files);
    if (generation === state.generation && state.current?.id === threadId) {
      renderMessages();
    }
    if (generation === state.generation) showError(e);
  } finally {
    if (generation === state.generation) {
      state.sending = false;
      renderConnection();
    }
  }
}
async function submit(event, mode = "auto") {
  event?.preventDefault();
  if (currentDevice() && state.online && (state.loading || !state.loaded))
    return toast("正在同步工作区，请稍候再发送");
  if (state.readOnly) {
    toast("此会话由另一个 Codex 实例运行，请先结束原会话或新建会话");
    return;
  }
  const text = $("prompt").value.trim();
  const files = [...currentAttachments()];
  if ((!text && !files.length) || state.sending) return;
  if (files.some((f) => f.uploading || f.error)) return toast("请等待附件上传完成，或重试失败的附件");
  if (files.length && !state.info?.attachments) return toast("请更新本地 Agent 以支持文件和图片");
  if (!currentDevice()) {
    openPair();
    return;
  }
  if (!state.online) {
    toast("设备离线，请先启动本地 Agent");
    return;
  }
  if (mode === "steer" || mode === "queue" || isRunning() || state.messageQueues.get(state.current?.id)?.entries?.length)
    return sendFollowup(text, mode);
  const generation = state.generation;
  const originalKey = draftKey();
  const original = $("prompt").value;
  const creating = !state.current;
  const operation = creating ? { originalKey, original, files, cancelled: false, restored: false, turnStarted: false } : null;
  state.sending = true;
  if (creating) {
    state.creating = operation;
    clearSentDraft(originalKey, files);
  }
  renderConnection();
  try {
    if (creating) {
      let result;
      try {
        result = await rpc("thread/start", {
          ...(selectedModel() ? { model: selectedModel().model, codexrmProvider: selectedModel().providerId } : {}),
          ...(selectedCwd() ? { cwd: selectedCwd() } : {}),
          sandbox: state.settings.sandbox || "danger-full-access",
          approvalPolicy: state.settings.approvalPolicy || "never",
        });
      } catch (error) {
        restoreCreatingDraft(operation);
        throw error;
      }
      if (generation !== state.generation || operation.cancelled || state.creating !== operation) return;
      operation.turnStarted = true;
      state.creating = null;
      state.current = result.thread;
      if (result.model) state.current.model = result.model;
      state.turns = [];
      if ($("prompt").value) {
        writeDraft(originalKey, "");
        writeDraft(draftKey(), $("prompt").value);
      }
      setHome(false);
      upsertThread(result.thread);
      renderWorkspacePath();
    }
    const threadId = state.current.id;
    const key = draftKey();
    const params = turnParams(text, files);
    const optimistic = {
      id: `local-${Date.now()}`,
      status: "pending",
      items: [
        {
          id: `optimistic-user-${Date.now()}`,
          type: "userMessage",
          content: params.input,
        },
      ],
    };
    state.turns.push(optimistic);
    renderMessages();
    scrollBottom();
    if (!creating) clearSentDraft(originalKey, files);
    try {
      const result = await rpc("turn/start", params);
      if (generation !== state.generation || state.current?.id !== threadId)
        return;
      if (selectedModel()) state.current.model = selectedModel().model;
      saveModelPreference();
      state.turns = state.turns.filter((t) => t !== optimistic);
      const existing = state.turns.find((t) => t.id === result.turn.id);
      if (existing) {
        if (!existing.items.some((i) => i.type === "userMessage"))
          existing.items.unshift(optimistic.items[0]);
      } else
        state.turns.push({
          ...result.turn,
          items: result.turn.items?.length
            ? result.turn.items
            : optimistic.items,
        });
      state.started = Date.now();
      scheduleRender();
      if (creating) {
        const name = Array.from((text || files[0]?.name || "新会话").split("\n")[0]).slice(0, 40).join("");
        rpc("thread/name/set", { threadId, name }).catch(() => {});
      }
    } catch (error) {
      restoreSentDraft(key, original, files);
      if (creating) operation.restored = true;
      if (generation === state.generation && state.current?.id === threadId) {
        state.turns = state.turns.filter((t) => t !== optimistic);
        renderMessages();
      }
      throw error;
    }
  } catch (error) {
    if (creating) restoreCreatingDraft(operation);
    if (generation === state.generation && !operation?.cancelled) showError(error);
  } finally {
    const ownsSend = !creating || (!operation.cancelled && (operation.turnStarted || state.creating === operation));
    if (generation === state.generation && ownsSend) {
      if (state.creating === operation) state.creating = null;
      state.sending = false;
      renderConnection();
    }
  }
}
function resizePrompt() {
  $("prompt").style.height = "auto";
  $("prompt").style.height = Math.min($("prompt").scrollHeight, 200) + "px";
  saveDraft();
  renderComposerControls();
}
function pairCodeFromLink() {
  const params = new URLSearchParams(location.hash.slice(1));
  const code = params.get("pair")?.trim() || "";
  if (!code) return "";
  params.delete("pair");
  const hash = params.toString();
  history.replaceState(
    null,
    "",
    `${location.pathname}${location.search}${hash ? `#${hash}` : ""}`,
  );
  return code;
}
function openPair(code = "") {
  $("pair-code").value = code;
  $("pair-error").textContent = "";
  $("agent-command").textContent =
    `codexrm-agent --server ${location.origin}\n  --cwd <本地项目路径>`;
  $("pair-dialog").showModal();
  setTimeout(() => $("pair-code").focus(), 50);
}
const mobileLayoutQuery =
  "(max-width:760px), (max-height:500px) and (pointer:coarse)";
function isMobileLayout() {
  return matchMedia(mobileLayoutQuery).matches;
}
let sidebarWidth = readStorage("codexrm.sidebarWidth", null);
if (!Number.isFinite(sidebarWidth)) sidebarWidth = null;
let sidebarResize = null;
function sidebarWidthBounds() {
  return { min: 220, max: Math.max(220, Math.min(520, Math.floor(window.innerWidth * 0.45))) };
}
function syncSidebarWidth() {
  const { min, max } = sidebarWidthBounds();
  if (sidebarWidth === null) document.documentElement.style.removeProperty("--sidebar-width");
  else document.documentElement.style.setProperty("--sidebar-width", `${Math.max(min, Math.min(520, sidebarWidth))}px`);
  const width = Math.max(min, Math.min(max, sidebarWidth ?? (window.innerWidth <= 1050 ? 240 : 260)));
  const handle = $("sidebar-resize");
  handle.setAttribute("aria-valuemin", String(min));
  handle.setAttribute("aria-valuemax", String(max));
  handle.setAttribute("aria-valuenow", String(Math.round(width)));
}
function setSidebarWidth(width, persist = true) {
  const { min, max } = sidebarWidthBounds();
  sidebarWidth = width === null ? null : Math.round(Math.max(min, Math.min(max, width)));
  syncSidebarWidth();
  if (persist) {
    try { localStorage.setItem("codexrm.sidebarWidth", JSON.stringify(sidebarWidth)); } catch {}
  }
}
function finishSidebarResize(event) {
  if (!sidebarResize || (event?.pointerId !== undefined && event.pointerId !== sidebarResize.id)) return;
  const id = sidebarResize.id;
  sidebarResize = null;
  document.body.classList.remove("sidebar-resizing");
  const handle = $("sidebar-resize");
  if (handle.hasPointerCapture?.(id)) handle.releasePointerCapture(id);
  try { localStorage.setItem("codexrm.sidebarWidth", JSON.stringify(sidebarWidth)); } catch {}
}
function startSidebarResize(event) {
  if (event.button !== 0 || event.isPrimary === false || isMobileLayout() || document.body.classList.contains("sidebar-collapsed")) return;
  event.preventDefault();
  closeThreadMenu();
  closeModelMenu();
  sidebarResize = { id: event.pointerId, x: event.clientX, width: $("sidebar").getBoundingClientRect().width || sidebarWidth || 260 };
  $("sidebar-resize").setPointerCapture(event.pointerId);
  document.body.classList.add("sidebar-resizing");
}
function moveSidebarResize(event) {
  if (!sidebarResize || event.pointerId !== sidebarResize.id) return;
  if (isMobileLayout()) return finishSidebarResize();
  setSidebarWidth(sidebarResize.width + event.clientX - sidebarResize.x, false);
}
function sidebarResizeKey(event) {
  if (isMobileLayout() || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  const { min, max } = sidebarWidthBounds();
  const width = Number($("sidebar-resize").getAttribute("aria-valuenow"));
  const step = event.shiftKey ? 50 : 10;
  setSidebarWidth(event.key === "Home" ? min : event.key === "End" ? max : width + (event.key === "ArrowRight" ? step : -step));
}
function openSidebar() {
  if (!isMobileLayout()) return;
  document.body.classList.add("sidebar-open");
  $("sidebar-overlay").hidden = false;
}
function closeSidebar() {
  closeThreadMenu();
  document.body.classList.remove("sidebar-open");
  $("sidebar-overlay").hidden = true;
}
let sidebarTouch = null;
const sidebarGestureExcluded = [
  "button",
  "a",
  "input",
  "textarea",
  "select",
  "label",
  "summary",
  "dialog",
  "[contenteditable]",
  "[role='button']",
  ".composer",
  ".messages",
  ".approval-area",
  ".message-queue",
  ".activity",
].join(",");
const sidebarCloseGestureExcluded =
  "input, textarea, select, [contenteditable]";
function sidebarGestureStart(event) {
  sidebarTouch = null;
  const opening = event.currentTarget === $("main");
  const isOpen = document.body.classList.contains("sidebar-open");
  if (
    !isMobileLayout() ||
    (opening ? isOpen : !isOpen) ||
    document.querySelector("dialog[open]") ||
    event.touches.length !== 1 ||
    event.target.closest?.(
      opening ? sidebarGestureExcluded : sidebarCloseGestureExcluded,
    )
  )
    return;
  const touch = event.touches[0];
  sidebarTouch = {
    id: touch.identifier,
    x: touch.clientX,
    y: touch.clientY,
    opening,
  };
}
function sidebarGestureMove(event) {
  if (!sidebarTouch) return;
  if (event.touches.length !== 1) {
    sidebarTouch = null;
    return;
  }
  const touch = event.touches[0];
  if (
    touch.identifier !== sidebarTouch.id ||
    Math.abs(touch.clientY - sidebarTouch.y) >
      Math.abs(touch.clientX - sidebarTouch.x)
  )
    sidebarTouch = null;
}
function sidebarGestureEnd(event) {
  const start = sidebarTouch;
  sidebarTouch = null;
  if (!start) return;
  const touch = Array.from(event.changedTouches).find(
    (t) => t.identifier === start.id,
  );
  if (!touch) return;
  const deltaX = touch.clientX - start.x;
  const deltaY = touch.clientY - start.y;
  const swiped = start.opening ? deltaX >= 56 : deltaX <= -56;
  if (!swiped || Math.abs(deltaX) <= Math.abs(deltaY) * 1.2) return;
  if (start.opening) openSidebar();
  else closeSidebar();
}
function settings() {
  $("sandbox").value = state.settings.sandbox || "danger-full-access";
  $("approval-policy").value = state.settings.approvalPolicy || "never";
  const d = currentDevice();
  const version = state.info?.version || d?.agent.appVersion;
  $("device-detail").textContent = d
    ? `${d.agent.serverName} · ${d.agent.os} ${d.agent.arch}${version ? ` · 电脑 Agent v${version}` : " · 电脑 Agent 版本未知"}${state.info ? ` · ${state.info.transport}` : ""}`
    : "尚未选择设备";
  $("disconnect").hidden = !d;
  const remoteUpdate = Boolean(d && state.online && state.info?.remoteUpdate);
  $("update-agent").hidden = !remoteUpdate;
  $("update-agent-help").hidden = !remoteUpdate;
  const desktopControl = Boolean(d && state.online && state.info?.desktopControl);
  $("close-codex-desktop").hidden = !desktopControl;
  $("close-codex-desktop-help").hidden = !desktopControl;
  $("settings-dialog").showModal();
}
async function updateAgent() {
  if (!state.online || !state.info?.remoteUpdate) return toast("电脑 Agent 离线或不支持远程更新");
  if (!window.confirm("检查并更新电脑 Agent 吗？更新会在当前任务和排队消息结束后安装，随后电脑 Agent 会自动重启。")) return;
  const button = $("update-agent");
  button.disabled = true;
  button.textContent = "正在检查更新…";
  try {
    const result = await rpc("codexrm/update", {}, 600000);
    if (!result.updateAvailable) toast("电脑 Agent 已是最新版本");
    else if (result.updatePending) toast("更新已校验，将在当前任务和排队消息结束后自动安装");
    else toast("更新已校验，电脑 Agent 将重启完成安装");
  } catch (e) {
    showError(e);
  } finally {
    button.disabled = false;
    button.textContent = "检查并更新电脑 Agent";
  }
}
$("update-agent").onclick = updateAgent;
async function closeCodexDesktop() {
  if (!state.online) return toast("设备离线，无法关闭 Codex 桌面客户端");
  if (!window.confirm("确定关闭 Codex 桌面客户端吗？未保存的桌面操作可能丢失。")) return;
  const button = $("close-codex-desktop");
  button.disabled = true;
  try {
    const result = await rpc("codexrm/desktop/close");
    toast(result.message || "Codex 桌面客户端已关闭");
  } catch (e) {
    showError(e);
  } finally {
    button.disabled = false;
  }
}
$("close-codex-desktop").onclick = closeCodexDesktop;
$("pair-form").onsubmit = async (event) => {
  event.preventDefault();
  $("pair-submit").disabled = true;
  $("pair-submit").textContent = "正在连接…";
  $("pair-error").textContent = "";
  try {
    const result = await api("/api/v1/client/claim", {
      code: $("pair-code").value.trim(),
      ...browserClientProfile(),
    });
    const old = state.devices.find(
      (d) => d.agent.agentId === result.agent.agentId,
    );
    state.devices = state.devices.filter(
      (d) => d.agent.agentId !== result.agent.agentId,
    );
    state.devices.push(result);
    state.selected = result.agent.agentId;
    persist();
    resetView();
    connect();
    $("pair-dialog").close();
    toast(`已配对 ${result.agent.serverName}`);
    if (old) api("/api/v1/client/revoke", {}, old.token).catch(() => {});
  } catch (error) {
    $("pair-error").textContent =
      {
        404: "找不到这个配对码，请确认输入正确。",
        410: "配对码已过期，请在电脑上重新生成。",
        409: "配对码已使用，请重新生成。",
      }[error.status] || error.message;
  } finally {
    $("pair-submit").disabled = false;
    $("pair-submit").innerHTML = "连接设备" + icon("arrow-right");
  }
};
$("settings-form").onsubmit = (event) => {
  event.preventDefault();
  state.settings = {
    sandbox: $("sandbox").value,
    approvalPolicy: $("approval-policy").value,
  };
  try {
    localStorage.setItem("codexrm.settings", JSON.stringify(state.settings));
  } catch {}
  renderWorkspacePath();
  $("settings-dialog").close();
  renderModelControl();
  toast("设置已保存，将应用于下一次发送");
};
$("disconnect").onclick = async () => {
  const d = currentDevice();
  if (!d) return;
  $("disconnect").disabled = true;
  try {
    await api("/api/v1/client/revoke", {}, d.token);
  } catch (e) {
    if (e.status !== 401) {
      showError(e);
      $("disconnect").disabled = false;
      return;
    }
  }
  disconnectSocket();
  state.devices = state.devices.filter((x) => x !== d);
  state.selected = state.devices[0]?.agent.agentId || null;
  persist();
  resetView();
  connect();
  $("settings-dialog").close();
  $("disconnect").disabled = false;
  toast("已解除配对");
};
async function renameThread(threadId, name) {
  await rpc("thread/name/set", { threadId, name });
  const listed = state.threads.find((thread) => thread.id === threadId);
  if (listed) listed.name = name;
  if (state.current?.id === threadId) {
    state.current.name = name;
    upsertThread(state.current);
    renderThreadTitle();
  } else if (listed) {
    upsertThread(listed);
  }
}
$("header-subtitle").onclick = beginTitleEdit;
$("title-rename-form").onsubmit = (event) => {
  event.preventDefault();
  return saveTitleEdit();
};
$("title-rename-input").onkeydown = (event) => {
  if (event.key !== "Escape") return;
  event.preventDefault();
  cancelTitleEdit();
};
$("title-rename-input").onblur = () => {
  if (!titleEditSaving) cancelTitleEdit();
};
$("rename-form").onsubmit = async (event) => {
  event.preventDefault();
  if (!state.current) return;
  const threadId = state.current.id;
  const name = $("thread-name").value.trim();
  if (!name) return;
  try {
    await renameThread(threadId, name);
    if (state.current?.id === threadId) $("thread-dialog").close();
  } catch (e) {
    showError(e);
  }
};
$("archive-thread").onclick = async () => {
  if (!state.current) return;
  const id = state.current.id;
  try {
    if (await archiveThread(id)) {
      $("thread-dialog").close();
      toast("会话已归档");
    }
  } catch (e) {
    showError(e);
  }
};
$("thread-actions").onclick = () => {
  if (!state.current) return;
  $("thread-name").value = title(state.current);
  $("thread-dialog").showModal();
};
$("delete-thread").onclick = () => {
  if (threadMenuTarget) requestThreadDeletion(threadMenuTarget.id);
};
$("confirm-delete-thread").onclick = confirmThreadDeletion;
$("delete-thread-dialog").addEventListener("close", () => {
  const target = deleteThreadTarget;
  deleteThreadTarget = null;
  (threadActionButton(target?.id) || threadActionButton(state.current?.id) || $("prompt")).focus();
});
$("thread-menu").addEventListener("keydown", (event) => {
  if (event.key === "Escape" || event.key === "Tab") {
    if (event.key === "Escape") event.preventDefault();
    event.stopPropagation();
    closeThreadMenu(true);
  } else if (["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) {
    event.preventDefault();
    if (!$("delete-thread").disabled) $("delete-thread").focus();
  }
});
document
  .querySelectorAll("[data-close]")
  .forEach((b) => (b.onclick = () => $(b.dataset.close).close()));
document.querySelectorAll("dialog").forEach((d) =>
  d.addEventListener("click", (e) => {
    if (e.target === d) {
      const r = d.getBoundingClientRect();
      if (
        e.clientX < r.left ||
        e.clientX > r.right ||
        e.clientY < r.top ||
        e.clientY > r.bottom
      )
        d.close();
    }
  }),
);
$("new-chat").onclick = newChat;
$("add-device").onclick = () => openPair();
$("connection-button").onclick = openDevicePicker;
$("device-pair").onclick = () => {
  $("device-dialog").close();
  openPair();
};
$("open-settings").onclick = settings;
$("add-workspace").onclick = openWorkspacePicker;
$("workspace-select").onchange = () =>
  selectWorkspace($("workspace-select").value).catch(showError);
$("directory-path-form").onsubmit = (event) => {
  event.preventDefault();
  browseDirectory($("directory-path").value.trim());
};
$("directory-path").oninput = () => {
  $("select-directory").disabled = true;
};
$("directory-hidden").onchange = () =>
  browseDirectory($("directory-path").value.trim());
$("workspace-dialog").addEventListener("close", () => {
  state.directoryVersion++;
  state.directory = null;
});
$("select-directory").onclick = async () => {
  if (!state.directory) return;
  const path = state.directory.path;
  const generation = state.generation;
  $("select-directory").disabled = true;
  try {
    const result = await rpc("codexrm/workspace/add", { path });
    if (generation !== state.generation) return;
    state.workspaces = result.workspaces || [];
    $("workspace-dialog").close();
    await selectWorkspace(path);
    toast(`已添加工作区 ${folderName(path)}`);
  } catch (e) {
    if (generation === state.generation) {
      $("directory-error").textContent = e.message;
      $("select-directory").disabled = false;
    }
  }
};
$("toggle-workspaces").onclick = toggleWorkspaceSection;
$("refresh").onclick = async () => {
  try {
    if (state.online && (!state.loaded || !state.info)) {
      await bootstrap(state.generation);
      return;
    }
    await loadWorkspaces();
    await loadThreads();
  } catch (e) {
    showError(e);
  }
};
$("load-more").onclick = () => loadThreads(true).catch(showError);
$("search").oninput = () => {
  closeThreadMenu();
  renderThreads();
};
$("model").onchange = () => {
  renderEfforts();
  saveModelPreference();
  if (state.current && (selectedModel()?.providerId || "") !== threadProviderId()) {
    newChat();
    toast("已切换供应商，发送消息将创建新会话");
  }
};
$("effort").onchange = () => {
  saveModelPreference();
  renderModelControl();
};
$("model-trigger").onclick = openModelMenu;
$("permission-trigger").onclick = settings;
$("choose-model").onclick = () => modelMenuPane("models");
$("choose-effort").onclick = () => modelMenuPane("effort");
$("model-menu-back").onclick = () => modelMenuPane();
$("close-model-menu").onclick = () => closeModelMenu(true);
$("model-search").oninput = renderModelChoices;
$("model-menu").addEventListener("toggle", (event) => {
  if (event.newState === "closed") {
    $("model-menu").hidden = true;
    $("model-trigger").setAttribute("aria-expanded", "false");
  }
});
$("model-menu").addEventListener("keydown", (event) => {
  if (event.isComposing) return;
  if (event.key === "Escape" || (event.key === "Tab" && event.shiftKey)) {
    event.preventDefault();
    if ($("model-menu").dataset.pane !== "root") modelMenuPane();
    else closeModelMenu(true);
    return;
  }
  const options = [...$("model-menu").querySelectorAll("button:not([disabled])")]
    .filter((button) => !button.closest("[hidden]"));
  const active = options.indexOf(document.activeElement);
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const direction = event.key === "ArrowDown" ? 1 : -1;
    options[(active + direction + options.length) % options.length]?.focus();
  } else if (event.key === "Enter" && event.target === $("model-search")) {
    event.preventDefault();
    $("model-choices").querySelector("button:not([disabled])")?.click();
  } else if (event.key === "Tab") {
    event.preventDefault();
    options[(active + 1) % options.length]?.focus();
  }
});
document.addEventListener("pointerdown", (event) => {
  if (threadMenuTarget && !$("thread-menu").contains(event.target) && !threadActionButton(threadMenuTarget.id)?.contains(event.target)) closeThreadMenu();
  if (!$("model-menu").hidden && !$("model-menu").contains(event.target) && !$("model-trigger").contains(event.target)) closeModelMenu();
});
window.addEventListener("resize", () => {
  if (isMobileLayout()) finishSidebarResize();
  syncSidebarWidth();
  closeThreadMenu();
  positionModelMenu();
});
window.addEventListener("blur", finishSidebarResize);
$("sidebar").addEventListener("scroll", () => closeThreadMenu(), { passive: true, capture: true });
window.visualViewport?.addEventListener("resize", positionModelMenu);
window.visualViewport?.addEventListener("scroll", positionModelMenu);
$("refresh-models").onclick = () => loadModels(true).catch(showError);
$("composer").onsubmit = submit;
$("queue-send").onclick = () => submit(null, "queue");
$("steer").onclick = () => submit(null, "steer");
$("stop").onclick = stopTurn;
$("attach-files").onclick = () => $("file-input").click();
$("file-input").onchange = (event) => {
  const files = [...event.target.files];
  event.target.value = "";
  addAttachments(files).catch(showError);
};
document.addEventListener("paste", pasteAttachments);
$("composer").addEventListener("dragover", (event) => {
  if ([...(event.dataTransfer?.types || [])].includes("Files")) {
    event.preventDefault();
    event.dataTransfer.dropEffect = attachmentBlockedReason() ? "none" : "copy";
  }
});
$("composer").addEventListener("drop", (event) => {
  if (event.dataTransfer?.files.length) {
    event.preventDefault();
    const blocked = attachmentBlockedReason();
    if (blocked) return toast(blocked);
    addAttachments([...event.dataTransfer.files]).catch(showError);
  }
});
$("prompt").oninput = resizePrompt;
$("prompt").onkeydown = (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    return submit(null, isRunning() && (e.ctrlKey || e.metaKey) ? "steer" : "auto");
  }
};
$("toggle-sidebar").onclick = () => {
  closeThreadMenu();
  finishSidebarResize();
  if (isMobileLayout()) {
    if (document.body.classList.contains("sidebar-open")) closeSidebar();
    else openSidebar();
  } else document.body.classList.toggle("sidebar-collapsed");
};
$("close-sidebar").onclick = closeSidebar;
$("sidebar-overlay").onclick = closeSidebar;
$("sidebar-resize").onpointerdown = startSidebarResize;
$("sidebar-resize").onpointermove = moveSidebarResize;
$("sidebar-resize").onpointerup = finishSidebarResize;
$("sidebar-resize").onpointercancel = finishSidebarResize;
$("sidebar-resize").onlostpointercapture = finishSidebarResize;
$("sidebar-resize").onkeydown = sidebarResizeKey;
$("sidebar-resize").ondblclick = () => setSidebarWidth(null);
for (const element of [$("main"), $("sidebar"), $("sidebar-overlay")]) {
  element.addEventListener("touchstart", sidebarGestureStart, { passive: true });
  element.addEventListener("touchmove", sidebarGestureMove, { passive: true });
  element.addEventListener("touchend", sidebarGestureEnd, { passive: true });
  element.addEventListener("touchcancel", () => (sidebarTouch = null), {
    passive: true,
  });
}
$("theme-toggle").onclick = () => {
  document.documentElement.dataset.theme =
    document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  try {
    localStorage.setItem(
      "codexrm.theme",
      JSON.stringify(document.documentElement.dataset.theme),
    );
  } catch {}
};
document.documentElement.dataset.theme = readStorage("codexrm.theme", "dark");
syncSidebarWidth();
document.addEventListener("keydown", (e) => {
  if (document.querySelector("dialog[open]")) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    newChat();
  }
  if (
    e.key === "/" &&
    !["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName) &&
    !document.querySelector("dialog[open]")
  ) {
    e.preventDefault();
    openSidebar();
    $("search").focus();
  }
});
setInterval(() => {
  if (!isRunning()) return;
  const seconds = Math.max(0, Math.floor((Date.now() - state.started) / 1000));
  $("elapsed").textContent =
    `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}, 1000);
setInterval(() => {
  if (!state.online || !state.loaded || state.threadListLoading || document.hidden) return;
  loadThreads().catch((error) => toast(`会话列表同步失败：${error.message}`));
}, 15000);
document.addEventListener("visibilitychange", handleVisibilityChange);
window.addEventListener("pageshow", resumeConnection);
window.addEventListener("online", resumeConnection);
renderDevices();
renderWorkspaces();
renderThreads();
renderConnection();
const linkedPairCode = pairCodeFromLink();
if (linkedPairCode) {
  openPair(linkedPairCode);
  $("pair-form").requestSubmit();
} else if (currentDevice()) connect();
if (document.visibilityState === "hidden") handleVisibilityChange();
