// ES module for the "esm" fixture. Loaded via <script type="module" src="./app.js">.
// It fetches ./data.json at runtime; the result is shown as a big PASS/FAIL.

const statusEl = document.getElementById("status");
const detailEl = document.getElementById("detail");
const logEl = document.getElementById("log");

function finish(kind, title, detail, logLine) {
  statusEl.className = "status " + kind;
  statusEl.textContent = title;
  detailEl.textContent = detail;
  logEl.textContent += "\n" + logLine;
  window.__esmDone = true;
}

async function main() {
  const started = Date.now();
  logEl.textContent = "location: " + location.href + "\nimport.meta.url: " + import.meta.url;

  const response = await fetch("./data.json", { cache: "no-store" });
  if (!response.ok) {
    throw new Error("fetch ./data.json -> HTTP " + response.status);
  }
  const data = await response.json();
  if (data.ok !== true) {
    throw new Error("data.ok is not true: " + JSON.stringify(data));
  }

  const elapsed = Date.now() - started;
  finish("pass", "PASS",
      "type=module 的 app.js 执行成功；fetch('./data.json') 成功。\n" +
      "message: " + data.message + "\n" +
      "items: " + (Array.isArray(data.items) ? data.items.join(", ") : "(not an array)") + "\n" +
      "elapsed: " + elapsed + " ms",
      "OK: module + fetch data.json (" + elapsed + " ms)");
}

main().catch(function (error) {
  finish("fail", "FAIL",
      "ES module 或 fetch 失败：\n" + error.name + ": " + error.message,
      "FAIL: " + error.name + ": " + error.message);
});
