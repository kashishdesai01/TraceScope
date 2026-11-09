import { useMemo, useRef, useState } from "react";
import {
  forceSimulation,
  forceLink,
  forceManyBody,
  forceCenter,
  forceCollide,
  line,
  randomLcg,
  scaleLinear,
} from "d3";
import type { SimulationNodeDatum, SimulationLinkDatum } from "d3";
import { useLocation, useNavigate } from "react-router-dom";
import { edgeClass, mapData, nodeRadius, serviceColor } from "./layout";
import { ms, percent } from "./api";
import type { Edge, Metric, ServiceMapData, WindowRange } from "./types";

export function Sparkline({
  rows,
  window,
  color = "#3e67db",
}: {
  rows: Metric[];
  window?: WindowRange;
  color?: string;
}) {
  const values = rows.map((row) => row.p95);
  const numbers = values.filter((v): v is number => v != null);
  const x = timeScale(rows, window, [0, 110]);
  if (!numbers.length) return <span className="muted">No samples</span>;
  const y = scaleLinear()
    .domain([0, Math.max(1, ...numbers)])
    .range([26, 3]);
  const path = line<number | null>()
    .defined((v): v is number => v != null)
    .x((_, i) => x(Date.parse(rows[i].time!)))
    .y((v) => y(v || 0))(values);
  return (
    <svg
      className="sparkline"
      viewBox="0 0 110 30"
      role="img"
      aria-label="Recent p95 latency"
    >
      <path d={path || ""} fill="none" stroke={color} strokeWidth="2" />
      {rows.map((row, index) =>
        row.p95 != null &&
        rows[index - 1]?.p95 == null &&
        rows[index + 1]?.p95 == null ? (
          <circle
            key={row.time}
            cx={x(Date.parse(row.time!))}
            cy={y(row.p95)}
            r="2"
            fill={color}
          />
        ) : null,
      )}
    </svg>
  );
}

function timeScale(
  rows: Metric[],
  window: WindowRange | undefined,
  range: [number, number],
) {
  const start = window
    ? Date.parse(window.from)
    : Date.parse(rows[0]?.time || "1970-01-01T00:00:00Z");
  const end = window
    ? Date.parse(window.to)
    : rows.length
      ? Date.parse(rows[rows.length - 1].time!) + 60000
      : start + 60000;
  return scaleLinear()
    .domain([start, Math.max(start + 1, end)])
    .clamp(true)
    .range(range);
}

export function SeriesChart({
  rows,
  window,
  field = "latency",
  threshold,
}: {
  rows: Metric[];
  window?: WindowRange;
  field?: "latency" | "rps" | "errorRate";
  threshold?: number;
}) {
  const keys = field === "latency" ? (["p50", "p95", "p99"] as const) : [field];
  const colors = ["#079c85", "#3e67db", "#a271d2"];
  const values = rows
    .flatMap((row) => keys.map((key) => row[key as keyof Metric]))
    .filter((v): v is number => typeof v === "number");
  const max =
    Math.max(field === "errorRate" ? 0.01 : 1, threshold || 0, ...values) *
    1.15;
  const y = scaleLinear().domain([0, max]).range([156, 12]);
  const x = timeScale(rows, window, [55, 700]);
  return (
    <div className="chart">
      <svg
        viewBox="0 0 730 195"
        role="img"
        aria-label={
          field === "latency"
            ? "p50, p95 and p99 latency over time"
            : `${field} over time`
        }
      >
        {y.ticks(4).map((value) => (
          <g key={value}>
            <line
              x1="55"
              x2="700"
              y1={y(value)}
              y2={y(value)}
              stroke="#e7ebf1"
            />
            <text x="46" y={y(value) + 4} textAnchor="end">
              {field === "errorRate"
                ? percent(value)
                : value.toFixed(value < 10 ? 1 : 0)}
            </text>
          </g>
        ))}
        {threshold != null && (
          <g>
            <line
              x1="55"
              x2="700"
              y1={y(threshold)}
              y2={y(threshold)}
              stroke="#d98827"
              strokeDasharray="5 5"
            />
            <text x="697" y={y(threshold) - 5} textAnchor="end">
              Alert {ms(threshold)}
            </text>
          </g>
        )}
        {keys.map((key, i) => (
          <path
            key={key}
            d={
              line<Metric>()
                .defined((row) => row[key as keyof Metric] != null)
                .x((row) => x(Date.parse(row.time!)))
                .y((row) => y(Number(row[key as keyof Metric])))(rows) || ""
            }
            fill="none"
            stroke={colors[i]}
            strokeWidth="2.5"
          />
        ))}
        {keys.flatMap((key, colorIndex) =>
          rows.map((row, index) =>
            row[key as keyof Metric] != null &&
            rows[index - 1]?.[key as keyof Metric] == null &&
            rows[index + 1]?.[key as keyof Metric] == null ? (
              <circle
                key={`${key}-${row.time}`}
                cx={x(Date.parse(row.time!))}
                cy={y(Number(row[key as keyof Metric]))}
                r="3"
                fill={colors[colorIndex]}
              />
            ) : null,
          ),
        )}
        {rows
          .filter(
            (_, i) =>
              i === 0 ||
              i === rows.length - 1 ||
              i === Math.floor(rows.length / 2),
          )
          .map((row, i) => (
            <text
              key={`${row.time}-${i}`}
              x={x(Date.parse(row.time!))}
              y="182"
              textAnchor="middle"
            >
              {String(row.time || "")
                .replace("T", " ")
                .slice(11, 16)}{" "}
              UTC
            </text>
          ))}
        {!values.length && (
          <text x="370" y="90" textAnchor="middle">
            No samples in this window
          </text>
        )}
      </svg>
      <p className="muted">
        Empty buckets mean no received requests; latency and error rate are
        unknown.
      </p>
      <div className="chart-legend">
        {keys.map((key, i) => (
          <span key={key}>
            <i style={{ background: colors[i] }} />
            {key === "errorRate"
              ? "Error rate"
              : key === "rps"
                ? "Requests / second"
                : `${key} · milliseconds`}
          </span>
        ))}
      </div>
    </div>
  );
}

