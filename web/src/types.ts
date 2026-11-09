export interface WindowRange {
  from: string;
  to: string;
  resolution: string;
}
export interface Page<T> {
  window: WindowRange;
  data: T[];
  nextCursor: string | null;
}
export interface Metric {
  service: string;
  operation?: string;
  time?: string;
  requests: number;
  errors: number;
  rps: number;
  errorRate: number | null;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  p50Range?: string;
  p95Range?: string;
  p99Range?: string;
}
export interface Edge {
  caller: string;
  callee: string;
  callee_kind: "service" | "database" | "external";
  calls: number;
  errors: number;
  rps: number;
  errorRate: number | null;
  p95: number | null;
}
export interface ServiceMapData {
  window: WindowRange;
  nodes: Metric[];
  edges: Edge[];
  edgeCompleteness: string;
}
export interface Span {
  traceId: string;
  spanId: string;
  parentSpanId: string;
  service: string;
  name: string;
  kind: string;
  startNs: number;
  durationNs: number;
  error: boolean;
  httpStatus: number;
  route: string;
  peer: string;
  database: boolean;
  attributes: Record<string, string>;
}
export interface TraceNode {
  span: Span;
  parentId: string;
  depth: number;
  missingParent: boolean;
  cycle: boolean;
}
export interface Segment {
  spanId: string;
  service: string;
  name: string;
  startNs: number;
  durationNs: number;
  pctOfTotal: number;
}
export interface TraceData {
  traceId: string;
  tree: { nodes: TraceNode[]; rootIds: string[]; knownIncomplete: boolean };
  criticalPath: {
    segments: Segment[];
    display: { spanId: string; startNs: number; skewAdjusted: boolean }[];
    durationNs: number;
    approximate: boolean;
  };
  metadata: {
    knownIncomplete: boolean;
    finalizationReason: string;
    lateSpanCount: number;
  };
  finalizationNotice: string;
}
export interface TraceRow {
  trace_id: string;
  start_ns: number;
  duration_ns: number;
  root_service: string;
  root_name: string;
  services: string[];
  span_count: number;
  error_count: number;
  kept_reason: string;
  known_incomplete: boolean;
  late_span_count: number;
}
export interface Rule {
  id: string | null;
  name: string;
  service: string;
  operation: string | null;
  kind: "latency" | "error_rate";
  percentile: number | null;
  threshold: number;
  windowSec: number;
  forSec: number;
  minRequests: number;
  resolveRatio: number;
  enabled: boolean;
}
export interface AlertEvent {
  id: string;
  rule_id: string;
  rule_name: string;
  service: string;
  kind: string;
  threshold: number;
  state: "pending" | "firing" | "resolved";
  started_at: string;
  firing_at: string | null;
  resolved_at: string | null;
  current_value: number | null;
  peak_value: number | null;
  no_data: boolean;
  exemplar_trace_ids: string[];
  exemplar_status: string;
  resolution: string | null;
}
export interface PipelineStats {
  bufferedSpans: number;
  estimatedBytes: number;
  identities: number;
  activeTraces: number;
  decisions: number;
  aggregateKeys: number;
  writerFrames: number;
  writerHealthy: boolean;
}
