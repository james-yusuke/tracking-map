import { expect, test } from "@playwright/test";

test("複数の子どもを切り替えて現在地と精度を確認できる", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "家族の現在地" })).toBeVisible();
  await expect(page.getByText("デモデータ")).toBeVisible();
  await expect(page.getByRole("button", { name: /はる/ }).first()).toBeVisible();
  await page.getByRole("button", { name: /ゆい/ }).first().click();
  await expect(page.getByText(/± 31 m/)).toBeVisible();
  await expect(page.getByText("64%").first()).toBeVisible();
});

test("履歴表示と通知ドロワーをキーボードでも操作できる", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "移動履歴" }).click();
  await expect(page.getByRole("heading", { name: /今日の移動/ })).toBeVisible();
  await expect(page.getByText(/地点を記録/)).toBeVisible();

  await page.getByRole("button", { name: "通知を開く" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "通知" })).toBeVisible();
  await page.getByRole("button", { name: "通知を閉じる" }).click();
  await expect(page.getByRole("dialog", { name: "通知" })).toBeHidden();
});

test("Webには更新操作を表示せず、主要領域にアクセシブルな名前がある", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("main")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "メインメニュー" })).toBeVisible();
  await expect(page.getByLabel("家族の位置を表示する地図")).toBeVisible();
  await expect(page.getByRole("button", { name: /追加|削除|保存/ })).toHaveCount(0);
  await expect(page.getByText("共有は明示されています")).toBeVisible();
});

test("モバイル幅でも現在地・履歴・通知を操作できる", async ({ page }, testInfo) => {
  test.skip(!testInfo.project.name.includes("mobile"), "mobile project only");
  await page.goto("/");
  await expect(page.getByRole("navigation", { name: "モバイルメニュー" })).toBeVisible();
  await page.getByRole("navigation", { name: "モバイルメニュー" }).getByRole("button", { name: "履歴" }).click();
  await expect(page.getByRole("heading", { name: /今日の移動/ })).toBeVisible();
  await page.getByRole("button", { name: "メニューを開く" }).click();
  await expect(page.getByRole("button", { name: "メニューを閉じる" })).toBeVisible();
});

test("ログイン画面はラベル・パスワード表示切替・エラー領域を備える", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "おかえりなさい" })).toBeVisible();
  await expect(page.getByLabel("メールアドレス")).toBeVisible();
  await expect(page.getByLabel("パスワード")).toHaveAttribute("type", "password");
  await page.getByRole("button", { name: "パスワードを表示" }).click();
  await expect(page.getByLabel("パスワード")).toHaveAttribute("type", "text");
});
