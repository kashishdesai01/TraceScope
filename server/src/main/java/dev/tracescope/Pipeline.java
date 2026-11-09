package dev.tracescope;

import dev.tracescope.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single-node pipeline. All admission mutations share one lock; database I/O runs outside it. */
@Component
public class Pipeline {
  public static final long DEDUPE_MS = 600_000;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC);
  private static final Map<String, String> TARGETS =
      Map.of(
          "gateway",
          "gateway",
          "checkout",
          "checkout",
          "payment",
          "payment",
          "inventory",
          "inventory",
          "payment-unavailable",
          "payment",
          "inventory-unavailable",
          "inventory");
  private final Settings.Values limits;
  private final Storage storage;
  private final MeterRegistry meters;
  private final Clock clock;
  private final AtomicLong version = new AtomicLong();
  private final Map<String, Long> seen = new HashMap<>();
  private final Map<String, State> active = new LinkedHashMap<>();
  private final Map<String, Decision> decisions = new HashMap<>();
  private final Map<Key, Cell> aggregates = new LinkedHashMap<>();
  private final Deque<Frame> writes = new ArrayDeque<>();
  private long retainedBytes;
  private int retainedSpans;
  private long lastFlush;
  private boolean writerBusy;
  private volatile boolean writerHealthy = true;

  @Autowired
  public Pipeline(Settings.Values limits, Storage storage, MeterRegistry meters) {
    this(limits, storage, meters, Clock.systemUTC());
  }

  Pipeline(Settings.Values limits, Storage storage, MeterRegistry meters, Clock clock) {
    this.limits = limits;
    this.storage = storage;
    this.meters = meters;
    this.clock = clock;
    lastFlush = clock.millis();
    meters.gauge("pipeline_buffered_spans", this, p -> p.stats().bufferedSpans());
    meters.gauge("pipeline_retained_estimated_bytes", this, p -> p.stats().estimatedBytes());
    meters.gauge("pipeline_writer_queue", this, p -> p.stats().writerFrames());
  }

  public record Stats(
      int bufferedSpans,
      long estimatedBytes,
      int identities,
      int activeTraces,
      int decisions,
      int aggregateKeys,
      int writerFrames,
      boolean writerHealthy) {}

  public synchronized Stats stats() {
    return new Stats(
        retainedSpans,
        estimatedBytes(),
        seen.size(),
        active.size(),
        decisions.size(),
        aggregates.size(),
        writes.size(),
        writerHealthy);
  }

  private long estimatedBytes() {
    return retainedBytes
        + seen.size() * 192L
        + decisions.values().stream().mapToLong(d -> d.summary.bytes()).sum()
        + aggregates.size() * 1800L;
  }

  public static class Full extends RuntimeException {
    Full() {
      super("Pipeline capacity exhausted; retry the complete batch");
    }
  }

  private record Key(long minute, String service, String operation, String callee, String kind) {}

  private static final class Cell {
    long count, errors;
    final LatencyHistogram histogram = new LatencyHistogram();

    void add(long duration, boolean error) {
      count++;
      if (error) errors++;
      histogram.record(duration);
    }
  }

  private static final class State {
    final long firstSeen;
    long lastSeen;
    final Map<String, Span> spans = new LinkedHashMap<>();

    State(long now) {
      firstSeen = now;
      lastSeen = now;
    }
  }

  private record Decision(Summary summary, String reason, long expires) {}

  record Frame(String id, Map<String, List<Map<String, Object>>> tables, long bytes, int spans) {}

  private record Root(long startNs, long durationNs, String service, String name) {}

  private static final class Summary {
    final String id;
    long start = Long.MAX_VALUE, end;
    Root root;
    final Set<String> services = new TreeSet<>();
    int count, errors, late;
    boolean incomplete;
    String finalization;

    Summary(String id) {
      this.id = id;
    }

    void add(Span s, boolean isLate) {
      start = Math.min(start, s.startNs());
      end = Math.max(end, s.endNs());
      services.add(s.service());
      count++;
      if (s.error()) errors++;
      if (s.parentSpanId().isEmpty() && (root == null || s.startNs() < root.startNs()))
        root = new Root(s.startNs(), s.durationNs(), s.service(), s.name());
      if (isLate) {
        late++;
        incomplete = true;
      }
    }

    long bytes() {
      return 1024L + services.stream().mapToLong(s -> 96L + s.length() * 2L).sum();
    }

    Map<String, Object> row(String reason, long revision) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("trace_id", id);
      r.put("start_time", time(start));
      r.put("end_time", time(end));
      r.put("duration_ns", root == null ? end - start : root.durationNs());
      r.put("root_service", root == null ? services.iterator().next() : root.service());
      r.put("root_name", root == null ? "[root missing]" : root.name());
      r.put("services", List.copyOf(services));
      r.put("span_count", count);
      r.put("error_count", errors);
      r.put("kept_reason", reason);
      r.put("known_incomplete", incomplete);
      r.put("finalization_reason", finalization);
      r.put("late_span_count", late);
      r.put("version", revision);
      return r;
    }
  }

  private static boolean applicationEntry(Span s) {
    return s.entry()
        && !s.route().startsWith("/actuator")
        && !s.route().startsWith("/chaos")
        && !s.name().matches("[A-Z]+ /(?:actuator|chaos)(?:[/ ].*)?");
  }

  private static long minute(long ns) {
    return ns / 60_000_000_000L * 60;
  }

  public static String time(long ns) {
    return TIME.format(Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L));
  }

  private long revision() {
    return version.updateAndGet(v -> Math.max(v + 1, clock.millis() * 1000));
  }

  private void expire(long now) {
    seen.values().removeIf(expires -> expires <= now);
    decisions.values().removeIf(d -> d.expires <= now);
  }

  /**
   * A Full exception occurs only before mutations. All unique valid spans are admitted together.
   */
  public synchronized int accept(List<Span> input) {
    long now = clock.millis();
    expire(now);
    Map<String, Span> unique = new LinkedHashMap<>();
    for (var s : input)
      if (!seen.containsKey(s.traceId() + s.spanId()))
        unique.putIfAbsent(s.traceId() + s.spanId(), s);
    if (unique.isEmpty()) return 0;
    Set<Key> newKeys = new HashSet<>();
    Map<String, Integer> perTrace = new HashMap<>();
    long bytes = 0;
    int spans = 0;
    for (var s : unique.values()) {
      if (applicationEntry(s))
        newKeys.add(new Key(minute(s.startNs()), s.service(), s.name(), "", ""));
      var decision = decisions.get(s.traceId());
      if (decision == null || !decision.reason.equals("dropped")) {
        bytes += s.estimatedBytes();
        spans++;
      }
      perTrace.merge(s.traceId(), 1, Integer::sum);
    }
    newKeys.removeAll(aggregates.keySet());
    long newTraceCount =
        perTrace.keySet().stream()
            .filter(id -> !active.containsKey(id) && !decisions.containsKey(id))
            .count();
    if (writes.size() >= limits.maxFrames()
        || seen.size() + unique.size() > limits.maxIdentities()
        || retainedSpans + spans > limits.maxSpans()
        || aggregates.size() + newKeys.size() > limits.maxKeys()
        || estimatedBytes()
                + bytes
                + unique.size() * 192L
                + newKeys.size() * 1800L
                + newTraceCount * 1024L
                + unique.size() * 512L
            > limits.maxBytes()) throw new Full();
    for (var e : perTrace.entrySet())
      if (e.getValue()
              + (active.containsKey(e.getKey())
                  ? active.get(e.getKey()).spans.size()
                  : decisions.containsKey(e.getKey()) ? decisions.get(e.getKey()).summary.count : 0)
          > limits.maxTraceSpans()) throw new Full();
    List<Span> late = new ArrayList<>();
    Set<String> revised = new LinkedHashSet<>();
    for (var s : unique.values()) {
      seen.put(s.traceId() + s.spanId(), now + DEDUPE_MS);
      if (applicationEntry(s))
        aggregates
            .computeIfAbsent(
                new Key(minute(s.startNs()), s.service(), s.name(), "", ""), k -> new Cell())
            .add(s.durationNs(), s.error());
      var decision = decisions.get(s.traceId());
      if (decision != null) {
        meters.counter("pipeline_late_spans_total", "decision", decision.reason).increment();
        if (!decision.reason.equals("dropped")) {
          decision.summary.add(s, true);
          late.add(s);
          revised.add(s.traceId());
          retainedBytes += s.estimatedBytes();
          retainedSpans++;
        }
      } else {
        var state = active.computeIfAbsent(s.traceId(), k -> new State(now));
        state.lastSeen = now;
        state.spans.put(s.spanId(), s);
        retainedBytes += s.estimatedBytes();
        retainedSpans++;
      }
    }
    if (!late.isEmpty()) {
      Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
      tables.put("spans", spanRows(late));
      tables.put("traces", new ArrayList<>());
      tables.put("trace_index", new ArrayList<>());
      for (String id : revised) {
        var d = decisions.get(id);
        addSummary(tables, d.summary, d.reason);
      }
      writes.add(
          new Frame(
              UUID.randomUUID().toString(),
              tables,
              late.stream().mapToLong(Span::estimatedBytes).sum(),
              late.size()));
    }
    meters.counter("pipeline_unique_spans_total").increment(unique.size());
    return unique.size();
  }

  private void addSummary(
      Map<String, List<Map<String, Object>>> tables, Summary summary, String reason) {
    long rev = revision();
    tables.get("traces").add(summary.row(reason, rev));
    tables
        .get("trace_index")
        .add(
            Map.of(
                "trace_id",
                summary.id,
                "start_time",
                time(summary.start),
                "end_time",
                time(summary.end),
                "version",
                rev));
  }

  private List<Map<String, Object>> spanRows(Collection<Span> spans) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var s : spans) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("trace_id", s.traceId());
      row.put("span_id", s.spanId());
      row.put("parent_span_id", s.parentSpanId());
      row.put("service", s.service());
      row.put("name", s.name());
      row.put("kind", s.kind().name().toLowerCase(Locale.ROOT));
      row.put("start_time", time(s.startNs()));
      row.put("duration_ns", s.durationNs());
      row.put("error", s.error());
      row.put("http_status", s.httpStatus());
      row.put("route", s.route());
      row.put("peer", s.peer());
      row.put("database", s.database());
      row.put("attributes", s.attributes());
      row.put("version", revision());
      rows.add(row);
    }
    return rows;
  }

  synchronized void prepare() {
    long now = clock.millis();
    expire(now);
    if (writes.size() >= limits.maxFrames()) return;
    Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
    tables.put("spans", new ArrayList<>());
    tables.put("traces", new ArrayList<>());
    tables.put("trace_index", new ArrayList<>());
    long frameBytes = 0;
    int frameSpans = 0;
    Iterator<Map.Entry<String, State>> iterator = active.entrySet().iterator();
    while (iterator.hasNext()) {
      var entry = iterator.next();
      var state = entry.getValue();
      boolean rootSeen = state.spans.values().stream().anyMatch(s -> s.parentSpanId().isEmpty());
      boolean old = now - state.firstSeen >= 30_000;
      if (!old && now - state.lastSeen < (rootSeen ? limits.idleMs() : 10_000)) continue;
      List<EdgeDeriver.Edge> edges = EdgeDeriver.derive(state.spans.values(), TARGETS);
      Set<Key> edgeKeys = new HashSet<>();
      for (var edge : edges)
        edgeKeys.add(
            new Key(minute(edge.startNs()), edge.caller(), "", edge.callee(), edge.calleeKind()));
      edgeKeys.removeAll(aggregates.keySet());
      var summary = new Summary(entry.getKey());
      for (var s : state.spans.values()) summary.add(s, false);
      if (aggregates.size() + edgeKeys.size() > limits.maxKeys()
          || estimatedBytes() + edgeKeys.size() * 1800L + summary.bytes() > limits.maxBytes())
        continue;
      for (var edge : edges)
        aggregates
            .computeIfAbsent(
                new Key(
                    minute(edge.startNs()), edge.caller(), "", edge.callee(), edge.calleeKind()),
                k -> new Cell())
            .add(edge.durationNs(), edge.error());
      summary.incomplete = TraceTree.build(state.spans.values()).knownIncomplete() || old;
      summary.finalization = old ? "max_age" : rootSeen ? "root_idle" : "missing_root_idle";
      String reason = Sampler.reason(state.spans.values(), limits.samplingRate(), 500_000_000);
      decisions.put(entry.getKey(), new Decision(summary, reason, now + DEDUPE_MS));
      long bytes = state.spans.values().stream().mapToLong(Span::estimatedBytes).sum();
      if (reason.equals("dropped")) {
        retainedBytes -= bytes;
        retainedSpans -= state.spans.size();
      } else {
        tables.get("spans").addAll(spanRows(state.spans.values()));
        addSummary(tables, summary, reason);
        frameBytes += bytes;
        frameSpans += state.spans.size();
      }
      iterator.remove();
    }
    if (!tables.get("spans").isEmpty())
      writes.add(new Frame(UUID.randomUUID().toString(), tables, frameBytes, frameSpans));
    if (now - lastFlush >= limits.flushMs()
        && !aggregates.isEmpty()
        && writes.size() < limits.maxFrames()) {
      Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
      rows.put("red_minute", new ArrayList<>());
      rows.put("edges_minute", new ArrayList<>());
      for (var e : aggregates.entrySet()) {
        Key key = e.getKey();
        Cell cell = e.getValue();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("minute", time(key.minute * 1_000_000_000L));
        row.put("errors", cell.errors);
        row.put("latency_buckets", cell.histogram.buckets());
        if (key.callee.isEmpty()) {
          row.put("service", key.service);
          row.put("operation", key.operation);
          row.put("requests", cell.count);
          rows.get("red_minute").add(row);
        } else {
          row.put("caller", key.service);
          row.put("callee", key.callee);
          row.put("callee_kind", key.kind);
          row.put("calls", cell.count);
          rows.get("edges_minute").add(row);
        }
      }
      long bytes = aggregates.size() * 1800L;
      aggregates.clear();
      retainedBytes += bytes;
      writes.add(new Frame(UUID.randomUUID().toString(), rows, bytes, 0));
      lastFlush = now;
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    prepare();
    synchronized (this) {
      if (writerBusy) return;
      writerBusy = true;
    }
    try {
      for (int i = 0; i < 8; i++) {
        Frame frame;
        synchronized (this) {
          frame = writes.peek();
        }
        if (frame == null) break;
        try {
          for (var table : frame.tables.entrySet())
            storage.insert(table.getKey(), table.getValue(), frame.id + ":" + table.getKey());
          synchronized (this) {
            writes.removeFirst();
            retainedBytes -= frame.bytes;
            retainedSpans -= frame.spans;
          }
          writerHealthy = true;
        } catch (RuntimeException failure) {
          writerHealthy = false;
          meters.counter("writer_failures_total").increment();
          org.slf4j.LoggerFactory.getLogger(Pipeline.class)
              .warn("Writer unavailable; bounded frame {} retained for retry", frame.id);
          break;
        }
      }
    } finally {
      synchronized (this) {
        writerBusy = false;
      }
    }
  }
}