type Node = SimulationNodeDatum & { id: string; kind: string; rps: number };
type Link = SimulationLinkDatum<Node> & Edge;
export function ServiceGraph({ data }: { data: ServiceMapData }) {
  const navigate = useNavigate();
  const { search } = useLocation();
  const svg = useRef<SVGSVGElement>(null);
  const dragging = useRef<{
    id: string;
    x: number;
    y: number;
    moved: boolean;
  } | null>(null);
  const [pinned, setPinned] = useState<
    Record<string, { x: number; y: number }>
  >({});
  const geometry = useMemo(() => {
    const graph = mapData(data.nodes, data.edges);
    const nodes: Node[] = graph.nodes.map((node) => ({ ...node }));
    const links: Link[] = graph.links.map((link) => ({ ...link }));
    const simulation = forceSimulation(nodes)
      .randomSource(randomLcg(0.42))
      .force(
        "link",
        forceLink<Node, Link>(links)
          .id((node) => node.id)
          .distance(180),
      )
      .force("charge", forceManyBody().strength(-700))
      .force("center", forceCenter(440, 210))
      .force("collide", forceCollide(65))
      .stop();
    simulation.tick(180);
    for (const node of nodes) {
      node.x = Math.max(90, Math.min(790, node.x || 440));
      node.y = Math.max(65, Math.min(335, node.y || 210));
    }
    return { nodes, links };
  }, [data.nodes, data.edges]);
  const positions = new Map(
    geometry.nodes.map((node) => [
      node.id,
      { ...node, ...(pinned[node.id] || {}) },
    ]),
  );
  const maxRps = Math.max(
    0.001,
    ...geometry.nodes
      .filter((node) => node.kind === "service")
      .map((node) => node.rps),
  );
  function move(event: React.PointerEvent<SVGSVGElement>) {
    const active = dragging.current;
    if (!active || !svg.current) return;
    const matrix = svg.current.getScreenCTM();
    if (!matrix) return;
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(
      matrix.inverse(),
    );
    if (
      Math.abs(event.clientX - active.x) + Math.abs(event.clientY - active.y) >
      4
    )
      active.moved = true;
    if (active.moved)
      setPinned((previous) => ({
        ...previous,
        [active.id]: {
          x: Math.max(60, Math.min(820, point.x)),
          y: Math.max(60, Math.min(335, point.y)),
        },
      }));
  }
  return (
    <div className="graph-wrap">
      <div className="graph-toolbar">
        <span>
          <span className="live-dot" />
          {geometry.nodes.length} nodes · {geometry.links.length} dependencies
        </span>
        <button className="text-button" onClick={() => setPinned({})}>
          Reset layout
        </button>
      </div>
      <div className="graph-canvas">
        <svg
          ref={svg}
          viewBox="0 0 880 420"
          className="service-graph"
          role="img"
          aria-label="Service dependency map. Select a service to investigate; drag nodes to pin them."
          onPointerMove={move}
          onPointerUp={(event) => {
            const active = dragging.current;
            dragging.current = null;
            if (
              active &&
              !active.moved &&
              positions.get(active.id)?.kind === "service"
            )
              navigate(`/services/${encodeURIComponent(active.id)}${search}`);
            if (svg.current?.hasPointerCapture(event.pointerId))
              svg.current.releasePointerCapture(event.pointerId);
          }}
          onPointerCancel={() => {
            dragging.current = null;
          }}
        >
          <defs>
            <pattern
              id="grid"
              width="24"
              height="24"
              patternUnits="userSpaceOnUse"
            >
              <circle cx="1" cy="1" r="1" fill="#dce3ed" />
            </pattern>
            <marker
              id="arrow"
              markerWidth="8"
              markerHeight="8"
              refX="7"
              refY="4"
              orient="auto"
            >
              <path d="M0,0 L8,4 L0,8" fill="#a4b2c8" />
            </marker>
          </defs>
          <rect width="880" height="420" fill="url(#grid)" />
          {geometry.links.map((edge, i) => {
            const a = positions.get((edge.source as Node).id)!,
              b = positions.get((edge.target as Node).id)!;
            const length =
              Math.hypot((b.x || 0) - (a.x || 0), (b.y || 0) - (a.y || 0)) || 1;
            const endX =
                (b.x || 0) -
                (((b.x || 0) - (a.x || 0)) / length) *
                  (nodeRadius(b.rps, maxRps, b.kind) + 2),
              endY =
                (b.y || 0) -
                (((b.y || 0) - (a.y || 0)) / length) *
                  (nodeRadius(b.rps, maxRps, b.kind) + 2);
            return (
              <g
                key={`${edge.caller}-${edge.callee}-${i}`}
                className={edgeClass(edge)}
              >
                <line
                  x1={a.x}
                  y1={a.y}
                  x2={endX}
                  y2={endY}
                  markerEnd="url(#arrow)"
                  strokeWidth="2"
                />
                <rect
                  x={((a.x || 0) + (b.x || 0)) / 2 - 35}
                  y={((a.y || 0) + (b.y || 0)) / 2 - 10}
                  width="70"
                  height="19"
                  rx="5"
                  fill="#fff"
                />
                <text
                  x={((a.x || 0) + (b.x || 0)) / 2}
                  y={((a.y || 0) + (b.y || 0)) / 2 + 3}
                  textAnchor="middle"
                >
                  {ms(edge.p95)}
                </text>
                <title>
                  {edge.caller} → {edge.callee}: {edge.calls} calls,{" "}
                  {percent(edge.errorRate)} errors, p95 {ms(edge.p95)}
                </title>
              </g>
            );
          })}
          {[...positions.values()].map((node) => (
            <g
              key={node.id}
              className="graph-node"
              transform={`translate(${node.x},${node.y})`}
              tabIndex={node.kind === "service" ? 0 : undefined}
              role={node.kind === "service" ? "link" : undefined}
              aria-label={`Investigate ${node.id}`}
              onKeyDown={(event) => {
                if (event.key === "Enter" && node.kind === "service")
                  navigate(`/services/${encodeURIComponent(node.id)}${search}`);
              }}
              onPointerDown={(event) => {
                event.preventDefault();
                dragging.current = {
                  id: node.id,
                  x: event.clientX,
                  y: event.clientY,
                  moved: false,
                };
                svg.current?.setPointerCapture(event.pointerId);
              }}
            >
              <circle
                r={nodeRadius(node.rps, maxRps, node.kind)}
                fill="white"
                stroke={
                  node.kind === "database" ? "#a271d2" : serviceColor(node.id)
                }
                strokeWidth="2.5"
              />
              {node.kind === "database" ? (
                <g stroke="#a271d2" fill="none" strokeWidth="1.8">
                  <path d="M-11,-7v14c0,7,22,7,22,0v-14" />
                  <ellipse cy="-7" rx="11" ry="4" />
                  <path d="M-11,0c0,6,22,6,22,0" />
                </g>
              ) : (
                <g stroke={serviceColor(node.id)} fill="none" strokeWidth="2">
                  <rect x="-10" y="-12" width="20" height="24" rx="4" />
                  <path d="M-5,-5h10M-5,1h10M-5,7h5" />
                </g>
              )}
              <text textAnchor="middle" y="53" className="node-name">
                {node.id}
              </text>
              <text textAnchor="middle" y="70" className="node-rate">
                {node.kind === "service"
                  ? `${node.rps.toFixed(2)} req/s`
                  : node.kind}
              </text>
            </g>
          ))}
          {!positions.size && (
            <text x="440" y="210" textAnchor="middle">
              No dependencies yet. Send traffic and wait for a minute to close.
            </text>
          )}
        </svg>
      </div>
      <div className="map-legend">
        <span>
          <i className="legend-line healthy" />
          Healthy
        </span>
        <span>
          <i className="legend-line slow" />
          p95 ≥ 500 ms
        </span>
        <span>
          <i className="legend-line error" />
          Errors ≥ 5%
        </span>
        <span className="muted">
          Node size shows request rate · edge labels show p95 · drag to pin
        </span>
      </div>
    </div>
  );
}
