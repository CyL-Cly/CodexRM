"use strict";
const $ = (id) => document.getElementById(id);
const token = location.hash.slice(1);
// URL fragments are never sent in HTTP requests or referrers. Keeping the
// capability here also lets the user refresh this page during this agent run.
let saved = null;
let code = "";
let busy = false;
const notice = (message, error = false) => {
  $("notice").textContent = message;
  $("notice").className = error ? "notice error" : "notice";
  $("notice").hidden = !message;
};
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, cache: "no-store" });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { if (!response.ok) throw new Error(text.trim()); throw new Error("服务器返回了无效的数据"); }
  if (!response.ok) throw new Error(data.error || "操作失败，请稍后重试");
  return data;
}
function status(data) {
  $("status").textContent = data.status;
  $("status").className = `status ${data.status === "已连接" ? "connected" : data.status === "连接失败" ? "failed" : ""}`;
  $("connection-error").textContent = data.status === "已连接" ? "" : data.error || "";
}
function apply(data) {
  saved = data;
  $("server").value = data.server || "";
  $("update-server").value = data.updateServer;
  $("update-settings").hidden = !!data.packaged;
  $("update-server").disabled = !!data.packaged;
  $("package-settings").hidden = !data.packaged;
  $("key").value = "";
  $("key").placeholder = data.enrollmentKeySet ? "已保存密钥；留空保留，输入新密钥可替换" : "输入服务器的连接授权密钥";
  $("key-hint").textContent = data.enrollmentKeySet ? "密钥已保存在本机。更换服务器时，请提供对应的授权密钥。" : "由远程服务器管理员提供。";
  $("version").textContent = data.version;
  status(data);
}
async function action(button, work) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  try { await work(); } catch (error) { notice(error.message, true); } finally { button.disabled = false; busy = false; }
}
function dirty() {
  return !saved || $("server").value !== saved.server || $("key").value !== "" || $("update-server").value !== saved.updateServer;
}
function requireSaved() {
  if (dirty()) throw new Error("请先保存连接配置，再进行配对或设备管理。");
  if (!saved.server) throw new Error("请先填写远程服务器和连接授权密钥。");
}
async function devices() {
  requireSaved();
  const rows = await api("/api/devices");
  $("devices").replaceChildren();
  if (!rows.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "暂无已配对设备。生成配对码以连接第一台设备。";
    $("devices").append(empty);
  }
  for (const row of rows) {
    const item = document.createElement("div");
    item.className = "device";
    const icon = document.createElement("span");
    icon.className = "device-icon";
    icon.textContent = row.deviceType === "mobile" ? "▯" : "▣";
    const info = document.createElement("div");
    info.className = "device-info";
    const name = document.createElement("strong");
    name.textContent = row.clientName || "未命名设备";
    name.title = name.textContent;
    const date = document.createElement("p");
    date.className = "hint";
    date.textContent = `配对于 ${row.createdAt ? new Date(row.createdAt).toLocaleString() : "未知时间"}`;
    info.append(name, date);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "text-button";
    remove.textContent = "移除授权";
    remove.addEventListener("click", () => {
      if (!confirm(`移除“${row.clientName || "未命名设备"}”的访问授权？`)) return;
      action(remove, async () => { requireSaved(); await api(`/api/devices/${encodeURIComponent(row.clientId)}`, { method: "DELETE" }); await devices(); notice("设备授权已移除。"); });
    });
    item.append(icon, info, remove);
    $("devices").append(item);
  }
}
$("settings-form").addEventListener("submit", (event) => {
  event.preventDefault();
  action($("save"), async () => {
    const input = { server: $("server").value.trim(), updateServer: $("update-server").value.trim() };
    if ($("key").value) input.enrollmentKey = $("key").value;
    const data = await api("/api/settings", { method: "PUT", body: JSON.stringify(input) });
    apply(data);
    $("pair-result").hidden = true;
    code = "";
    $("devices").replaceChildren();
    notice("设置已保存，Agent 正在连接服务器。");
  });
});
$("pair").addEventListener("click", () => action($("pair"), async () => {
  requireSaved();
  const data = await api("/api/pair", { method: "POST" });
  code = data.code;
  $("pair-code").textContent = data.code;
  $("pair-link").href = data.url;
  $("qr").src = data.qr;
  $("expires").textContent = `有效至 ${new Date(data.expiresAt * 1000).toLocaleTimeString()}`;
  $("pair-result").hidden = false;
  notice("配对码已生成，可在另一台设备上完成配对。");
}));
$("copy-code").addEventListener("click", () => action($("copy-code"), async () => { await navigator.clipboard.writeText(code); notice("配对码已复制。"); }));
$("refresh").addEventListener("click", () => action($("refresh"), async () => { await devices(); notice(""); }));
$("toggle-key").addEventListener("click", () => {
  const show = $("key").type === "password";
  $("key").type = show ? "text" : "password";
  $("toggle-key").textContent = show ? "隐藏" : "显示";
  $("toggle-key").setAttribute("aria-label", show ? "隐藏连接授权密钥" : "显示连接授权密钥");
});
api("/api/settings").then(async (data) => { apply(data); if (data.server) { try { await devices(); } catch (error) { notice(error.message, true); } } }).catch((error) => notice(error.message, true));
setInterval(async () => { try { status(await api("/api/settings")); } catch { $("status").textContent = "Agent 已退出或页面已失效"; } }, 5000);
