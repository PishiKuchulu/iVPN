// Plain-Node local dev server (no dependencies). Mirrors the Vercel routes:
//   GET  /api/bundle   POST /api/report   GET /api/stats   GET /health
import { createServer } from "node:http";
import { handleBundle, handleReport, handleStats } from "./lib/handlers.mjs";

const PORT = process.env.PORT || 3000;

function send(res, out) {
  res.writeHead(out.status, out.headers);
  res.end(out.body);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  try {
    if (url.pathname === "/health") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
    } else if (url.pathname === "/api/bundle" && req.method === "GET") {
      send(res, await handleBundle());
    } else if (url.pathname === "/api/stats" && req.method === "GET") {
      send(res, await handleStats(req.headers));
    } else if (url.pathname === "/api/report" && req.method === "POST") {
      let data = "";
      req.on("data", (chunk) => {
        data += chunk;
        if (data.length > 16384) req.destroy();
      });
      req.on("end", async () => {
        let body;
        try {
          body = JSON.parse(data || "{}");
        } catch {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ accepted: false, errors: ["body is not valid JSON"] }));
          return;
        }
        send(res, await handleReport(body));
      });
    } else {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "not_found" }));
    }
  } catch (err) {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "internal", detail: String(err?.message || err) }));
  }
});

server.listen(PORT, () => console.log(`autoconnect-backend dev server on http://localhost:${PORT}`));
