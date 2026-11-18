"use strict";

try {
  document.documentElement.dataset.theme = JSON.parse(localStorage.getItem("codexrm.theme")) || "dark";
} catch {
  document.documentElement.dataset.theme = "dark";
}
