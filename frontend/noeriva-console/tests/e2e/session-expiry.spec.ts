import { expect, test, type Page } from "@playwright/test";

const username = process.env.NOERIVA_E2E_USERNAME || "admin";
const password = process.env.NOERIVA_E2E_PASSWORD || "noeriva-local-demo";

async function submitLogin(page: Page, loginPassword = password) {
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(loginPassword);
  await page.getByRole("button", { name: "进入工作区", exact: true }).click();
}

test("expired API session returns to the home login, closes search and allows a new login", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/assets?q=private-session-context");
  await submitLogin(page);
  await expect(
    page.getByRole("heading", { name: "资产目录", exact: true }),
  ).toBeVisible();
  await page.route("**/api/v1/workspace/search?*", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ detail: "登录已失效或凭据不正确，请重新登录。" }),
    }),
  );
  await page
    .getByRole("button", {
      name: "搜索资产、端口、IP、事件，快捷键 Command 或 Control K",
    })
    .click();
  await page.getByLabel("全局搜索").fill("core");
  await expect(
    page.getByRole("heading", { name: "登录工作区", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/overview$/);
  await expect(page.locator(".app-layout")).toHaveCount(0);
  await expect(page.locator("dialog[open]")).toHaveCount(0);
  await expect(page.locator(".login-page")).not.toContainText("数据暂时不可用");

  await submitLogin(page, "session-expiry-intentionally-wrong-password");
  await expect(page.locator(".login-form [role='alert']")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "进入工作区", exact: true }),
  ).toBeEnabled();
  await expect(page).toHaveURL(/\/overview$/);
  await page.screenshot({
    path: "/tmp/noeriva-session-expiry-login.png",
    fullPage: false,
  });

  await page.unroute("**/api/v1/workspace/search?*");
  await submitLogin(page);
  await expect(
    page.getByRole("heading", { name: "运行总览", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".login-page")).toHaveCount(0);
  expect(errors).toEqual([]);
});

for (const status of [403, 503]) {
  test(`HTTP ${status} keeps the workspace session and search dialog`, async ({
    page,
  }) => {
    await page.goto("/assets");
    await submitLogin(page);
    await expect(
      page.getByRole("heading", { name: "资产目录", exact: true }),
    ).toBeVisible();
    await page.route("**/api/v1/workspace/search?*", (route) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify({ detail: "搜索暂时不可用" }),
      }),
    );
    await page
      .getByRole("button", {
        name: "搜索资产、端口、IP、事件，快捷键 Command 或 Control K",
      })
      .click();
    await page.getByLabel("全局搜索").fill("core");
    const search = page.getByRole("dialog", {
      name: "搜索资产、端口、IP、事件",
      exact: true,
    });
    await expect(search.getByRole("alert")).toContainText("搜索暂时不可用");
    await expect(search).toBeVisible();
    await expect(page.locator(".app-layout")).toBeVisible();
    await expect(page.locator(".login-page")).toHaveCount(0);
    await expect(page).toHaveURL(/\/assets$/);
  });
}
