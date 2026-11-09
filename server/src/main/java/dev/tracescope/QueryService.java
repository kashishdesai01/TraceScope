package dev.tracescope;

import dev.tracescope.model.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QueryService {
  private final Storage storage;

  public QueryService(Storage storage) {
    this.storage = storage;
  }

  public record Window(Instant from, Instant to, String resolution) {
    public long seconds() {
      return to.getEpochSecond() - from.getEpochSecond();
    }
  }

  public record Page(Window window, List<Map<String, Object>> data, String nextCursor) {}

  public static Window window(Instant from, Instant to) {
    if (from != null
        && to != null
        && Duration.between(
                    from.truncatedTo(ChronoUnit.MINUTES), to.truncatedTo(ChronoUnit.MINUTES))
                .getSeconds()
            > 86400) throw new IllegalArgumentException("Requested range cannot exceed 24 hours");
    Instant closed = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    Instant end = (to == null || to.isAfter(closed) ? closed : to).truncatedTo(ChronoUnit.MINUTES);
    Instant start = (from == null ? end.minusSeconds(900) : from).truncatedTo(ChronoUnit.MINUTES);
    if (!start.isBefore(end) || Duration.between(start, end).getSeconds() > 86400)
      throw new IllegalArgumentException(
          "Range must be 1 minute to 24 hours after UTC minute alignment");
    return new Window(start, end, "UTC minute-aligned; current partial minute excluded");
  }

  public Page services(Window w) {
    return red(w, null, false, 60);
  }

  public Page operations(Window w, String service) {
    return red(w, service, true, 60);
  }

  public Page series(Window w, String service, int step) {
    if (step < 60 || step > 3600 || step % 60 != 0)
      throw new IllegalArgumentException("step must be a multiple of 60 between 60 and 3600");
    return red(w, service, false, step);
  }

  private Page red(Window w, String service, boolean operations, int step) {
    boolean series = service != null && !operations;
    String group =
        operations
            ? "service,operation"
            : series
                ? "service,toStartOfInterval(minute,INTERVAL "
                    + step
                    + " SECOND) AS time,toUnixTimestamp(time) AS bucket_epoch"
                : "service";
    String sql =
        "SELECT "
            + group
            + ",sum(requests) AS requests,sum(errors) AS errors,sumForEach(latency_buckets) AS buckets FROM red_minute WHERE minute>=toDateTime(?) AND minute<toDateTime(?)"
            + (service == null ? "" : " AND service=?")
            + " GROUP BY "
            + (operations ? "service,operation" : series ? "service,time" : "service")
            + " ORDER BY "
            + (series ? "time" : "requests DESC")
            + " LIMIT 1440";
    List<Object> params = new ArrayList<>(List.of(w.from.getEpochSecond(), w.to.getEpochSecond()));
    if (service != null) params.add(service);
    var rows = storage.query(sql, params.toArray());
    Map<Long, Map<String, Object>> byBucket = new HashMap<>();
    for (var row : rows) {
      long seconds = w.seconds();
      if (series) {
        long bucketStart = number(row.remove("bucket_epoch"));
        row.put("time", Instant.ofEpochSecond(bucketStart).toString());
        byBucket.put(bucketStart, row);
        seconds =
            Math.min(bucketStart + step, w.to.getEpochSecond())
                - Math.max(bucketStart, w.from.getEpochSecond());
      }
      decorate(row, seconds);
    }
    if (series) {
      List<Map<String, Object>> dense = new ArrayList<>();
      long first = Math.floorDiv(w.from.getEpochSecond(), step) * step;
      for (long bucket = first; bucket < w.to.getEpochSecond(); bucket += step) {
        var row = byBucket.get(bucket);
        if (row == null) {
          row = new LinkedHashMap<>();
          row.put("service", service);
          row.put("time", Instant.ofEpochSecond(bucket).toString());
          row.put("requests", 0L);
          row.put("errors", 0L);
          row.put("rps", 0.0);
          row.put("errorRate", null);
          for (String key : List.of("p50", "p95", "p99")) {
            row.put(key, null);
            row.put(key + "Range", "empty");
          }
        }
        dense.add(row);
      }
      rows = dense;
    }
    return new Page(w, rows, null);
  }

  static long number(Object value) {
    return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
  }

  static long[] buckets(Object value) {
    int n = java.lang.reflect.Array.getLength(value);
    long[] result = new long[n];
    for (int i = 0; i < n; i++) result[i] = number(java.lang.reflect.Array.get(value, i));
    return result;
  }

  static void decorate(Map<String, Object> row, long seconds) {
    long count = number(row.getOrDefault("requests", row.getOrDefault("calls", 0))),
        errors = number(row.get("errors"));
    var h = new LatencyHistogram(buckets(row.remove("buckets")));
    row.put("rps", count / (double) seconds);
    row.put("errorRate", count == 0 ? null : errors / (double) count);
    for (double q : new double[] {.5, .95, .99}) {
      var quantile = h.percentile(q);
      String key = "p" + (int) (q * 100);
      row.put(key, quantile.ms());
      row.put(key + "Range", quantile.range());
    }
  }

  public Map<String, Object> serviceMap(Window w) {
    var edges =
        storage.query(
            "SELECT caller,callee,callee_kind,sum(calls) AS calls,sum(errors) AS errors,sumForEach(latency_buckets) AS buckets FROM edges_minute WHERE minute>=toDateTime(?) AND minute<toDateTime(?) GROUP BY caller,callee,callee_kind ORDER BY calls DESC LIMIT 1000",
            w.from.getEpochSecond(),
            w.to.getEpochSecond());
    for (var edge : edges) decorate(edge, w.seconds());
    return Map.of(
        "window",
        w,
        "nodes",
        services(w).data,
        "edges",
        edges,
        "edgeCompleteness",
        "Edges reflect spans available at trace finalization; late contributions are omitted");
  }

  public Page traces(
      Window w, String service, Double minMs, boolean errors, int limit, String cursor) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
    if (minMs != null && (!Double.isFinite(minMs) || minMs < 0 || minMs > 86400000))
      throw new IllegalArgumentException("Invalid minimum duration");
    StringBuilder sql =
        new StringBuilder(
            "SELECT trace_id,toUnixTimestamp64Nano(start_time) AS start_ns,duration_ns,root_service,root_name,services,span_count,error_count,kept_reason,known_incomplete,finalization_reason,late_span_count FROM traces FINAL WHERE start_time>=fromUnixTimestamp64Nano(?) AND start_time<fromUnixTimestamp64Nano(?)");
    List<Object> params =
        new ArrayList<>(
            List.of(
                w.from.getEpochSecond() * 1_000_000_000L, w.to.getEpochSecond() * 1_000_000_000L));
    if (service != null && !service.isBlank()) {
      sql.append(" AND has(services,?)");
      params.add(service);
    }
    if (minMs != null) {
      sql.append(" AND duration_ns>=?");
      params.add((long) (minMs * 1e6));
    }
    if (errors) sql.append(" AND error_count>0");
    if (cursor != null && !cursor.isBlank()) {
      try {
        String[] parts =
            new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
        if (parts.length != 2 || !parts[1].matches("[0-9a-f]{32}"))
          throw new IllegalArgumentException();
        sql.append(" AND (start_time,trace_id)<(fromUnixTimestamp64Nano(?),?)");
        params.add(Long.parseLong(parts[0]));
        params.add(parts[1]);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Invalid cursor");
      }
    }
    sql.append(" ORDER BY start_time DESC,trace_id DESC LIMIT ").append(limit + 1);
    var rows = storage.query(sql.toString(), params.toArray());
    String next = null;
    if (rows.size() > limit) {
      rows = new ArrayList<>(rows.subList(0, limit));
      var last = rows.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.get("start_ns") + ":" + last.get("trace_id"))
                      .getBytes(StandardCharsets.UTF_8));
    }
    return new Page(w, rows, next);
  }

  public List<Span> spans(String id) {
    if (!id.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("traceId must be 32 lowercase hex characters");
    var index =
        storage.query(
            "SELECT toUnixTimestamp64Nano(start_time) AS start_ns,toUnixTimestamp64Nano(end_time) AS end_ns FROM trace_index FINAL WHERE trace_id=?",
            id);
    if (index.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Retained trace not found");
    var bounds = index.getFirst();
    var rows =
        storage.query(
            "SELECT trace_id,span_id,parent_span_id,service,name,kind,toUnixTimestamp64Nano(start_time) AS start_ns,duration_ns,error,http_status,route,peer,database,attributes FROM spans FINAL WHERE trace_id=? AND start_time>=fromUnixTimestamp64Nano(?) AND start_time<=fromUnixTimestamp64Nano(?) ORDER BY start_time,span_id LIMIT 1001",
            id,
            number(bounds.get("start_ns")),
            number(bounds.get("end_ns")));
    List<Span> spans = new ArrayList<>();
    for (var row : rows) {
      Map<String, String> attrs = new LinkedHashMap<>();
      if (row.get("attributes") instanceof Map<?, ?> m)
        m.forEach((k, v) -> attrs.put(k.toString(), v.toString()));
      spans.add(
          new Span(
              id,
              row.get("span_id").toString(),
              row.get("parent_span_id").toString(),
              row.get("service").toString(),
              row.get("name").toString(),
              Span.Kind.valueOf(row.get("kind").toString().toUpperCase(Locale.ROOT)),
              number(row.get("start_ns")),
              number(row.get("duration_ns")),
              Boolean.parseBoolean(row.get("error").toString())
                  || row.get("error").toString().equals("1"),
              (int) number(row.get("http_status")),
              row.get("route").toString(),
              row.get("peer").toString(),
              Boolean.parseBoolean(row.get("database").toString())
                  || row.get("database").toString().equals("1"),
              attrs));
    }
    return spans;
  }

  public record TraceMetadata(
      boolean knownIncomplete, String finalizationReason, long lateSpanCount) {}

  public TraceMetadata traceMetadata(String id) {
    var rows =
        storage.query(
            "SELECT known_incomplete,finalization_reason,late_span_count FROM traces FINAL WHERE trace_id=?",
            id);
    if (rows.isEmpty()) return new TraceMetadata(true, "metadata_unavailable", 0);
    var row = rows.getFirst();
    Object incomplete = row.get("known_incomplete");
    return new TraceMetadata(
        Boolean.parseBoolean(incomplete.toString()) || incomplete.toString().equals("1"),
        row.get("finalization_reason").toString(),
        number(row.get("late_span_count")));
  }

  public Map<String, Object> trace(String id) {
    var spans = spans(id);
    return Map.of(
        "traceId",
        id,
        "metadata",
        traceMetadata(id),
        "tree",
        TraceTree.build(spans),
        "criticalPath",
        CriticalPath.compute(spans),
        "finalizationNotice",
        "Idle finalization is a heuristic; absence of missing parents does not prove completeness");
  }

  public List<String> exemplars(Window w, String service, String operation, boolean errors) {
    String sql =
        "SELECT trace_id,max(duration_ns) AS duration FROM spans FINAL WHERE service=? AND kind='server' AND start_time>=fromUnixTimestamp64Nano(?) AND start_time<fromUnixTimestamp64Nano(?)"
            + (operation == null ? "" : " AND name=?")
            + (errors ? " AND error=true" : "")
            + " GROUP BY trace_id ORDER BY duration DESC LIMIT 5";
    List<Object> params =
        new ArrayList<>(
            List.of(
                service,
                w.from.getEpochSecond() * 1_000_000_000L,
                w.to.getEpochSecond() * 1_000_000_000L));
    if (operation != null) params.add(operation);
    return storage.query(sql, params.toArray()).stream()
        .map(row -> row.get("trace_id").toString())
        .toList();
  }
}
