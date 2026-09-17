import { expect, test, type Page } from "@playwright/test";

async function login(
  page: Page,
  username = process.env.NOERIVA_E2E_USERNAME || "admin",
) {
  await page.goto("/overview");
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page
    .getByLabel("密码", { exact: true })
    .fill(process.env.NOERIVA_E2E_PASSWORD || "noeriva-local-demo");
  await page.getByRole("button", { name: "进入工作区", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "运行总览", exact: true }),
  ).toBeVisible();
}

test("real API device navigation, charts, graph, source inspection and keyboard search", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page);
  await expect(page.getByText("合成演示", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await page
    .getByRole("textbox", { name: "搜索设备", exact: true })
    .fill("core-asr-01");
  await expect(
    page.getByRole("link", { name: "core-asr-01", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "core-asr-01", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "core-asr-01", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "七日带宽热力图" }),
  ).toBeVisible();
  await expect(page.locator(".chart-canvas svg").first()).toBeVisible();
  await page.getByRole("button", { name: "接口与带宽", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "设备接口", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "资产关系", exact: true }).click();
  await page.getByRole("button", { name: "列表替代", exact: true }).click();
  await page
    .locator(".graph-list .graph-node-button")
    .filter({ hasText: "core-asr-01" })
    .click();
  await expect(page.getByRole("link", { name: "打开设备详情" })).toBeVisible();
  await page.getByRole("button", { name: "事件与告警", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "设备活动", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: /检查事件/ })
    .first()
    .click();
  await expect(
    page.getByRole("dialog", { name: "事件来源检查" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await page.keyboard.press("Control+k");
  await page.getByLabel("全局搜索").fill("compute-07");
  await expect(
    page
      .getByRole("dialog", { name: "搜索资产、端口、IP、事件" })
      .getByRole("link")
      .first(),
  ).toBeVisible();
  await page.getByLabel("全局搜索").press("ArrowDown");
  await page.getByLabel("全局搜索").press("Enter");
  await expect(
    page.getByRole("dialog", { name: "搜索资产、端口、IP、事件" }),
  ).not.toBeVisible();
  expect(errors).toEqual([]);
});

test("asset registration writes a real unknown-state record", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await page.getByRole("button", { name: "登记设备", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "登记设备", exact: true });
  const name = `e2e-host-${Date.now()}`;
  await dialog.getByLabel("设备名称", { exact: true }).fill(name);
  await dialog.getByLabel("管理地址", { exact: true }).fill("192.0.2.87");
  await dialog.getByRole("button", { name: "登记设备", exact: true }).click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/assets\/[^/?]+\?tab=access$/);
  await page.getByRole("button", { name: "概览", exact: true }).click();
  await expect(page.getByText("尚无当前监测值", { exact: true })).toBeVisible();
  await expect(
    page.locator(".page-heading .status").filter({ hasText: "未知" }),
  ).toHaveCount(2);
});

test("viewer has a read-only console and credentials never persist", async ({
  page,
}) => {
  await login(page, "viewer");
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "登记设备", exact: true }),
  ).toHaveCount(0);
  const storage = await page.evaluate(() => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }));
  expect(JSON.stringify(storage)).not.toMatch(
    /noeriva-local-demo|Bearer|Basic|accessToken/,
  );
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "登录工作区", exact: true }),
  ).toBeVisible();
});

test("mobile drawer, responsive shell and dark theme remain operable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  await page.getByRole("button", { name: "打开主导航", exact: true }).click();
  const drawer = page.getByRole("dialog", { name: "主导航", exact: true });
  await expect(drawer).toBeVisible();
  await drawer.getByRole("link", { name: "资产目录", exact: true }).click();
  await expect(drawer).not.toBeVisible();
  await expect(
    page.getByRole("heading", { name: "资产目录", exact: true }),
  ).toBeVisible();
  const hasOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(hasOverflow).toBe(false);
  await page.getByRole("button", { name: "切换深色主题", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "打开主导航", exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(drawer).not.toBeVisible();
});

test("alert acknowledgement persists separately from problem recovery", async ({
  page,
}) => {
  await login(page);
  await page
    .locator("nav")
    .getByRole("link", { name: /^告警队列(?:，|$)/ })
    .click();
  await expect(
    page.getByRole("heading", { name: "告警队列", exact: true }),
  ).toBeVisible();
  const buttons = page.getByRole("button", { name: "确认异常", exact: true });
  // A fresh DEMO run contains open alerts; never silently skip the mutation when loading races navigation.
  await expect(buttons.first()).toBeVisible();
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes("/acknowledge") &&
      response.request().method() === "POST",
  );
  await buttons.first().click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ state: "ACKNOWLEDGED" });
  await expect(page.locator('main .notice[role="status"]')).toContainText(
    "问题状态未被标记为恢复",
  );
  await page.getByLabel("状态", { exact: true }).selectOption("ACKNOWLEDGED");
  await expect(
    page.getByRole("cell", { name: "已确认未恢复", exact: true }).first(),
  ).toBeVisible();
});

