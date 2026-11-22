// Aggregation of anonymous client reports (doc §4, as corrected 2026-11-21:
// clients are the ONLY dead/alive authority — there is no server prober).
//
// A config is excluded for ONE operator only on aggregated evidence:
//   >= MIN_REPORTS reports inside the window AND failure rate >= MIN_FAIL_RATE.
// Scores are the plain client-observed success rate, emitted per operator so
// the bundle can rank configs differently on mci vs irancell etc.

export const THRESHOLDS = {
  windowHours: 24, // only hour-buckets inside this window count
  minReports: 10, // minimum reports in window before an exclusion can trigger
  minFailRate: 0.8, // >= 80% failures in window -> excluded for that operator
  minReportsForScore: 3, // below this, no operator_score is emitted
};

function bucketTimeMs(hourBucket) {
  return Date.parse(hourBucket + ":00:00Z");
}

export function computeAggregation(counters, now = new Date()) {
  const windowStart = now.getTime() - THRESHOLDS.windowHours * 3600e3;
  const perOperator = {};
  const details = [];

  for (const [key, entry] of Object.entries(counters)) {
    const sep = key.lastIndexOf("|");
    const configId = key.slice(0, sep);
    const operatorKey = key.slice(sep + 1);

    let success = 0;
    let fail = 0;
    for (const [bucket, counts] of Object.entries(entry.buckets || {})) {
      const t = bucketTimeMs(bucket);
      if (!Number.isNaN(t) && t >= windowStart && t <= now.getTime() + 3600e3) {
        success += counts.success || 0;
        fail += counts.fail || 0;
      }
    }
    const total = success + fail;
    if (total === 0) continue;
    const failRate = fail / total;

    const op = perOperator[operatorKey] || (perOperator[operatorKey] = { scores: {}, excluded_config_ids: [] });
    if (total >= THRESHOLDS.minReportsForScore) {
      op.scores[configId] = Math.round((success / total) * 1000) / 1000;
    }
    const excluded = total >= THRESHOLDS.minReports && failRate >= THRESHOLDS.minFailRate;
    if (excluded && !op.excluded_config_ids.includes(configId)) op.excluded_config_ids.push(configId);

    details.push({
      config_id: configId,
      operator_key: operatorKey,
      window_reports: total,
      window_success: success,
      window_fail: fail,
      fail_rate: Math.round(failRate * 1000) / 1000,
      excluded,
    });
  }

  return {
    generated_at: now.toISOString(),
    authority: "client-reports-only",
    thresholds: { ...THRESHOLDS },
    per_operator: perOperator,
    details,
  };
}
