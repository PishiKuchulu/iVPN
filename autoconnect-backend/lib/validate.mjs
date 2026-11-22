// Strict validation for anonymous client reports (architecture doc §4.2 / §9.4).
// Allowlist-only: any unknown field rejects the whole report.

export const OPERATOR_KEYS = ["mci", "irancell", "rightel", "other-ir", "unknown", "wifi"];
export const STAGES = ["handshake", "leak_test", "connected"];
export const RESULTS = ["success", "fail"];
const ALLOWED_FIELDS = ["config_id", "operator_key", "stage_reached", "handshake_ms", "result", "hour_bucket"];

export function isValidOperatorKey(k) {
  if (typeof k !== "string") return false;
  if (OPERATOR_KEYS.includes(k)) return true;
  return /^wifi:\d{1,10}$/.test(k); // wifi:<asn> per doc §7.1
}

export function validateReport(body) {
  const errors = [];
  if (body === null || typeof body !== "object" || Array.isArray(body)) {
    return { ok: false, errors: ["body must be a JSON object"] };
  }
  for (const key of Object.keys(body)) {
    if (!ALLOWED_FIELDS.includes(key)) errors.push(`unknown field: ${key}`);
  }
  for (const f of ALLOWED_FIELDS) {
    if (!(f in body)) errors.push(`missing field: ${f}`);
  }
  if (errors.length) return { ok: false, errors };

  const r = body;
  if (typeof r.config_id !== "string" || !/^sha256:[0-9a-f]{64}$/.test(r.config_id)) {
    errors.push("config_id must be 'sha256:<64 lowercase hex>'");
  }
  if (!isValidOperatorKey(r.operator_key)) errors.push("operator_key not in allowlist");
  if (!STAGES.includes(r.stage_reached)) errors.push("stage_reached not in allowlist");
  if (!RESULTS.includes(r.result)) errors.push("result must be 'success' or 'fail'");
  if (!Number.isInteger(r.handshake_ms) || r.handshake_ms < 0 || r.handshake_ms > 600000) {
    errors.push("handshake_ms must be an integer in [0, 600000]");
  }
  if (typeof r.hour_bucket !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}$/.test(r.hour_bucket)) {
    errors.push("hour_bucket must match YYYY-MM-DDTHH");
  } else {
    const t = Date.parse(r.hour_bucket + ":00:00Z");
    if (Number.isNaN(t)) errors.push("hour_bucket is not a real hour");
    else if (t - Date.now() > 2 * 3600e3) errors.push("hour_bucket is too far in the future");
  }
  if (errors.length) return { ok: false, errors };
  return {
    ok: true,
    report: {
      config_id: r.config_id,
      operator_key: r.operator_key,
      stage_reached: r.stage_reached,
      handshake_ms: r.handshake_ms,
      result: r.result,
      hour_bucket: r.hour_bucket,
    },
  };
}
