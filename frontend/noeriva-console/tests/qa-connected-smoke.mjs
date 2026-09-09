import { chromium, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";

const root = new URL("../../../", import.meta.url);
const env = await readFile(new URL(".env", root), "utf8");
const credential = env
  .split(/\r?\n/)
  .find((line) => line.startsWith("NOERIVA_BOOTSTRAP_PASSWORD="))
  ?.split("=")
  .slice(1)
  .join("=")
  .replace(/^['"]|['"]$/g, "");
if (!credential) throw new Error("Local bootstrap credential unavailable");
const fixture = JSON.parse(
  await readFile(
    new URL("docs/implementation/rollup-job-smoke.json", root),
    "utf8",
  ),
);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
let stage = "login";
const errors = [];
page.on("pageerror", (error) => errors.push(error.name));
page.on("console", (message) => {
  if (message.type() === "error") errors.push("console.error");
});
const result = {
  status: "FAIL",
  mode: "CONNECTED_COMPOSE",
  synthetic: true,
  deviceId: fixture.deviceId,
  interfaceId: fixture.interfaceId,
};
try {
  await page.goto("http://127.0.0.1:18000/devices");
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill(credential);
  const sessionResponse = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/session"),
  );
  await page.getByRole("button", { name: "进入工作区", exact: true }).click();
  const session = await (await sessionResponse).json();
  if (session.mode !== "CONNECTED")
    throw new Error("Unexpected deployment mode");
  stage = "inventory";
  await expect(
    page.getByRole("heading", { name: "资产目录", exact: true }),
  ).toBeVisible();
  const asset = await (
    await page.request.get(
      `http://127.0.0.1:18000/api/v1/devices/${fixture.deviceId}`,
      {
        headers: { Authorization: `Bearer ${session.accessToken}` },
      },
    )
  ).json();
  await page
    .getByRole("textbox", { name: "搜索设备", exact: true })
    .fill(asset.name);
  await expect(
    page.locator(`a.device-name[href="/devices/${fixture.deviceId}"]`),
  ).toBeVisible();
  const responsePromise = page.waitForResponse((response) =>
    response
      .url()
      .includes(`/interfaces/${fixture.interfaceId}/bandwidth/heatmap`),
  );
  await page
    .locator(`a.device-name[href="/devices/${fixture.deviceId}"]`)
    .click();
  stage = "heatmap";
  const response = await responsePromise;
  if (response.status() !== 200) throw new Error("Heatmap request failed");
  const heatmap = await response.json();
  const observed = heatmap.cells.filter((cell) => cell.value != null);
  if (
    observed.length !== 1 ||
    observed[0].value !== 800 ||
    heatmap.cells.length !== 168
  )
    throw new Error("Unexpected rollup result");
  await expect(
    page.getByRole("heading", { name: "七日带宽热力图", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".chart-canvas.square svg")).toBeVisible();
  await page
    .locator("summary")
    .filter({ hasText: "查看完整 168 个时段的数据表" })
    .click();
  await expect(
    page.getByRole("cell", { name: "800 bit/s", exact: true }),
  ).toBeVisible();
  stage = "client-integrity";
  const storage = await page.evaluate(() => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }));
  if (/Bearer|Basic|accessToken/.test(JSON.stringify(storage)))
    throw new Error("Unexpected persisted authorization");
  if (errors.length) throw new Error("Browser rendering error");
  Object.assign(result, {
    status: "PASS",
    observedCells: observed.length,
    totalCells: heatmap.cells.length,
    averageBps: observed[0].value,
    source: heatmap.source,
    checks: [
      "production Nginx SPA and relative API proxy",
      "in-memory login session reports CONNECTED",
      "inventory loads existing synthetic asset",
      "real heatmap query returns one 800 bps hour plus 167 missing/future cells",
      "ECharts heatmap renders and accessible table shows 800 bit/s",
      "no browser authorization persistence",
      "no console or page errors",
    ],
    verifiedAt: new Date().toISOString(),
  });
} catch {
  Object.assign(result, { failedStage: stage, browserErrorKinds: errors });
  process.exitCode = 1;
} finally {
  await browser.close();
}
await writeFile(
  new URL("docs/frontend/connected-browser-smoke.json", root),
  JSON.stringify(result, null, 2) + "\n",
);
console.log(JSON.stringify(result, null, 2));
