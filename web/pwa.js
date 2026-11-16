(() => {
  "use strict";

  const installButton = document.getElementById("install-app");
  let installPrompt = null;

  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    installPrompt = event;
    if (installButton) installButton.hidden = false;
  });

  installButton?.addEventListener("click", async () => {
    if (!installPrompt) return;
    installButton.disabled = true;
    try {
      await installPrompt.prompt();
      await installPrompt.userChoice;
      installPrompt = null;
      installButton.hidden = true;
    } finally {
      installButton.disabled = false;
    }
  });

  window.addEventListener("appinstalled", () => {
    installPrompt = null;
    if (installButton) installButton.hidden = true;
  });

  if (!("serviceWorker" in navigator)) return;
  window.addEventListener(
    "load",
    () => {
      navigator.serviceWorker.register("/sw.js", { scope: "/" }).catch((error) => {
        console.warn("CodexRM offline cache registration failed", error);
      });
    },
    { once: true },
  );
})();
