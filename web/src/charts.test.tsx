import { render, cleanup } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { SeriesChart, Sparkline } from "./charts";
import type { Metric, WindowRange } from "./types";

afterEach(cleanup);
const window: WindowRange = {
  from: "2026-11-08T12:00:00Z",
  to: "2026-11-08T12:12:00Z",
  resolution: "UTC minute",
};
const rows: Metric[] = Array.from({ length: 12 }, (_, i) => ({
  service: "checkout",
  time: new Date(Date.parse(window.from) + i * 60000).toISOString(),
  requests: i === 0 || i >= 10 ? 20 : 0,
  errors: 0,
  rps: i === 0 || i >= 10 ? 1 / 3 : 0,
  errorRate: i === 0 || i >= 10 ? 0 : null,
  p50: i === 0 || i >= 10 ? 5 : null,
  p95: i === 0 || i >= 10 ? 10 : null,
  p99: i === 0 || i >= 10 ? 15 : null,
}));
it("positions latency by elapsed UTC time and breaks lines across unknown buckets", () => {
  const { container } = render(<SeriesChart rows={rows} window={window} />);
  const path = container.querySelector("path")!.getAttribute("d")!;
  expect(path).toMatch(/^M55,/);
  expect(path).toContain("M592.5,");
  expect(path).toContain("L646.25,");
  expect(path.match(/M/g)).toHaveLength(2);
  expect(container.querySelectorAll("circle")).toHaveLength(3);
});
it("preserves gap timing in the overview sparkline", () => {
  const { container } = render(<Sparkline rows={rows} window={window} />);
  const path = container.querySelector("path")!.getAttribute("d")!;
  expect(path).toMatch(/^M0,/);
  expect(path).toContain("M91.667,");
  expect(path.match(/M/g)).toHaveLength(2);
});
it("does not render missing latency as a zero-valued singleton", () => {
  const { container, getByText } = render(
    <SeriesChart rows={[rows[1]]} window={window} />,
  );
  expect(container.querySelectorAll("circle")).toHaveLength(0);
  expect(getByText("No samples in this window")).toBeInTheDocument();
});
