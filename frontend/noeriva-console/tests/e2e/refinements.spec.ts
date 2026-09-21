import { expect, test, type Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/overview");
  await page.getByLabel("用户名", { exact: true }).fill("admin");
  await page.getByLabel("密码", { exact: true }).fill("noeriva-local-demo");
  await page.getByRole("button", { name: "进入工作区", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "运行总览", exact: true }),
  ).toBeVisible();
}

test("selected windows show aggregate summaries and square daily heatmaps at desktop and mobile widths", async ({
  page,
}) => {
  await login(page);
  await page.getByLabel("流量时间窗口").selectOption("24");
  await expect(page.locator(".window-stats").first()).toContainText("区间平均");
  await expect(page.locator(".window-stats > div").first()).not.toContainText(
    "—",
  );
  await expect(page.getByText(/每 1 小时均值/)).toBeVisible();
  const response = page.waitForResponse(
    (r) =>
      r.url().includes("/metrics?") &&
      new URL(r.url()).searchParams.get("points") === "8",
  );
  await page.getByLabel("流量时间窗口").selectOption("168");
  const data = await (await response).json();
  expect(data.resolution).toBe(86400);
  expect(data.points).toHaveLength(7);
  expect(data.summary.sampleCount).toBeGreaterThan(7);
  await expect(page.getByText(/每 1 天均值/)).toBeVisible();
  await page.getByRole("link", { name: "core-asr-01", exact: true }).click();
  const heatmap = page.locator(".heatmap-square");
  await expect(heatmap.locator(".heatmap-hour")).toHaveCount(168);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    const geometry = await heatmap.evaluate((el) => {
      const r = el.getBoundingClientRect();
      const cells = [...el.querySelectorAll(".heatmap-hour")].map((c) => {
        const b = c.getBoundingClientRect();
        return Math.abs(b.width - b.height);
      });
      return {
        difference: Math.abs(r.width - r.height),
        cells,
        overflow: document.documentElement.scrollWidth > innerWidth,
      };
    });
    expect(geometry.difference).toBeLessThan(1);
    expect(Math.max(...geometry.cells)).toBeLessThan(1);
    expect(geometry.overflow).toBe(false);
  }
  await heatmap.locator(".heatmap-hour").first().click();
  await expect(page.locator(".chart-inspector")).toContainText("00:00");
});

test("manual interface selector filters the directory and carries the interface into history", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("link", { name: "网络接口", exact: true }).click();
  await page.getByLabel("查找接口设备", { exact: true }).fill("core-asr");
  await page
    .getByLabel("接口设备", { exact: true })
    .selectOption("core-asr-01");
  const interfaces = page.getByLabel("选择网络接口", { exact: true });
  await expect(interfaces.locator("option")).toHaveCount(2);
  const interfaceId = await interfaces
    .locator("option")
    .nth(1)
    .getAttribute("value");
  await interfaces.selectOption(interfaceId!);
  await expect(page).toHaveURL(/interfaceId=/);
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page.getByRole("link", { name: "带宽历史 ↗", exact: true }).click();
  await expect(page.locator("#heat-interface")).toHaveValue(interfaceId!);
  await page.locator("#heat-interface").selectOption("");
  await expect(page).not.toHaveURL(/interfaceId=/);
});

test("admin can cancel and then remove a newly registered disposable demo asset", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await page.getByRole("button", { name: "登记设备", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "登记设备", exact: true });
  const name = `delete-check-${Date.now()}`;
  await dialog.getByLabel("设备名称", { exact: true }).fill(name);
  await dialog.getByLabel("管理地址", { exact: true }).fill("192.0.2.88");
  await dialog.getByRole("button", { name: "登记设备", exact: true }).click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await page.getByLabel("搜索设备", { exact: true }).fill(name);
  await page.getByRole("button", { name: `删除 ${name}`, exact: true }).click();
  const removal = page.getByRole("dialog", { name: "删除资产", exact: true });
  await expect(removal).toContainText("保留历史观测");
  await removal.getByRole("button", { name: "取消", exact: true }).click();
  await expect(page.getByRole("link", { name, exact: true })).toBeVisible();
  await page.getByRole("button", { name: `删除 ${name}`, exact: true }).click();
  await removal.getByRole("button", { name: "确认删除", exact: true }).click();
  await expect(removal).not.toBeVisible();
  await expect(page.getByText("没有匹配的设备", { exact: true })).toBeVisible();
});

