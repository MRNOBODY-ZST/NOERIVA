/** Budgets produce readable averages, e.g. 24 hourly bins or seven daily bins. */
export function trendPoints(hours: number): number {
  if (hours <= 0.25) return 16;
  if (hours <= 1) return 13;
  if (hours <= 6) return 25;
  if (hours <= 24) return 25;
  return 8;
}
export function bucketLabel(seconds: number | undefined): string {
  if (!seconds) return "分时均值";
  if (seconds >= 86400) return `每 ${Math.round(seconds / 86400)} 天均值`;
  if (seconds >= 3600) return `每 ${Math.round(seconds / 3600)} 小时均值`;
  return `每 ${Math.max(1, Math.round(seconds / 60))} 分钟均值`;
}
