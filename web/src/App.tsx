import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  Link,
  NavLink,
  Route,
  Routes,
  useLocation,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { count, ms, percent, rangeQuery, request, useApi } from "./api";
import { SeriesChart, ServiceGraph, Sparkline } from "./charts";
import { serviceColor, validateRule, waterfallLayout } from "./layout";
import type {
  AlertEvent,
  Metric,
  Page,
  PipelineStats,
  Rule,
  ServiceMapData,
  TraceData,
  TraceRow,
} from "./types";

function useRange() {
  const [params] = useSearchParams();
  const input = Number(params.get("minutes") || 15);
  const minutes = [15, 30, 60, 360, 720, 1440].includes(input) ? input : 15;
  return { minutes, query: rangeQuery(minutes) };
}
function SLink({
  service,
  children,
}: {
  service: string;
  children?: ReactNode;
}) {
  const { search } = useLocation();
  return (
    <Link
      to={`/services/${encodeURIComponent(service)}${search}`}
      className="service-link"
    >
      <i style={{ background: serviceColor(service) }} />
      {children || service}
    </Link>
  );
}
function TLink({ id, children }: { id: string; children?: ReactNode }) {
  const { search } = useLocation();
  return (
    <Link to={`/traces/${id}${search}`} className="mono">
      {children || `${id.slice(0, 12)}…`}
    </Link>
  );
}
function Status({ loading, error }: { loading: boolean; error: Error | null }) {
  if (error)
    return (
      <div className="notice danger" role="alert">
        {error.message} <span>Check the backend and retry with Refresh.</span>
      </div>
    );
  if (loading)
    return (
      <div className="loading" role="status">
        <span className="spinner" />
        Loading telemetry…
      </div>
    );
  return null;
}
function Modal({
  onClose,
  children,
}: {
  onClose: () => void;
  children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="dialog-host"
      aria-labelledby="rule-title"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      {children}
    </dialog>
  );
}
function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="empty">
      <span className="empty-symbol">⌁</span>
      {children}
    </div>
  );
}
function Panel({
  title,
  subtitle,
  action,
  children,
  className = "",
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      <div className="panel-heading">
        <div>
          <h2>{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}
function Title({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-title">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </div>
  );
}
function Stats({
  items,
}: {
  items: { label: string; value: ReactNode; note: string; color?: string }[];
}) {
  return (
    <div className="stats">
      {items.map((item) => (
        <div className="stat" key={item.label}>
          <span>{item.label}</span>
          <strong style={{ color: item.color }}>{item.value}</strong>
          <small>{item.note}</small>
        </div>
      ))}
    </div>
  );
}
function ServiceTrend({ service, query }: { service: string; query: string }) {
  const series = useApi<Page<Metric>>(
    `/services/${encodeURIComponent(service)}/series?${query}`,
  );
  return (
    <Sparkline
      rows={series.data?.data || []}
      window={series.data?.window}
      color={serviceColor(service)}
    />
  );
}
function ServicesTable({ rows, query }: { rows: Metric[]; query: string }) {
  const [sort, setSort] = useState<keyof Metric>("requests");
  const sorted = [...rows].sort(
    (a, b) => Number(b[sort] || 0) - Number(a[sort] || 0),
  );
  return rows.length ? (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Service</th>
            <th>Status</th>
            {(["requests", "rps", "errorRate", "p95", "p99"] as const).map(
              (key) => (
                <th key={key}>
                  <button className="sort" onClick={() => setSort(key)}>
                    {
                      {
                        requests: "Requests",
                        rps: "Req / sec",
                        errorRate: "Error rate",
                        p95: "p95 latency",
                        p99: "p99 latency",
                      }[key]
                    }
                    {sort === key ? " ↓" : ""}
                  </button>
                </th>
              ),
            )}
            <th>Latency trend</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => (
            <tr key={row.service}>
              <td>
                <SLink service={row.service} />
              </td>
              <td>
                <span
                  className={`badge ${(row.errorRate || 0) >= 0.05 ? "red" : (row.p95 || 0) >= 500 ? "amber" : "green"}`}
                >
                  {(row.errorRate || 0) >= 0.05
                    ? "Errors"
                    : (row.p95 || 0) >= 500
                      ? "Slow"
                      : "Healthy"}
                </span>
              </td>
              <td>{count(row.requests)}</td>
              <td>{row.rps.toFixed(2)}</td>
              <td className={row.errors ? "error-text" : ""}>
                {percent(row.errorRate)}
              </td>
              <td>{ms(row.p95)}</td>
              <td>{ms(row.p99)}</td>
              <td>
                <ServiceTrend service={row.service} query={query} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <Empty>
      No application requests yet. Start the demo load and wait for a minute to
      close.
    </Empty>
  );
}
function TraceTable({ rows }: { rows: TraceRow[] }) {
  return rows.length ? (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Trace ID</th>
            <th>Root operation</th>
            <th>Started</th>
            <th>Duration</th>
            <th>Spans</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.trace_id}>
              <td>
                <TLink id={row.trace_id} />
              </td>
              <td>
                <strong>{row.root_service}</strong>
                <span className="cell-sub">{row.root_name}</span>
              </td>
              <td className="muted">
                {new Date(row.start_ns / 1e6).toLocaleTimeString()}
              </td>
              <td>{ms(row.duration_ns / 1e6)}</td>
              <td>{row.span_count}</td>
              <td>
                <span
                  className={`badge ${row.error_count ? "red" : row.known_incomplete ? "amber" : "neutral"}`}
                >
                  {row.error_count
                    ? `${row.error_count} errors`
                    : row.known_incomplete
                      ? "Incomplete"
                      : row.kept_reason}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <Empty>No retained traces match this window.</Empty>
  );
}
function Overview() {
  const { query } = useRange();
  const services = useApi<Page<Metric>>(`/services?${query}`);
  const map = useApi<ServiceMapData>(`/service-map?${query}`);
  const rows = services.data?.data || [];
  const requests = rows.reduce((sum, row) => sum + row.requests, 0);
  const errors = rows.reduce((sum, row) => sum + row.errors, 0);
  return (
    <>
      <Title
        eyebrow="APPLICATION PERFORMANCE"
        title="Know where the time goes."
        description="Follow a request from the first call to the slowest dependency."
      />
      <Status loading={services.isPending} error={services.error} />
      <Stats
        items={[
          {
            label: "Observed services",
            value: rows.length,
            note: "Receiving application traffic",
          },
          {
            label: "Service requests",
            value: count(requests),
            note: "Counted before trace sampling",
          },
          {
            label: "Service error rate",
            value: percent(requests ? errors / requests : null),
            note: `${errors} observed error spans`,
            color: errors ? "#cb3d50" : undefined,
          },
          {
            label: "Highest service p95",
            value: ms(
              rows.length ? Math.max(...rows.map((row) => row.p95 || 0)) : null,
            ),
            note: "Approximate histogram quantile",
          },
        ]}
      />
      <Panel
        title="Your services"
        subtitle="Application requests · sorted by request volume"
        action={
          <Link to="/traces" className="text-button">
            Explore traces →
          </Link>
        }
      >
        <ServicesTable rows={rows} query={query} />
      </Panel>
      <Panel
        title="Connected, end to end"
        subtitle="Every edge is an observed outbound call."
        action={
          <Link to="/map" className="text-button">
            Open service map →
          </Link>
        }
      >
        <Status loading={map.isPending} error={map.error} />
        {map.data && <ServiceGraph data={map.data} />}
      </Panel>
      <p className="footnote">
        Service requests count each service's work. They are not a count of
        unique user requests. Metrics use received spans before sampling.
      </p>
    </>
  );
}
function MapPage() {
  const { query } = useRange();
  const map = useApi<ServiceMapData>(`/service-map?${query}`);
  return (
    <>
      <Title
        eyebrow="DEPENDENCIES"
        title="See the whole request."
        description="Follow service calls, spot slow edges, and open the service that needs attention."
      />
      <Status loading={map.isPending} error={map.error} />
      {map.data && (
        <>
          <Panel
            title="Service map"
            subtitle="p95 latency and error rate on each dependency"
          >
            <ServiceGraph data={map.data} />
          </Panel>
          <Panel title="Dependency health" subtitle={map.data.edgeCompleteness}>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Caller</th>
                    <th>Dependency</th>
                    <th>Type</th>
                    <th>Calls</th>
                    <th>Error rate</th>
                    <th>p95</th>
                  </tr>
                </thead>
                <tbody>
                  {map.data.edges.map((edge) => (
                    <tr key={`${edge.caller}:${edge.callee}`}>
                      <td>
                        <SLink service={edge.caller} />
                      </td>
                      <td>
                        {edge.callee_kind === "service" ? (
                          <SLink service={edge.callee} />
                        ) : (
                          <span className="mono">{edge.callee}</span>
                        )}
                      </td>
                      <td>
                        <span className="badge neutral">
                          {edge.callee_kind}
                        </span>
                      </td>
                      <td>{count(edge.calls)}</td>
                      <td className={edge.errors ? "error-text" : ""}>
                        {percent(edge.errorRate)}
                      </td>
                      <td>{ms(edge.p95)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </>
      )}
    </>
  );
}
function ServiceDetail() {
  const service = useParams().service || "";
  const { query } = useRange();
  const metrics = useApi<Page<Metric>>(`/services?${query}`);
  const series = useApi<Page<Metric>>(
    `/services/${encodeURIComponent(service)}/series?${query}`,
  );
  const ops = useApi<Page<Metric>>(
    `/services/${encodeURIComponent(service)}/operations?${query}`,
  );
  const slow = useApi<Page<TraceRow>>(
    `/traces?${query}&service=${encodeURIComponent(service)}&minMs=500&limit=5`,
  );
  const errors = useApi<Page<TraceRow>>(
    `/traces?${query}&service=${encodeURIComponent(service)}&errorsOnly=true&limit=5`,
  );
  const rules = useApi<Rule[]>("/alert-rules");
  const metric = metrics.data?.data.find((row) => row.service === service);
  const threshold = rules.data?.find(
    (rule) =>
      rule.enabled &&
      rule.service === service &&
      rule.kind === "latency" &&
      rule.percentile === 0.95,
  )?.threshold;
  return (
    <>
      <Title
        eyebrow="SERVICE DETAIL"
        title={service}
        description="Latency, errors, operations, and the traces behind them."
        action={
          <Link
            to={`/traces?service=${encodeURIComponent(service)}`}
            className="button"
          >
            Search service traces →
          </Link>
        }
      />
      <Status
        loading={series.isPending || metrics.isPending}
        error={series.error || metrics.error}
      />
      <Stats
        items={[
          {
            label: "Request rate",
            value: metric ? `${metric.rps.toFixed(2)} / s` : "—",
            note: "Application entry spans",
          },
          {
            label: "Error rate",
            value: percent(metric?.errorRate),
            note: `${metric?.errors || 0} errors in window`,
          },
          {
            label: "p95 latency",
            value: ms(metric?.p95),
            note: "Mergeable histogram",
          },
          {
            label: "p99 latency",
            value: ms(metric?.p99),
            note: "Tail request duration",
          },
        ]}
      />
      <Panel
        title="Request latency"
        subtitle="p50 / p95 / p99 · closed minute buckets"
      >
        <SeriesChart
          window={series.data?.window}
          rows={series.data?.data || []}
          threshold={threshold}
        />
      </Panel>
      <div className="two-columns">
        <Panel title="Throughput" subtitle="Requests per second">
          <SeriesChart
            window={series.data?.window}
            rows={series.data?.data || []}
            field="rps"
          />
        </Panel>
        <Panel
          title="Errors"
          subtitle="Fraction of received application requests"
        >
          <SeriesChart
            window={series.data?.window}
            rows={series.data?.data || []}
            field="errorRate"
          />
        </Panel>
      </div>
      <Panel
        title="Operations"
        subtitle="Latency and errors by instrumented operation"
      >
        <Status loading={ops.isPending} error={ops.error} />
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Operation</th>
                <th>Requests</th>
                <th>Error rate</th>
                <th>p50</th>
                <th>p95</th>
                <th>p99</th>
              </tr>
            </thead>
            <tbody>
              {ops.data?.data.map((row) => (
                <tr key={row.operation}>
                  <td className="mono">{row.operation}</td>
                  <td>{count(row.requests)}</td>
                  <td>{percent(row.errorRate)}</td>
                  <td>{ms(row.p50)}</td>
                  <td>{ms(row.p95)}</td>
                  <td>{ms(row.p99)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
      <Panel
        title="Slow traces"
        subtitle="Retained requests with root duration ≥ 500 ms"
      >
        <Status loading={slow.isPending} error={slow.error} />
        <TraceTable rows={slow.data?.data || []} />
      </Panel>
      <Panel
        title="Error traces"
        subtitle="Retained traces involving this service"
      >
        <Status loading={errors.isPending} error={errors.error} />
        <TraceTable rows={errors.data?.data || []} />
      </Panel>
    </>
  );
}
function TraceSearch() {
  const { query } = useRange();
  const [params, setParams] = useSearchParams();
  const filter = new URLSearchParams();
  for (const key of ["service", "minMs", "errorsOnly", "cursor"])
    if (params.get(key)) filter.set(key, params.get(key)!);
  const traces = useApi<Page<TraceRow>>(`/traces?${query}&${filter}&limit=50`);
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams(params);
    for (const key of ["service", "minMs", "errorsOnly", "cursor"])
      next.delete(key);
    for (const key of ["service", "minMs"])
      if (form.get(key)) next.set(key, String(form.get(key)));
    if (form.get("errorsOnly")) next.set("errorsOnly", "true");
    setParams(next);
  }
  return (
    <>
      <Title
        eyebrow="REQUEST EXPLORER"
        title="Every trace tells a story."
        description="Search retained end-to-end requests and inspect the work behind them."
      />
      <form
        className="filter-bar"
        onSubmit={search}
        key={`${params.get("service")}:${params.get("minMs")}:${params.get("errorsOnly")}`}
      >
        <label>
          Service
          <input
            name="service"
            placeholder="All services"
            defaultValue={params.get("service") || ""}
          />
        </label>
        <label>
          Minimum duration (ms)
          <input
            name="minMs"
            type="number"
            min="0"
            step="any"
            placeholder="Any duration"
            defaultValue={params.get("minMs") || ""}
          />
        </label>
        <label className="check">
          <input
            name="errorsOnly"
            type="checkbox"
            defaultChecked={params.get("errorsOnly") === "true"}
          />
          Errors only
        </label>
        <button className="button" type="submit">
          Search traces
        </button>
      </form>
      <div className="notice">
        <span className="info-symbol">i</span>Trace search shows kept traces.
        Metrics include all received application requests. Slow and error
        retention depends on spans available at finalization.
      </div>
      <Status loading={traces.isPending} error={traces.error} />
      <Panel
        title="Retained traces"
        subtitle={
          traces.data
            ? `${traces.data.window.from.slice(11, 16)}–${traces.data.window.to.slice(11, 16)} UTC · newest first`
            : "Newest first"
        }
      >
        <TraceTable rows={traces.data?.data || []} />
        {traces.data?.nextCursor && (
          <div className="panel-footer">
            <button
              className="button secondary"
              onClick={() => {
                const next = new URLSearchParams(params);
                next.set("cursor", traces.data!.nextCursor!);
                setParams(next);
              }}
            >
              Next page →
            </button>
          </div>
        )}
      </Panel>
    </>
  );
}
function TraceView() {
  const id = useParams().id || "";
  const trace = useApi<TraceData>(`/traces/${encodeURIComponent(id)}`, false);
  const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSelected(null);
    };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, []);
  const rows = useMemo(
    () => (trace.data ? waterfallLayout(trace.data) : []),
    [trace.data],
  );
  const span = rows.find((row) => row.span.spanId === selected)?.span;
  const serviceCount = new Set(rows.map((row) => row.span.service)).size;
  const total = trace.data?.criticalPath.durationNs || 0;
  return (
    <>
      <Title
        eyebrow="TRACE WATERFALL"
        title="Follow the time."
        description={id}
        action={
          <Link to="/traces" className="button secondary">
            ← All traces
          </Link>
        }
      />
      <Status loading={trace.isPending} error={trace.error} />
      {trace.data && (
        <>
          <Stats
            items={[
              {
                label: "Root duration",
                value: ms(total / 1e6),
                note: "End-to-end observed request",
              },
              {
                label: "Services",
                value: serviceCount,
                note: "Distinct instrumented services",
              },
              {
                label: "Spans",
                value: rows.length,
                note: "Select a row to inspect attributes",
              },
              {
                label: "Error spans",
                value: rows.filter((row) => row.span.error).length,
                note: trace.data.criticalPath.approximate
                  ? "Timing attribution is approximate"
                  : "Synchronous timing attribution",
              },
            ]}
          />
          {(trace.data.metadata.knownIncomplete ||
            trace.data.tree.knownIncomplete ||
            trace.data.criticalPath.approximate) && (
            <div className="notice warning">
              This trace contains missing, late, cyclic, or adjusted timing
              information. Highlighting is approximate. Finalized by{" "}
              {trace.data.metadata.finalizationReason};{" "}
              {trace.data.metadata.lateSpanCount} late spans.
            </div>
          )}
          <Panel
            title="Request timeline"
            subtitle="Highlighted segments show synchronous critical-path time; overlapping child calls do not add wall time."
            action={<span className="badge blue">Critical path</span>}
          >
            <div className="waterfall-scroll">
              <div className="waterfall">
                <div className="waterfall-head">
                  <span>Service / operation</span>
                  <div>
                    {[0, 0.25, 0.5, 0.75, 1].map((fraction) => (
                      <span
                        key={fraction}
                        style={{ left: `${fraction * 100}%` }}
                      >
                        {ms((total * fraction) / 1e6)}
                      </span>
                    ))}
                  </div>
                  <span>Duration</span>
                </div>
                {rows.map((row) => (
                  <button
                    type="button"
                    className={`waterfall-row ${row.critical ? "on-path" : ""} ${selected === row.span.spanId ? "selected" : ""}`}
                    key={row.span.spanId}
                    onClick={() => setSelected(row.span.spanId)}
                    aria-label={`Inspect ${row.span.service} ${row.span.name}`}
                  >
                    <span
                      className="waterfall-label"
                      style={{
                        paddingLeft: `${14 + Math.min(8, row.depth) * 14}px`,
                      }}
                    >
                      <i
                        style={{ background: serviceColor(row.span.service) }}
                      />
                      <strong>{row.span.service}</strong>
                      <small>{row.span.name}</small>
                      {row.span.error && <b className="error-text">!</b>}
                      {row.skewAdjusted && <em>skew</em>}
                    </span>
                    <span className="waterfall-track">
                      <span
                        className="span-bar"
                        style={{
                          left: `${Math.min(100, Math.max(0, row.left))}%`,
                          width: `${Math.max(0, Math.min(100 - Math.max(0, row.left), Math.max(0.3, row.width)))}%`,
                          background: serviceColor(row.span.service),
                          opacity: row.critical ? 1 : 0.35,
                        }}
                      />
                      {trace
                        .data!.criticalPath.segments.filter(
                          (segment) => segment.spanId === row.span.spanId,
                        )
                        .map((segment, index) => {
                          const display = trace.data!.criticalPath.display.find(
                            (item) => item.spanId === row.span.spanId,
                          );
                          const origin =
                            (display?.startNs ?? row.span.startNs) -
                            (row.left / 100) * total;
                          return (
                            <span
                              key={index}
                              className="critical-segment"
                              style={{
                                left: `${Math.max(0, ((segment.startNs - origin) / total) * 100)}%`,
                                width: `${Math.max(0.2, (segment.durationNs / total) * 100)}%`,
                              }}
                            />
                          );
                        })}
                    </span>
                    <span className="waterfall-duration">
                      {ms(row.span.durationNs / 1e6)}
                    </span>
                  </button>
                ))}
              </div>
            </div>
            <div className="panel-footer muted">
              {trace.data.finalizationNotice}
            </div>
          </Panel>
          {span && (
            <aside className="span-drawer" aria-label="Span details">
              <div className="panel-heading">
                <div>
                  <span className="eyebrow">SPAN DETAILS</span>
                  <h2>{span.name}</h2>
                </div>
                <button
                  className="icon-button"
                  aria-label="Close span details"
                  onClick={() => setSelected(null)}
                >
                  ×
                </button>
              </div>
              <div className="drawer-content">
                <dl>
                  <dt>Service</dt>
                  <dd>
                    <SLink service={span.service} />
                  </dd>
                  <dt>Span ID</dt>
                  <dd className="mono">{span.spanId}</dd>
                  <dt>Duration</dt>
                  <dd>{ms(span.durationNs / 1e6)}</dd>
                  <dt>Kind</dt>
                  <dd>{span.kind}</dd>
                  <dt>Status</dt>
                  <dd>{span.error ? "Error" : "No error recorded"}</dd>
                  {span.peer && (
                    <>
                      <dt>Peer</dt>
                      <dd>{span.peer}</dd>
                    </>
                  )}
                </dl>
                <h3>Retained attributes</h3>
                <p className="muted">
                  Sensitive-key matches are removed at ingest. This is not a
                  general content or PII sanitizer.
                </p>
                <dl className="attributes">
                  {Object.entries(span.attributes).map(([key, value]) => (
                    <div key={key}>
                      <dt>{key}</dt>
                      <dd>{value}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            </aside>
          )}
        </>
      )}
    </>
  );
}
const defaultRule: Rule = {
  id: null,
  name: "",
  service: "",
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
function AlertsPage() {
  const client = useQueryClient();
  const rules = useApi<Rule[]>("/alert-rules");
  const events = useApi<AlertEvent[]>("/alert-events");
  const [editing, setEditing] = useState<Rule | null>(null);
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState<{
    value: number | null;
    requests: number;
    status: string;
  } | null>(null);
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    const problem = validateRule(editing);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError("");
    try {
      await request(`/alert-rules${editing.id ? `/${editing.id}` : ""}`, {
        method: editing.id ? "PUT" : "POST",
        body: JSON.stringify(editing),
      });
      setEditing(null);
      await client.invalidateQueries();
    } catch (error) {
      setFormError((error as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function remove(rule: Rule) {
    setBusy(true);
    setFormError("");
    try {
      await request(`/alert-rules/${rule.id}`, { method: "DELETE" });
      await client.invalidateQueries();
    } catch (error) {
      setFormError((error as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function edit(patch: Partial<Rule>) {
    setEditing((previous) => (previous ? { ...previous, ...patch } : previous));
    setPreview(null);
  }
  return (
    <>
      <Title
        eyebrow="INCIDENT SIGNALS"
        title="Catch the meaningful changes."
        description="Sustained breaches, recovery hysteresis, and traces to investigate."
        action={
          <button
            className="button"
            onClick={() => {
              setEditing({ ...defaultRule });
              setFormError("");
              setPreview(null);
            }}
          >
            + Create rule
          </button>
        }
      />
      <Status
        loading={rules.isPending || events.isPending}
        error={rules.error || events.error}
      />
      {formError && !editing && (
        <div className="notice danger" role="alert">
          {formError}
        </div>
      )}
      <Panel
        title="Alert events"
        subtitle="No data is explicit. It never silently resolves a firing incident."
      >
        {events.data?.length ? (
          <div className="event-list">
            {events.data.map((event) => (
              <article className="event" key={event.id}>
                <div className={`event-marker ${event.state}`} />
                <div className="event-body">
                  <div className="event-title">
                    <h3>{event.rule_name}</h3>
                    <span
                      className={`badge ${event.state === "firing" ? "red" : event.state === "pending" ? "amber" : "green"}`}
                    >
                      {event.state}
                    </span>
                    {event.no_data && (
                      <span className="badge amber">No data</span>
                    )}
                  </div>
                  <p>
                    <SLink service={event.service} /> · Started{" "}
                    {new Date(event.started_at).toLocaleString()}
                    {event.resolved_at &&
                      ` · Resolved ${new Date(event.resolved_at).toLocaleTimeString()}`}
                  </p>
                  <div className="event-values">
                    Current{" "}
                    <strong>
                      {event.kind === "latency"
                        ? ms(event.current_value)
                        : percent(event.current_value)}
                    </strong>{" "}
                    Peak{" "}
                    <strong>
                      {event.kind === "latency"
                        ? ms(event.peak_value)
                        : percent(event.peak_value)}
                    </strong>
                    {event.resolution && (
                      <span>{event.resolution.replaceAll("_", " ")}</span>
                    )}
                  </div>
                  <div className="exemplars">
                    {event.exemplar_trace_ids.map((id) => (
                      <TLink key={id} id={id} />
                    ))}
                    {event.exemplar_status === "unavailable" && (
                      <span className="muted">
                        Exemplars unavailable; telemetry may still be arriving.
                      </span>
                    )}
                  </div>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <Empty>
            No alert events. Sustained breaches will appear here with example
            traces.
          </Empty>
        )}
      </Panel>
      <Panel
        title="Detection rules"
        subtitle="Minute-aligned windows · evaluation every 15 seconds"
      >
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Rule</th>
                <th>Service</th>
                <th>Condition</th>
                <th>Window / sustained</th>
                <th>State</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.data?.map((rule) => (
                <tr key={rule.id}>
                  <td>
                    <strong>{rule.name}</strong>
                    {rule.operation && (
                      <span className="cell-sub mono">{rule.operation}</span>
                    )}
                  </td>
                  <td>
                    <SLink service={rule.service} />
                  </td>
                  <td>
                    {rule.kind === "latency"
                      ? `p${(rule.percentile || 0.95) * 100} > ${ms(rule.threshold)}`
                      : `Errors > ${percent(rule.threshold)}`}
                  </td>
                  <td>
                    {rule.windowSec / 60} min / {rule.forSec} s
                  </td>
                  <td>
                    <span
                      className={`badge ${rule.enabled ? "green" : "neutral"}`}
                    >
                      {rule.enabled ? "Enabled" : "Disabled"}
                    </span>
                  </td>
                  <td>
                    <div className="row-actions">
                      <button
                        className="text-button"
                        onClick={() => {
                          setEditing({ ...rule });
                          setPreview(null);
                          setFormError("");
                        }}
                      >
                        Edit
                      </button>
                      <button
                        className="text-button error-text"
                        disabled={busy}
                        onClick={() => void remove(rule)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>
      {editing && (
        <Modal onClose={() => setEditing(null)}>
          <section className="rule-modal">
            <div className="panel-heading">
              <h2 id="rule-title">
                {editing.id ? "Edit detection rule" : "Create detection rule"}
              </h2>
              <button
                className="icon-button"
                aria-label="Close rule editor"
                onClick={() => setEditing(null)}
              >
                ×
              </button>
            </div>
            <form onSubmit={save}>
              <div className="form-grid">
                <label>
                  Rule name
                  <input
                    required
                    maxLength={128}
                    value={editing.name}
                    onChange={(event) => edit({ name: event.target.value })}
                    autoFocus
                  />
                </label>
                <label>
                  Service
                  <input
                    required
                    maxLength={128}
                    value={editing.service}
                    onChange={(event) => edit({ service: event.target.value })}
                    placeholder="checkout"
                  />
                </label>
                <label>
                  Operation (optional)
                  <input
                    maxLength={256}
                    value={editing.operation || ""}
                    onChange={(event) =>
                      edit({ operation: event.target.value || null })
                    }
                    placeholder="All operations"
                  />
                </label>
                <label>
                  Signal
                  <select
                    value={editing.kind}
                    onChange={(event) =>
                      edit({
                        kind: event.target.value as Rule["kind"],
                        threshold:
                          event.target.value === "latency" ? 500 : 0.05,
                        percentile:
                          event.target.value === "latency" ? 0.95 : null,
                      })
                    }
                  >
                    <option value="latency">Latency</option>
                    <option value="error_rate">Error rate</option>
                  </select>
                </label>
                {editing.kind === "latency" && (
                  <label>
                    Percentile
                    <select
                      value={editing.percentile || 0.95}
                      onChange={(event) =>
                        edit({ percentile: Number(event.target.value) })
                      }
                    >
                      <option value={0.5}>p50</option>
                      <option value={0.95}>p95</option>
                      <option value={0.99}>p99</option>
                    </select>
                  </label>
                )}
                <label>
                  Threshold (
                  {editing.kind === "latency" ? "ms" : "fraction, 0–1"})
                  <input
                    type="number"
                    required
                    min="0.000001"
                    max={editing.kind === "latency" ? 86400000 : 1}
                    step="any"
                    value={editing.threshold}
                    onChange={(event) =>
                      edit({ threshold: Number(event.target.value) })
                    }
                  />
                </label>
                <label>
                  Window (seconds)
                  <input
                    type="number"
                    min="60"
                    max="3600"
                    step="60"
                    value={editing.windowSec}
                    onChange={(event) =>
                      edit({ windowSec: Number(event.target.value) })
                    }
                  />
                </label>
                <label>
                  Sustained for (seconds)
                  <input
                    type="number"
                    min="0"
                    max="3600"
                    value={editing.forSec}
                    onChange={(event) =>
                      edit({ forSec: Number(event.target.value) })
                    }
                  />
                </label>
                <label>
                  Minimum requests
                  <input
                    type="number"
                    min="1"
                    max="1000000"
                    value={editing.minRequests}
                    onChange={(event) =>
                      edit({ minRequests: Number(event.target.value) })
                    }
                  />
                </label>
                <label>
                  Recovery ratio
                  <input
                    type="number"
                    min="0.01"
                    max="0.99"
                    step="0.01"
                    value={editing.resolveRatio}
                    onChange={(event) =>
                      edit({ resolveRatio: Number(event.target.value) })
                    }
                  />
                </label>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={editing.enabled}
                    onChange={(event) =>
                      edit({ enabled: event.target.checked })
                    }
                  />
                  Enabled
                </label>
              </div>
              {formError && (
                <div className="notice danger" role="alert">
                  {formError}
                </div>
              )}
              {preview && (
                <div className="notice">
                  Current value:{" "}
                  <strong>
                    {editing.kind === "latency"
                      ? ms(preview.value)
                      : percent(preview.value)}
                  </strong>{" "}
                  · {preview.requests} requests ·{" "}
                  {preview.status.replaceAll("_", " ")}
                </div>
              )}
              <div className="modal-actions">
                <button
                  type="button"
                  className="button secondary"
                  onClick={async () => {
                    const problem = validateRule(editing);
                    if (problem) {
                      setFormError(problem);
                      return;
                    }
                    try {
                      setPreview(
                        await request("/alert-rules/preview", {
                          method: "POST",
                          body: JSON.stringify(editing),
                        }),
                      );
                      setFormError("");
                    } catch (error) {
                      setFormError((error as Error).message);
                    }
                  }}
                >
                  Preview current value
                </button>
                <button type="submit" className="button" disabled={busy}>
                  {busy ? "Saving…" : "Save rule"}
                </button>
              </div>
            </form>
          </section>
        </Modal>
      )}
    </>
  );
}
export function App() {
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const { minutes } = useRange();
  const pipeline = useApi<PipelineStats>("/pipeline");
  const alerts = useApi<AlertEvent[]>("/alert-events?state=open");
  const firing =
    alerts.data?.filter((event) => event.state === "firing").length || 0;
  useEffect(() => {
    document.title = `TraceScope · ${location.pathname.startsWith("/traces/") ? "Request trace" : location.pathname.startsWith("/services/") ? "Service detail" : location.pathname === "/map" ? "Service map" : location.pathname === "/alerts" ? "Alerts" : location.pathname === "/traces" ? "Traces" : "Overview"}`;
  }, [location.pathname]);
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Link to="/" className="brand">
          <svg viewBox="0 0 30 30" aria-hidden="true">
            <path d="M2 16h6l4-10 6 19 4-9h6" />
          </svg>
          <span>
            TraceScope<small>APPLICATION PERFORMANCE</small>
          </span>
        </Link>
        <div className="workspace">
          <span className="workspace-icon">T</span>
          <div>
            Demo shop<small>Local environment</small>
          </div>
          <span className="live-dot" />
        </div>
        <div className="nav-label">OBSERVE</div>
        <nav aria-label="Main navigation">
          {[
            { path: "/", icon: "◫", label: "Overview" },
            { path: "/map", icon: "◇", label: "Service map" },
            { path: "/traces", icon: "⌁", label: "Traces" },
            { path: "/alerts", icon: "◉", label: "Alerts" },
          ].map((item) => (
            <NavLink
              key={item.path}
              to={{ pathname: item.path, search: `?minutes=${minutes}` }}
              end={item.path === "/"}
            >
              <span className="nav-icon">{item.icon}</span>
              {item.label}
              {item.path === "/alerts" && firing > 0 && (
                <b className="nav-count">{firing}</b>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span
            className={`live-dot ${pipeline.data?.writerHealthy ? "" : "warn"}`}
          />
          <div>
            {pipeline.error
              ? "Backend unavailable"
              : pipeline.data?.writerHealthy
                ? "Pipeline connected"
                : "Checking pipeline"}
            <small>
              {pipeline.data
                ? `${pipeline.data.bufferedSpans} buffered spans`
                : "Waiting for telemetry"}
            </small>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            Demo shop <span>/</span>{" "}
            <strong>
              {location.pathname === "/"
                ? "Overview"
                : location.pathname.split("/")[1] === "map"
                  ? "Service map"
                  : location.pathname.split("/")[1]}
            </strong>
            <span className="environment">LOCAL</span>
          </div>
          <div className="time-controls">
            <span className="utc-label">Closed minute windows</span>
            <label className="sr-only" htmlFor="time-window">
              Time window
            </label>
            <select
              id="time-window"
              value={minutes}
              onChange={(event) => {
                const next = new URLSearchParams(params);
                next.set("minutes", event.target.value);
                next.delete("cursor");
                setParams(next);
              }}
            >
              {[
                [15, "Last 15 minutes"],
                [30, "Last 30 minutes"],
                [60, "Last hour"],
                [360, "Last 6 hours"],
                [720, "Last 12 hours"],
                [1440, "Last 24 hours"],
              ].map(([value, label]) => (
                <option value={value} key={value}>
                  {label}
                </option>
              ))}
            </select>
            <button
              className="refresh"
              onClick={() => void client.invalidateQueries()}
              aria-label="Refresh telemetry"
            >
              ↻ <span>Refresh</span>
            </button>
          </div>
        </header>
        <main id="main-content">
          {pipeline.data && !pipeline.data.writerHealthy && (
            <div className="notice danger" role="alert">
              Storage writes are failing. Buffered telemetry is bounded; new
              batches may receive backpressure.
            </div>
          )}
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/map" element={<MapPage />} />
            <Route path="/services/:service" element={<ServiceDetail />} />
            <Route path="/traces" element={<TraceSearch />} />
            <Route path="/traces/:id" element={<TraceView />} />
            <Route path="/alerts" element={<AlertsPage />} />
            <Route
              path="*"
              element={
                <Empty>
                  Page not found. <Link to="/">Return to overview</Link>
                </Empty>
              }
            />
          </Routes>
          <footer className="footer">
            <span>
              TraceScope <b>·</b> Real instrumented services. Generated load.
            </span>
            <span>UTC minute-aligned metrics · 7-day trace retention</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