test("application window cards and charts remain balanced with twelve synthetic application rows", async ({
  page,
}) => {
  await login(page);
  const at = new Date().toISOString();
  await page.route("**/api/v1/applications/sources?*", (route) =>
    route.fulfill({
      json: {
        items: [
          {
            deviceId: "core-asr-01",
            deviceName: "Synthetic application fixture",
            siteId: "lab-a",
            revision: 1,
            enabled: false,
            intervalSeconds: 60,
            interfaceIndices: [8],
            maxRows: 128,
            status: "DISABLED",
            lastAttemptAt: at,
            lastSuccessAt: at,
            nextPollAt: null,
            errorCode: "",
            errorMessage: "",
            credentialRevision: 1,
            protocol: "CISCO_NBAR_SNMP",
            lastRowCount: 12,
            qualityFlags: ["SYNTHETIC_DATA"],
          },
        ],
        nextCursor: null,
        source: "SYNTHETIC_DATA",
        mode: "DEMO",
        asOf: at,
      },
    }),
  );
  await page.route("**/api/v1/applications/summary?*", (route) => {
    const url = new URL(route.request().url());
    const from = url.searchParams.get("from")!,
      to = url.searchParams.get("to")!;
    return route.fulfill({
      json: {
        deviceId: "core-asr-01",
        asOf: at,
        observedAt: at,
        source: "SYNTHETIC_DATA",
        mode: "DEMO",
        freshness: "FRESH",
        sampleRows: 120,
        totalApplications: 12,
        truncated: false,
        qualityFlags: ["SYNTHETIC_DATA"],
        from,
        to,
        totalBytes: "1200000000",
        inBytes: "800000000",
        outBytes: "400000000",
        meanBps: 2666666.67,
        coverage: 1,
        resolutionSeconds: 300,
        items: Array.from({ length: 12 }, (_, i) => ({
          application: `application-${i + 1}`,
          direction: i % 2 ? "OUT" : "IN",
          interfaceIndices: [8],
          derivedBps: 2400000 - i * 100000,
          cumulativeBytes: "100000000",
          coverage: 1,
          observationCount: 10,
          qualityFlags: [],
        })),
        trend: Array.from({ length: 12 }, (_, i) => ({
          timestamp: new Date(
            Date.parse(from) +
              ((Date.parse(to) - Date.parse(from)) * (i + 1)) / 12,
          ).toISOString(),
          inBps: 1800000 + i * 25000,
          outBps: 800000 - i * 10000,
          inBytes: "66666667",
          outBytes: "33333333",
          coverage: 1,
        })),
      },
    });
  });
  await page.getByRole("link", { name: "应用监测", exact: true }).click();
  await expect(page.locator("[data-application-total-bytes]")).toHaveText(
    "1.12 GiB",
  );
  await expect(page.locator(".application-charts svg")).toHaveCount(2);
  const charts = await page
    .locator(".application-charts .chart-canvas")
    .evaluateAll((elements) =>
      elements.map((e) => e.getBoundingClientRect().height),
    );
  expect(charts).toHaveLength(2);
  expect(Math.abs(charts[0]! - charts[1]!)).toBeLessThan(1);
  await page.screenshot({
    path: "/tmp/noeriva-refinements/application-statistics.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await expect(page.getByLabel("时间范围", { exact: true })).toBeVisible();
});
