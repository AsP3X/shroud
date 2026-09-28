import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import "@fontsource-variable/inter";
import "./index.css";
import { App } from "./App";
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

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
