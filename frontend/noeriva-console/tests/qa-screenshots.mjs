import { chromium, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
const output = process.env.NOERIVA_SCREENSHOTS || "/tmp/noeriva-ui-qa-20260906";
await mkdir(output, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
page.on("console", (message) => {
  if (message.type() === "error") errors.push(message.text());
});
await page.goto(
  process.env.NOERIVA_E2E_BASE_URL || "http://127.0.0.1:5174/overview",
);
await page.screenshot({ path: `${output}/login-1440.png`, fullPage: true });
await page
  .getByLabel("用户名", { exact: true })
  .fill(process.env.NOERIVA_E2E_USERNAME || "admin");
await page
  .getByLabel("密码", { exact: true })
  .fill(process.env.NOERIVA_E2E_PASSWORD || "noeriva-local-demo");
await page.getByRole("button", { name: "进入工作区", exact: true }).click();
await page
  .getByRole("heading", { name: "运行总览", exact: true })
  .waitFor({ timeout: 30000 });
await page.getByRole("link", { name: "core-asr-01", exact: true }).waitFor();
const findings = [];
for (const width of [390, 768, 1280, 1440, 1920]) {
  await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 });
  await page.screenshot({
    path: `${output}/overview-${width}.png`,
    fullPage: true,
  });
  findings.push({
    page: "overview",
    width,
    overflow: await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  });
}
await page.setViewportSize({ width: 1440, height: 1000 });
await page.getByRole("link", { name: "资产目录", exact: true }).click();
await page.getByRole("link", { name: "core-asr-01", exact: true }).waitFor();
await page.screenshot({ path: `${output}/devices-1440.png`, fullPage: true });
findings.push({
  page: "devices",
  rowHeights: await page
    .locator("tbody tr")
    .evaluateAll((rows) =>
      rows.map((row) => row.getBoundingClientRect().height),
    ),
});
await page.getByRole("link", { name: "core-asr-01", exact: true }).click();
await page.locator(".chart-canvas svg").first().waitFor();
await page.screenshot({ path: `${output}/device-1440.png`, fullPage: true });
await page.getByRole("link", { name: "资产目录", exact: true }).click();
await page.getByRole("link", { name: "基础设施拓扑", exact: true }).click();
await page.getByRole("button", { name: "列表替代", exact: true }).waitFor();
await page.locator(".chart-canvas svg").waitFor();
await expect(
  page.getByRole("button", { name: "启用物理", exact: true }),
).toBeVisible();
await page.locator('.chart-canvas svg path[fill="#e7effa"]').first().click();
await page.mouse.move(290, 150);
await page.screenshot({
  path: `${output}/connections-1440.png`,
  fullPage: true,
});
await page.getByRole("button", { name: "切换深色主题", exact: true }).click();
await expect(
  page.locator('.chart-canvas svg path[fill="#253d55"]'),
).toHaveCount(5);
await expect(
  page.getByRole("button", { name: "列表替代", exact: true }),
).toHaveCSS("background-color", "rgb(24, 35, 49)");
await page.screenshot({
  path: `${output}/connections-dark-1440.png`,
  fullPage: true,
});
await page.getByRole("link", { name: "打开设备详情", exact: true }).click();
await page.getByRole("button", { name: "传感器与监测", exact: true }).click();
await page.locator(".chart-canvas svg").first().waitFor();
await page.screenshot({
  path: `${output}/device-dark-1440.png`,
  fullPage: true,
});
await page.setViewportSize({ width: 390, height: 844 });
await page.screenshot({
  path: `${output}/device-dark-390.png`,
  fullPage: true,
});
findings.push({
  page: "device",
  width: 390,
  overflow: await page.evaluate(
    () => document.documentElement.scrollWidth > innerWidth,
  ),
});
await writeFile(
  `${output}/checks.json`,
  JSON.stringify({ errors, findings }, null, 2),
);
console.log(JSON.stringify({ output, errors, findings }, null, 2));
await browser.close();
