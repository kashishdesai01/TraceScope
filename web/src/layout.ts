import type { Edge, Metric, TraceData } from "./types";
export const palette = [
  "#3e67db",
  "#0a9c88",
  "#a271d2",
  "#d98827",
  "#d24c70",
  "#2581ac",
];
export function serviceColor(service: string) {
  let hash = 0;
  for (const char of service) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return palette[hash % palette.length];
}
export function edgeClass(edge: Pick<Edge, "errorRate" | "p95">): string {
  if ((edge.errorRate || 0) >= 0.05) return "edge-error";
  if ((edge.errorRate || 0) >= 0.01) return "edge-warning";
  if ((edge.p95 || 0) >= 500) return "edge-slow";
  return "edge-healthy";
}
export function nodeRadius(rps: number, maxRps: number, kind: string): number {
  return kind === "service"
    ? Math.max(20, 42 * Math.sqrt(Math.max(0, rps) / Math.max(0.001, maxRps)))
    : 30;
}
export function mapData(metrics: Metric[], edges: Edge[]) {
  const nodes = new Map(
    metrics.map((metric) => [
      metric.service,
      { id: metric.service, kind: "service", rps: metric.rps },
    ]),
  );
  for (const edge of edges) {
    if (!nodes.has(edge.caller))
      nodes.set(edge.caller, { id: edge.caller, kind: "service", rps: 0 });
    if (!nodes.has(edge.callee))
      nodes.set(edge.callee, {
        id: edge.callee,
        kind: edge.callee_kind,
        rps: 0,
      });
  }
  return {
    nodes: [...nodes.values()],
    links: edges.map((edge) => ({
      ...edge,
      source: edge.caller,
      target: edge.callee,
      className: edgeClass(edge),
    })),
  };
}
export function waterfallLayout(trace: TraceData) {
  const offsets = new Map(
    trace.criticalPath.display.map((display) => [display.spanId, display]),
  );
  const root =
    trace.tree.nodes.find((node) =>
      trace.tree.rootIds.includes(node.span.spanId),
    ) || trace.tree.nodes[0];
  if (!root) return [];
  const origin = offsets.get(root.span.spanId)?.startNs ?? root.span.startNs;
  const duration = Math.max(1, trace.criticalPath.durationNs);
  const critical = new Set(
    trace.criticalPath.segments.map((segment) => segment.spanId),
  );
  return trace.tree.nodes.map((node) => {
    const display = offsets.get(node.span.spanId);
    return {
      ...node,
      left:
        (((display?.startNs ?? node.span.startNs) - origin) / duration) * 100,
      width: (node.span.durationNs / duration) * 100,
      critical: critical.has(node.span.spanId),
      skewAdjusted: display?.skewAdjusted || false,
    };
  });
}
export function validateRule(rule: RuleInput): string | null {
  if (!rule.name.trim() || !rule.service.trim())
    return "Enter a rule name and service.";
  if (
    !Number.isFinite(rule.threshold) ||
    rule.threshold <= 0 ||
    (rule.kind === "error_rate" && rule.threshold > 1)
  )
    return "Enter a positive threshold; error rate must be a fraction up to 1.";
  if (rule.windowSec < 60 || rule.windowSec > 3600 || rule.windowSec % 60 !== 0)
    return "Window must be a whole number of minutes, from 1 to 60.";
  if (
    !Number.isInteger(rule.forSec) ||
    rule.forSec < 0 ||
    rule.forSec > 3600 ||
    rule.minRequests < 1 ||
    rule.minRequests > 1000000 ||
    !Number.isInteger(rule.minRequests)
  )
    return "Check the persistence duration and minimum request count.";
  if (
    !Number.isFinite(rule.resolveRatio) ||
    rule.resolveRatio <= 0 ||
    rule.resolveRatio >= 1
  )
    return "Recovery ratio must be between 0 and 1.";
  return null;
}
type RuleInput = Pick<
  import("./types").Rule,
  | "name"
  | "service"
  | "kind"
  | "threshold"
  | "windowSec"
  | "forSec"
  | "minRequests"
  | "resolveRatio"
>;
