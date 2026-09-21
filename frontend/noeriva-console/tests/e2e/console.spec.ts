import { expect, test, type Page, type Locator } from "@playwright/test";

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

// Inspect the pixels the browser actually renders; no application internals or test-only hooks.
async function graphPixels(canvas: Locator) {
  return canvas.evaluate((element) => {
    const surface = element as HTMLCanvasElement;
    const context = surface.getContext("2d")!;
    const { width, height } = surface;
    const pixels = context.getImageData(0, 0, width, height).data;
    const palette = new Set([
      "201,77,85",
      "215,123,47",
      "197,163,53",
      "50,148,106",
      "240,117,121",
      "241,166,91",
      "224,203,108",
      "101,199,154",
    ]);
    const mask = new Uint8Array(width * height);
    for (let i = 0; i < mask.length; i++) {
      const offset = i * 4;
      if (
        pixels[offset + 3]! > 180 &&
        palette.has(
          `${pixels[offset]},${pixels[offset + 1]},${pixels[offset + 2]}`,
        )
      )
        mask[i] = 1;
    }
    const scaleX = surface.clientWidth / width,
      scaleY = surface.clientHeight / height;
    const points: {
      x: number;
      y: number;
      left: number;
      top: number;
      right: number;
      bottom: number;
      color: string;
    }[] = [];
    for (let i = 0; i < mask.length; i++) {
      if (!mask[i]) continue;
      const queue = [i];
      mask[i] = 0;
      let left = width,
        right = 0,
        top = height,
        bottom = 0;
      for (let index = 0; index < queue.length; index++) {
        const point = queue[index]!,
          x = point % width,
          y = Math.floor(point / width);
        left = Math.min(left, x);
        right = Math.max(right, x);
        top = Math.min(top, y);
        bottom = Math.max(bottom, y);
        for (const next of [
          point - 1,
          point + 1,
          point - width,
          point + width,
        ]) {
          if (next >= 0 && next < mask.length && mask[next]) {
            mask[next] = 0;
            queue.push(next);
          }
        }
      }
      if (queue.length < 5) continue;
      points.push({
        x: ((left + right) / 2) * scaleX,
        y: ((top + bottom) / 2) * scaleY,
        left: left * scaleX,
        right: right * scaleX,
        top: top * scaleY,
        bottom: bottom * scaleY,
        color: `${pixels[i * 4]},${pixels[i * 4 + 1]},${pixels[i * 4 + 2]}`,
      });
    }
    return {
      points,
      width: surface.clientWidth,
      height: surface.clientHeight,
      minX: Math.min(...points.map((p) => p.left)),
      maxX: Math.max(...points.map((p) => p.right)),
      minY: Math.min(...points.map((p) => p.top)),
      maxY: Math.max(...points.map((p) => p.bottom)),
      outside: points.filter(
        (p) =>
          p.left < 24 ||
          p.top < 24 ||
          p.right > surface.clientWidth - 24 ||
          p.bottom > surface.clientHeight - 24,
      ).length,
    };
  });
}

