import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import TvApp from "./tv/TvApp";
import "./styles/global.css";

const television =
  typeof window !== "undefined" && !!(window.__SB_BOOT && window.__SB_BOOT.television);

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>{television ? <TvApp /> : <App />}</React.StrictMode>,
);

// Android holds the navy splash until this first paint. Desktop has no bridge.
requestAnimationFrame(() => {
  requestAnimationFrame(() => {
    if (typeof window.__sbCall === "function") {
      window.__sbCall("uiReady", {}, 5000).catch(() => {});
    }
  });
});
