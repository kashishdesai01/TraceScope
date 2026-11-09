// Record actual application pages while the local payment-latency scenario is visible.
import { chromium, expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";
const directory = new URL("../../reports/runtime/demo/", import.meta.url);
await mkdir(directory, { recursive: true });
const browser = await chromium.launch();
try {
  const page = await browser.newPage({
    viewport: { width: 1400, height: 1000 },
    deviceScaleFactor: 1,
  });
  const base = process.env.WEB_URL || "http://127.0.0.1:3000";
  const frame = async (number) => {
    await page.screenshot({
      path: new URL(`frame-${number}.png`, directory).pathname,
    });
  };
  await page.goto(`${base}/?minutes=15`);
  await expect(
    page.getByRole("heading", { name: "Know where the time goes." }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "payment", exact: true }).first(),
  ).toBeVisible();
  await expect(page.locator(".loading")).toHaveCount(0);
  await frame(1);
  await page.goto(`${base}/map?minutes=15`);
  const payment = page.getByRole("link", {
    name: "Investigate payment",
    exact: true,
  });
  await expect(payment).toBeVisible();
  await frame(2);
  await payment.focus();
  await payment.press("Enter");
  await expect(
    page.getByRole("heading", { name: "payment", exact: true, level: 1 }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Request latency", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".loading")).toHaveCount(0);
  await expect(
    page.getByText("No samples in this window", { exact: true }),
  ).toHaveCount(0);
  await frame(3);
  await page.getByRole("link", { name: /Search service traces/ }).click();
  await page.getByLabel("Minimum duration (ms)", { exact: true }).fill("500");
  await page
    .getByRole("button", { name: "Search traces", exact: true })
    .click();
  const checkout = page
    .locator("tbody tr")
    .filter({ hasText: "POST /api/checkout" })
    .first();
  await expect(checkout).toBeVisible();
  await checkout.getByRole("link").first().click();
  await expect(
    page.getByRole("heading", { name: "Follow the time." }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Inspect payment POST /pay", exact: true })
    .click();
  await expect(
    page.getByRole("complementary", { name: "Span details" }),
  ).toBeVisible();
  await frame(4);
  await page.keyboard.press("Escape");
  await frame(5);
  console.log("Recorded five actual UI frames under reports/runtime/demo.");
} finally {
  await browser.close();
}