test("native graph click, drag, zoom and double click are operational", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("link", { name: "资产目录", exact: true }).click();
  await page.getByRole("link", { name: "基础设施拓扑", exact: true }).click();
  await expect(page.getByLabel("终端接入推断", { exact: true })).toBeChecked();
  await expect(
    page.getByLabel("三层邻居推断", { exact: true }),
  ).not.toBeChecked();
  const nodeColors = [
    "#c94d55",
    "#d77b2f",
    "#c5a335",
    "#32946a",
    "#f07579",
    "#f1a65b",
    "#e0cb6c",
    "#65c79a",
  ];
  const nodeSelector = nodeColors
    .map((color) => `.graph-area .chart-canvas svg path[fill="${color}"]`)
    .join(", ");
  const node = page.locator(nodeSelector).first();
  await expect(node).toBeVisible();
  const freeze = page.getByRole("button", { name: "圆形排列", exact: true });
  await expect(freeze).toBeVisible();
  await freeze.click();
  await expect(page.locator(".graph-area .chart-canvas")).toHaveAttribute(
    "data-graph-fit",
    "done",
  );
  await node.click();
  await expect(
    page.getByRole("link", { name: "打开设备详情", exact: true }),
  ).toBeVisible();

  await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await page.getByRole("button", { name: "缩小连接图", exact: true }).click();
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(page.locator(".graph-area .chart-canvas")).toHaveAttribute(
    "data-graph-fit",
    "done",
  );
  // Keep the same glyph: hover/emphasis may reorder SVG paths, so first() is not a node identity after dragging.
  const draggedNode = await node.elementHandle();
  if (!draggedNode) throw new Error("Graph node unavailable");
  const bounds = await draggedNode.boundingBox();
  if (!bounds) throw new Error("Graph node bounds unavailable");
  await page.mouse.move(
    bounds.x + bounds.width / 2,
    bounds.y + bounds.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    bounds.x + bounds.width / 2 + 40,
    bounds.y + bounds.height / 2 + 25,
    { steps: 8 },
  );
  await page.mouse.up();
  const draggedBounds = await draggedNode.boundingBox();
  if (!draggedBounds) throw new Error("Dragged graph node unavailable");
  const draggedDistance = Math.hypot(
    draggedBounds.x - bounds.x,
    draggedBounds.y - bounds.y,
  );
  expect(draggedDistance).toBeGreaterThan(1);
  expect(draggedDistance).toBeLessThan(100);
  await draggedNode.click();
  await page.getByRole("button", { name: "切换深色主题", exact: true }).click();
  const contrasts = await page
    .locator(".graph-toolbar .btn, .graph-inspector .btn")
    .evaluateAll((buttons) => {
      const luminance = (color: string) => {
        const channels = color
          .match(/[\d.]+/g)!
          .slice(0, 3)
          .map((value) => {
            const channel = Number(value) / 255;
            return channel <= 0.04045
              ? channel / 12.92
              : ((channel + 0.055) / 1.055) ** 2.4;
          });
        return (
          channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722
        );
      };
      return buttons.map((button) => {
        const style = getComputedStyle(button);
        const foreground = luminance(style.color),
          background = luminance(style.backgroundColor);
        return (
          (Math.max(foreground, background) + 0.05) /
          (Math.min(foreground, background) + 0.05)
        );
      });
    });
  expect(contrasts.length).toBeGreaterThanOrEqual(4);
  expect(Math.min(...contrasts)).toBeGreaterThanOrEqual(4.5);
  expect(await page.locator(nodeSelector).count()).toBeGreaterThan(0);
  await expect(page.locator(".graph-area svg image")).toHaveCount(0);
  const destination = await page
    .getByRole("link", { name: "打开设备详情", exact: true })
    .getAttribute("href");
  await node.dblclick();
  await expect(page).toHaveURL(new RegExp(destination + "(?:\\?.*)?$"));
});

