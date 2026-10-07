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

for (const flow of [
  { page: "资产目录", api: "**/api/v1/devices?*" },
  { page: "网络接口", api: "**/api/v1/workspace/interfaces?*" },
  { page: "监测与传感器", api: "**/api/v1/workspace/monitoring?*" },
  { page: "应用监测", api: "**/api/v1/applications/sources?*" },
]) {
  test(`${flow.page} 401 goes straight to the home login without the unavailable-data panel`, async ({
    page,
  }) => {
    await login(page);
    await page.route(flow.api, (route) =>
      route.fulfill({
        status: 401,
        headers: { "X-Request-Id": "7d6a987c-6007-4d38-8bde-dafbdda20334" },
        json: { detail: "登录已失效或凭据不正确，请重新登录。" },
      }),
    );
    await page.getByRole("link", { name: flow.page, exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "登录工作区", exact: true }),
    ).toBeVisible();
    await expect(page).toHaveURL(/\/overview$/);
    await expect(page.locator(".app-layout")).toHaveCount(0);
    await expect(page.locator(".error-state")).toHaveCount(0);
    await expect(page.locator("body")).not.toContainText("数据暂时不可用");
  });
}

test("401 headers redirect even when the response body never completes", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page);
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = (input, init) => {
      const url =
        typeof input === "string"
          ? input
          : input instanceof URL
            ? input.href
            : input.url;
      if (url.includes("/workspace/monitoring")) {
        return Promise.resolve(
          new Response(new ReadableStream(), {
            status: 401,
            headers: {
              "Content-Type": "application/json",
              "X-Request-Id": "stalled-auth-body",
            },
          }),
        );
      }
      return original(input, init);
    };
  });
  await page.getByRole("link", { name: "监测与传感器", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "登录工作区", exact: true }),
  ).toBeVisible({ timeout: 1500 });
  await expect(page).toHaveURL(/\/overview$/);
  await expect(page.locator(".error-state")).toHaveCount(0);
  await page.screenshot({
    path: testInfo.outputPath("expired-to-login.png"),
    fullPage: false,
  });
  expect(errors).toEqual([]);
});
