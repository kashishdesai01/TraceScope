import { test, expect } from "@playwright/test";

test("investigate payment latency from dependency map to span attributes", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/map");
  await expect(
    page.getByRole("heading", { name: "See the whole request." }),
  ).toBeVisible();
  const payment = page.getByRole("link", {
    name: "Investigate payment",
    exact: true,
  });
  await expect(payment).toBeVisible();
  const graph = page.getByRole("img", { name: /Service dependency map\./ });
  const inventory = page.getByRole("link", {
    name: "Investigate inventory",
    exact: true,
  });
  const graphBounds = await graph.boundingBox();
  const inventoryBounds = await inventory.boundingBox();
  expect(graphBounds).not.toBeNull();
  expect(inventoryBounds).not.toBeNull();
  await page.mouse.move(
    inventoryBounds!.x + inventoryBounds!.width / 2,
    inventoryBounds!.y + 20,
  );
  await page.mouse.down();
  await page.mouse.move(
    graphBounds!.x + graphBounds!.width / 2,
    graphBounds!.y + graphBounds!.height - 2,
    { steps: 8 },
  );
  await page.mouse.up();
  const pinnedBounds = await inventory.boundingBox();
  expect(pinnedBounds!.y).toBeGreaterThanOrEqual(graphBounds!.y);
  expect(pinnedBounds!.y + pinnedBounds!.height).toBeLessThanOrEqual(
    graphBounds!.y + graphBounds!.height,
  );
  await payment.focus();
  await payment.press("Enter");
  await expect(
    page.getByRole("heading", { name: "payment", exact: true, level: 1 }),
  ).toBeVisible();
  const slow = page.locator("section").filter({
    has: page.getByRole("heading", { name: "Slow traces", exact: true }),
  });
  await expect(slow.locator("tbody tr").first()).toBeVisible();
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
  await expect(
    page.getByText("Retained attributes", { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("complementary", { name: "Span details" }),
  ).not.toBeVisible();
  await page.screenshot({
    path: "../reports/runtime/trace-waterfall.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("create, preview, edit and delete a rule through the UI", async ({
  page,
}) => {
  await page.goto("/alerts");
  await page
    .getByRole("button", { name: "+ Create rule", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog
    .getByLabel("Rule name", { exact: true })
    .fill("Browser smoke latency");
  await dialog.getByLabel("Service", { exact: true }).fill("browser-smoke");
  await dialog.getByRole("button", { name: "Preview current value" }).click();
  await expect(dialog.getByText(/no data/)).toBeVisible();
  await dialog.getByRole("button", { name: "Save rule", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  let row = page.getByRole("row").filter({ hasText: "Browser smoke latency" });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "Edit", exact: true }).click();
  await dialog
    .getByLabel("Sustained for (seconds)", { exact: true })
    .fill("30");
  await dialog.getByRole("button", { name: "Save rule", exact: true }).click();
  row = page.getByRole("row").filter({ hasText: "Browser smoke latency" });
  await expect(row).toContainText("30 s");
  await row.getByRole("button", { name: "Delete", exact: true }).click();
  await expect(row).not.toBeVisible();
  await page
    .getByRole("button", { name: "+ Create rule", exact: true })
    .click();
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
});

test("overview works on a narrow viewport without page overflow", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Know where the time goes." }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "payment", exact: true }).first(),
  ).toBeVisible();
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  expect(overflow).toBe(false);
  await page.screenshot({
    path: "../reports/runtime/mobile-overview.png",
    fullPage: true,
  });
});