test("dense graph fits real glyph bounds and preserves manual zoom through refresh", async ({
  page,
}) => {
  test.setTimeout(60_000);
  await login(page);
  await expect(page.getByText("合成演示", { exact: true })).toBeVisible();
  const credentials = Buffer.from(
    `${process.env.NOERIVA_E2E_USERNAME || "admin"}:${process.env.NOERIVA_E2E_PASSWORD || "noeriva-local-demo"}`,
  ).toString("base64");
  const sessionResponse = await page.request.get("/api/v1/session", {
    headers: { Authorization: `Basic ${credentials}` },
  });
  expect(sessionResponse.ok()).toBe(true);
  const session = await sessionResponse.json();
  expect(session.mode).toBe("DEMO");
  const headers = {
    Authorization: session.accessToken
      ? `Bearer ${session.accessToken}`
      : `Basic ${credentials}`,
    "X-Noeriva-Request": "1",
  };
  for (let index = 0; index < 45; index++) {
    const response = await page.request.post("/api/v1/devices", {
      headers,
      data: {
        name: `fit-terminal-${index}`,
        type: "HOST",
        siteId: "lab-a",
        vendor: "",
        model: "",
        managementAddress: `192.0.2.${index + 120}`,
      },
    });
    expect(response.ok()).toBe(true);
  }
  await page.getByRole("link", { name: "基础设施拓扑", exact: true }).click();
  const canvas = page.locator(".graph-area .chart-canvas");
  await expect(canvas).toHaveAttribute("data-initial-graph-fit", "done", {
    timeout: 25_000,
  });
  const colors = [
    "#c94d55",
    "#d77b2f",
    "#c5a335",
    "#32946a",
    "#f07579",
    "#f1a65b",
    "#e0cb6c",
    "#65c79a",
  ];
  const measure = () =>
    canvas.locator("svg").evaluate((svg, colors) => {
      const viewport = svg.getBoundingClientRect();
      const nodes = [
        ...svg.querySelectorAll<SVGGraphicsElement>(
          "path,circle,rect,polygon,ellipse",
        ),
      ]
        .filter((node) =>
          colors.includes(node.getAttribute("fill") || node.style.fill),
        )
        .map((node) => node.getBoundingClientRect());
      return {
        count: nodes.length,
        outside: nodes.filter(
          (box) =>
            box.left < viewport.left + 24 ||
            box.top < viewport.top + 24 ||
            box.right > viewport.right - 24 ||
            box.bottom > viewport.bottom - 24,
        ).length,
        minX: Math.min(...nodes.map((box) => box.left - viewport.left)),
        minY: Math.min(...nodes.map((box) => box.top - viewport.top)),
        maxX: Math.max(...nodes.map((box) => box.right - viewport.left)),
        maxY: Math.max(...nodes.map((box) => box.bottom - viewport.top)),
        width: viewport.width,
        height: viewport.height,
      };
    }, colors);
  await expect.poll(async () => (await measure()).outside).toBe(0);
  expect((await measure()).count).toBeGreaterThanOrEqual(50);
  await expect(
    page.getByRole("button", { name: "圆形排列", exact: true }),
  ).toBeVisible();
  for (let index = 0; index < 4; index++)
    await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await expect.poll(async () => (await measure()).outside).toBeGreaterThan(0);
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(canvas).toHaveAttribute("data-graph-fit", "done");
  await expect.poll(async () => (await measure()).outside).toBe(0);
  const fitted = await measure();
  await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await expect
    .poll(async () => (await measure()).maxY - (await measure()).minY)
    .toBeGreaterThan(fitted.maxY - fitted.minY + 10);
  const manuallyZoomed = await measure();
  const refresh = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/v1/topology" && response.ok(),
  );
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await refresh;
  await expect(canvas).toHaveAttribute("data-initial-graph-fit", "cancelled");
  const afterRefresh = await measure();
  expect(afterRefresh.maxY - afterRefresh.minY).toBeCloseTo(
    manuallyZoomed.maxY - manuallyZoomed.minY,
    0,
  );
  console.log(
    JSON.stringify({
      graphFitEvidence: {
        initialAndManualFitOutside: fitted.outside,
        nodeCount: fitted.count,
        paddingPixels: 24,
        width: fitted.width,
        height: fitted.height,
        glyphBounds: [fitted.minX, fitted.minY, fitted.maxX, fitted.maxY],
        manualViewPreservedOnRefresh: true,
      },
    }),
  );
});
