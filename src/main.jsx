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
