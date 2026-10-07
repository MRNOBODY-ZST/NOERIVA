import type { HeatmapMatrixCell } from "../services/types";

// Continuous palette from Apache ECharts' matrix-covariance example.
export const matrixPalette = [
  "#313695",
  "#4575b4",
  "#74add1",
  "#abd9e9",
  "#e0f3f8",
  "#ffffbf",
  "#fee090",
  "#fdae61",
  "#f46d43",
  "#d73027",
  "#a50026",
];
export function hasHeatValue(
  cell: Pick<HeatmapMatrixCell, "value" | "state">,
): boolean {
  return (
    cell.value !== null &&
    Number.isFinite(cell.value) &&
    cell.value >= 0 &&
    !["MISSING", "FUTURE", "DST_MISSING"].includes(cell.state)
  );
}
export function heatmapMatrix(cells: HeatmapMatrixCell[], size: 7 | 28) {
  const points = [...cells]
    .sort((a, b) => a.index - b.index)
    .map((cell) => ({
      x: cell.index % size,
      y: Math.floor(cell.index / size),
      cell,
    }));
  const values = cells.filter(hasHeatValue).map((c) => c.value!);
  const maximum = Math.max(1, ...values);
  const minimum = values.length ? Math.min(...values) : 0;
  return {
    size,
    coordinates: Array.from({ length: size }, (_, i) => String(i)),
    points,
    minimum: minimum < maximum ? minimum : 0,
    maximum,
  };
}
export function heatmapGeometry(width: number, height: number, size: 7 | 28) {
  const side = Math.max(1, Math.min(width - 20, height - 64));
  return {
    cellSize: side / size,
    width: side,
    height: side,
    left: (width - side) / 2,
    top: 48 + (height - 64 - side) / 2,
  };
}
export function heatStateColor(
  cell: Pick<HeatmapMatrixCell, "state">,
  dark: boolean,
) {
  return cell.state === "FUTURE"
    ? dark
      ? "#172331"
      : "#ffffff"
    : dark
      ? "#374553"
      : "#e4e8ed";
}
