import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "@fontsource-variable/inter";
import "./index.css";
import { App } from "./App";
import { installRemovalMessages, removedWhileClosed, signalDeviceRemoved } from "./deviceRemoval";
import { finishWipeOnLoad, followWipesInOtherTabs } from "./deviceWipe";
import { installLogo } from "./logo";
import { installNotificationClicks } from "./notifications/push";
import { installTheme } from "./theme";

// Before anything reads storage: an interrupted logout is finished, and what an old logout
// left behind is cleared, so the first screen never sees another account's data.
void finishWipeOnLoad();
followWipesInOtherTabs();
installTheme();
installLogo();
// A notification click routes to its chat — also the one that opened this window.
installNotificationClicks();
// The worker relays "this browser was removed from the account" to every open tab.
installRemovalMessages();

const root = createRoot(document.getElementById("root")!);
// A removal that arrived while no tab was open left a marker (Cache Storage, which the worker
// can reach). Look before the first render, so the first screen is the wipe, not the account.
void removedWhileClosed().then((removed) => {
  if (removed) signalDeviceRemoved();
  root.render(
    <StrictMode>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </StrictMode>,
  );
});
