import { handleReport } from "../lib/handlers.mjs";

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.status(405).json({ error: "method_not_allowed" });
    return;
  }
  let body = req.body;
  if (typeof body === "string") {
    try {
      body = JSON.parse(body);
    } catch {
      res.status(400).json({ accepted: false, errors: ["body is not valid JSON"] });
      return;
    }
  }
  if (body === undefined || body === null) body = {};
  const out = await handleReport(body);
  for (const [k, v] of Object.entries(out.headers)) res.setHeader(k, v);
  res.status(out.status).send(out.body);
}