test("native graph click, drag, zoom and double click are operational", async ({
  page,
}) => {
  await login(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.getByRole("link", { name: "基础设施拓扑", exact: true }).click();
  await expect(page.getByLabel("终端接入推断", { exact: true })).toBeChecked();
  await expect(
    page.getByLabel("三层邻居推断", { exact: true }),
  ).not.toBeChecked();
  const scopedTopology = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return (
      url.pathname === "/api/v1/topology" &&
      url.searchParams.has("deviceId") &&
      response.ok()
    );
  });
  await page
    .getByLabel("限定设备邻域", { exact: true })
    .selectOption({ label: "lab-sw-01 · 邻域" });
  await scopedTopology;
  const graph = page.locator(".graph-area .topology-canvas"),
    canvas = graph.locator("canvas");
  await expect(canvas).toBeVisible();
  await expect(graph).toHaveAttribute("data-initial-graph-fit", "done", {
    timeout: 25_000,
  });
  await page.getByRole("button", { name: "固定布局", exact: true }).click();
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(graph).toHaveAttribute("data-graph-fit", "done");
  const initial = await graphPixels(canvas);
  const node = initial.points[0]!;
  expect(node).toBeTruthy();
  await canvas.click({ position: { x: node.x, y: node.y } });
  await expect(
    page.getByRole("link", { name: "打开设备详情", exact: true }),
  ).toBeVisible();
  const destination = await page
    .getByRole("link", { name: "打开设备详情", exact: true })
    .getAttribute("href");
  const box = await canvas.boundingBox();
  if (!box) throw new Error("Graph canvas unavailable");
  await page.mouse.move(box.x + node.x, box.y + node.y);
  await page.mouse.down();
  await page.mouse.move(box.x + node.x + 40, box.y + node.y + 25, { steps: 8 });
  await page.mouse.up();
  await expect
    .poll(async () => {
      const { points } = await graphPixels(canvas);
      return Math.min(
        ...points
          .filter((point) => point.color === node.color)
          .map((point) =>
            Math.hypot(point.x - node.x - 40, point.y - node.y - 25),
          ),
      );
    })
    .toBeLessThan(5);
  // vis-network deduplicates gesture starts within50ms; separate these two complete drags.
  await page.waitForTimeout(60);
  const beforePan = await graphPixels(canvas);
  await page.mouse.move(box.x + 20, box.y + 20);
  await page.mouse.down();
  await page.mouse.move(box.x + 44, box.y + 36, { steps: 6 });
  await page.mouse.up();
  await expect
    .poll(async () => (await graphPixels(canvas)).minX - beforePan.minX)
    .toBeCloseTo(24, 0);
  await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await page.getByRole("button", { name: "缩小连接图", exact: true }).click();
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(graph).toHaveAttribute("data-graph-fit", "done");
  // Pick a rendered edge away from nodes and verify native edge hit testing.
  const edgePoint = await canvas.evaluate((element) => {
    const surface = element as HTMLCanvasElement,
      ctx = surface.getContext("2d")!;
    const data = ctx.getImageData(0, 0, surface.width, surface.height).data;
    for (let y = 30; y < surface.height - 30; y += 3)
      for (let x = 30; x < surface.width - 30; x += 3) {
        const i = (y * surface.width + x) * 4;
        if (
          data[i] === 130 &&
          data[i + 1] === 148 &&
          data[i + 2] === 168 &&
          data[i + 3]! > 160
        )
          return {
            x: (x * surface.clientWidth) / surface.width,
            y: (y * surface.clientHeight) / surface.height,
          };
      }
    return null;
  });
  expect(edgePoint).toBeTruthy();
  await canvas.click({ position: edgePoint! });
  await expect(page.locator(".graph-inspector")).toContainText("连线检查器");
  const iconContrastDuringThemeChange = page.evaluate(
    () =>
      new Promise<number[]>((resolve) => {
        const samples: number[] = [];
        const luminance = (color: string) => {
          const rgb = color
            .match(/[\d.]+/g)!
            .slice(0, 3)
            .map((value) => {
              const c = Number(value) / 255;
              return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
            });
          return rgb[0]! * 0.2126 + rgb[1]! * 0.7152 + rgb[2]! * 0.0722;
        };
        const observer = new MutationObserver(() => {
          observer.disconnect();
          const start = performance.now();
          const sample = () => {
            document
              .querySelectorAll(".graph-toolbar .icon-btn:not(:disabled)")
              .forEach((button) => {
                const foreground = luminance(
                  getComputedStyle(button.querySelector("svg")!).stroke,
                );
                const background = luminance(
                  getComputedStyle(button.closest(".panel")!).backgroundColor,
                );
                samples.push(
                  (Math.max(foreground, background) + 0.05) /
                    (Math.min(foreground, background) + 0.05),
                );
              });
            if (performance.now() - start < 200) requestAnimationFrame(sample);
            else resolve(samples);
          };
          sample();
        });
        observer.observe(document.documentElement, {
          attributes: true,
          attributeFilter: ["data-theme"],
        });
      }),
  );
  await page.getByRole("button", { name: "切换深色主题", exact: true }).click();
  const themeContrast = await iconContrastDuringThemeChange;
  expect(themeContrast.length).toBeGreaterThan(2);
  expect(Math.min(...themeContrast)).toBeGreaterThanOrEqual(3);

  await expect
    .poll(
      async () =>
        (await graphPixels(canvas)).points.filter((point) =>
          ["240,117,121", "241,166,91", "224,203,108", "101,199,154"].includes(
            point.color,
          ),
        ).length,
    )
    .toBeGreaterThan(0);
  const contrasts = await page
    .locator(".graph-toolbar .btn, .graph-inspector .btn")
    .evaluateAll((buttons) => {
      const luminance = (color: string) => {
        const channels = color
          .match(/[\d.]+/g)!
          .slice(0, 3)
          .map((value) => {
            const c = Number(value) / 255;
            return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
          });
        return (
          channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722
        );
      };
      return buttons.map((button) => {
        const style = getComputedStyle(button),
          front = luminance(style.color),
          back = luminance(style.backgroundColor);
        return (Math.max(front, back) + 0.05) / (Math.min(front, back) + 0.05);
      });
    });
  expect(Math.min(...contrasts)).toBeGreaterThanOrEqual(4.5);
  await page.screenshot({
    path: "/tmp/noeriva-topology-dark.png",
    fullPage: true,
  });
  const darkNode = (await graphPixels(canvas)).points.find((point) =>
    ["240,117,121", "241,166,91", "224,203,108", "101,199,154"].includes(
      point.color,
    ),
  )!;
  await canvas.dblclick({ position: { x: darkNode.x, y: darkNode.y } });
  // Demo nodes are registered; verify native double-click navigation to a real detail page.
  await expect(page).toHaveURL(/\/devices\/[^/?]+/);
  expect(destination).toMatch(/^\/devices\//);
  expect(errors).toEqual([]);
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
  const graph = page.locator(".graph-area .topology-canvas"),
    canvas = graph.locator("canvas");
  await expect(graph).toHaveAttribute("data-initial-graph-fit", "done", {
    timeout: 25_000,
  });
  const fitted = await graphPixels(canvas);
  expect(fitted.outside).toBe(0);
  expect(fitted.points.length).toBeGreaterThanOrEqual(50);
  for (let i = 0; i < 5; i++)
    await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await expect
    .poll(async () => (await graphPixels(canvas)).outside)
    .toBeGreaterThan(0);
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(graph).toHaveAttribute("data-graph-fit", "done");
  expect((await graphPixels(canvas)).outside).toBe(0);
  await page.getByRole("button", { name: "放大连接图", exact: true }).click();
  await expect
    .poll(async () => {
      const current = await graphPixels(canvas);
      return current.maxY - current.minY;
    })
    .toBeGreaterThan(fitted.maxY - fitted.minY + 10);
  const manuallyZoomed = await graphPixels(canvas);
  const refresh = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/v1/topology" && response.ok(),
  );
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await refresh;
  await expect(graph).toHaveAttribute("data-initial-graph-fit", "cancelled");
  const refreshed = await graphPixels(canvas);
  expect(refreshed.maxY - refreshed.minY).toBeCloseTo(
    manuallyZoomed.maxY - manuallyZoomed.minY,
    0,
  );
  await page.getByRole("button", { name: "列表替代", exact: true }).click();
  await expect(page.locator(".graph-list .graph-node-button")).toHaveCount(
    fitted.points.length,
  );
  await page.getByRole("button", { name: "图形视图", exact: true }).click();
  await expect
    .poll(() =>
      canvas.evaluate((element) => (element as HTMLCanvasElement).width),
    )
    .toBeGreaterThan(0);
  const restored = await graphPixels(canvas);
  expect(restored.maxY - restored.minY).toBeCloseTo(
    manuallyZoomed.maxY - manuallyZoomed.minY,
    0,
  );
  await page.getByRole("button", { name: "适应视图", exact: true }).click();
  await expect(graph).toHaveAttribute("data-graph-fit", "done");
  await page.screenshot({
    path: "/tmp/noeriva-topology-dense.png",
    fullPage: true,
  });
});

