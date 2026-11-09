import { describe, expect, it } from "vitest";
import {
  edgeClass,
  mapData,
  nodeRadius,
  validateRule,
  waterfallLayout,
} from "./layout";
import type { Edge, Metric, Rule, TraceData, TraceNode } from "./types";
const node = (
  id: string,
  parentId: string,
  start: number,
  duration: number,
  depth = 0,
): TraceNode => ({
  span: {
    spanId: id,
    parentSpanId: parentId,
    traceId: "trace",
    service: id,
    name: "operation",
    kind: "SERVER",
    startNs: start,
    durationNs: duration,
    error: false,
    httpStatus: 200,
    route: "/",
    peer: "",
    database: false,
    attributes: {},
  },
  parentId,
  depth,
  missingParent: false,
  cycle: false,
});
const trace = (): TraceData => ({
  traceId: "trace",
  tree: {
    nodes: [
      node("root", "", 100, 100),
      node("child", "root", 120, 50, 1),
      node("parallel", "root", 130, 50, 1),
    ],
    rootIds: ["root"],
    knownIncomplete: false,
  },
  criticalPath: {
    durationNs: 100,
    approximate: false,
    display: [],
    segments: [
      {
        spanId: "parallel",
        service: "parallel",
        name: "operation",
        startNs: 130,
        durationNs: 50,
        pctOfTotal: 50,
      },
    ],
  },
  metadata: {
    knownIncomplete: false,
    finalizationReason: "root_idle",
    lateSpanCount: 0,
  },
  finalizationNotice: "",
});
describe("waterfall layout", () => {
  it("positions nested and parallel spans without adding their durations", () => {
    const rows = waterfallLayout(trace());
    expect(rows[0].left).toBe(0);
    expect(rows[0].width).toBe(100);
    expect(rows[1].left).toBe(20);
    expect(rows[1].width).toBe(50);
    expect(rows[2].left).toBe(30);
    expect(rows[2].critical).toBe(true);
    expect(rows[1].critical).toBe(false);
    expect(rows[1].depth).toBe(1);
  });
  it("uses adjusted display timestamps while preserving raw clocks", () => {
    const fixture = trace();
    fixture.criticalPath.display = [
      { spanId: "child", startNs: 140, skewAdjusted: true },
    ];
    const rows = waterfallLayout(fixture);
    expect(rows[1].left).toBe(40);
    expect(rows[1].skewAdjusted).toBe(true);
    expect(rows[1].span.startNs).toBe(120);
  });
  it("handles zero-duration and empty traces", () => {
    const fixture = trace();
    fixture.criticalPath.durationNs = 0;
    expect(Number.isFinite(waterfallLayout(fixture)[1].left)).toBe(true);
    fixture.tree.nodes = [];
    expect(waterfallLayout(fixture)).toEqual([]);
  });
});
describe("dependency map", () => {
  it("encodes service throughput with readable square-root node sizes", () => {
    expect(nodeRadius(100, 100, "service")).toBe(42);
    expect(nodeRadius(25, 100, "service")).toBe(21);
    expect(nodeRadius(0, 100, "service")).toBe(20);
    expect(nodeRadius(0, 0, "service")).toBe(20);
    expect(nodeRadius(100, 100, "database")).toBe(30);
  });
  it("creates database leaves and resolves edge endpoints", () => {
    const metric = { service: "payment", rps: 2 } as Metric;
    const edge = {
      caller: "payment",
      callee: "postgresql:payments",
      callee_kind: "database",
      calls: 10,
      errors: 0,
      errorRate: 0,
      p95: 10,
      rps: 2,
    } as Edge;
    const map = mapData([metric], [edge]);
    expect(map.nodes.map((item) => item.id)).toEqual([
      "payment",
      "postgresql:payments",
    ]);
    expect(map.nodes[1].kind).toBe("database");
    expect(map.links[0].source).toBe("payment");
    expect(map.links[0].target).toBe("postgresql:payments");
  });
  it("distinguishes latency from errors including boundary values", () => {
    expect(edgeClass({ errorRate: 0, p95: 800 })).toBe("edge-slow");
    expect(edgeClass({ errorRate: 0.05, p95: 800 })).toBe("edge-error");
    expect(edgeClass({ errorRate: 0.01, p95: 10 })).toBe("edge-warning");
    expect(edgeClass({ errorRate: 0, p95: 10 })).toBe("edge-healthy");
  });
});
describe("alert validation", () => {
  const rule: Rule = {
    id: null,
    name: "Checkout latency",
    service: "checkout",
    operation: null,
    kind: "latency",
    percentile: 0.95,
    threshold: 500,
    windowSec: 120,
    forSec: 60,
    minRequests: 20,
    resolveRatio: 0.8,
    enabled: true,
  };
  it("accepts the default rule", () => expect(validateRule(rule)).toBeNull());
  it.each([
    { name: "" },
    { service: "" },
    { threshold: NaN },
    { kind: "error_rate" as const, threshold: 5 },
    { windowSec: 61 },
    { forSec: 1.5 },
    { minRequests: 0 },
    { resolveRatio: NaN },
    { resolveRatio: 1 },
  ])("rejects invalid fields %o", (patch) =>
    expect(validateRule({ ...rule, ...patch })).not.toBeNull(),
  );
});