test("reduced motion graph stabilizes into a draggable static view", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await login(page);
  await page.getByRole("link", { name: "基础设施拓扑", exact: true }).click();
  const graph = page.locator(".graph-area .topology-canvas"),
    canvas = graph.locator("canvas");
  await expect(
    page.getByRole("button", { name: "静态布局", exact: true }),
  ).toBeDisabled();
  await expect(graph).toHaveAttribute("data-initial-graph-fit", "done", {
    timeout: 25_000,
  });
  const initial = await graphPixels(canvas);
  expect(initial.points.length).toBeGreaterThan(0);
  expect(initial.outside).toBe(0);
  const node = initial.points.find(
    (point) =>
      point.x > 40 &&
      point.x < initial.width - 80 &&
      point.y > 40 &&
      point.y < initial.height - 80,
  )!;
  const box = await canvas.boundingBox();
  if (!box) throw new Error("Graph canvas unavailable");
  await page.mouse.move(box.x + node.x, box.y + node.y);
  await page.mouse.down();
  await page.mouse.move(box.x + node.x + 30, box.y + node.y + 20, { steps: 6 });
  await page.mouse.up();
  await expect
    .poll(async () =>
      Math.min(
        ...(await graphPixels(canvas)).points.map((point) =>
          Math.hypot(point.x - node.x - 30, point.y - node.y - 20),
        ),
      ),
    )
    .toBeLessThan(5);
});
