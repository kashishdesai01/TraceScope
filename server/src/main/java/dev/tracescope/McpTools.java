package dev.tracescope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tracescope.model.*;
import java.time.*;
import java.util.*;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.*;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;

@Component
public class McpTools {
  private final QueryService queries;
  private final AlertService alerts;
  private final ObjectMapper json;

  public McpTools(QueryService queries, AlertService alerts, ObjectMapper json) {
    this.queries = queries;
    this.alerts = alerts;
    this.json = json;
  }

  private static int minutes(Integer minutes) {
    int m = minutes == null ? 15 : minutes;
    if (m < 1 || m > 1440) throw new IllegalArgumentException("minutes must be 1..1440");
    return m;
  }

  private static int limit(Integer limit) {
    int n = limit == null ? 5 : limit;
    if (n < 1 || n > 10) throw new IllegalArgumentException("limit must be 1..10");
    return n;
  }

  private static String service(String service) {
    if (service == null || service.isBlank() || service.length() > 128)
      throw new IllegalArgumentException("service is required and limited to 128 characters");
    return service;
  }

  private static QueryService.Window window(Integer minutes) {
    Instant end = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    return QueryService.window(end.minusSeconds(minutes(minutes) * 60L), end);
  }

  @Tool(
      name = "list_services",
      description =
          "List services with all-received-request rate, error fraction and p95 in a closed minute-aligned window. Trace retention is sampled; metrics are computed before sampling.")
  public String listServices(
      @ToolParam(required = false, description = "Window in minutes, 1..1440; default 15")
          Integer minutes) {
    var page = queries.services(window(minutes));
    return bounded(
        new LinkedHashMap<>(
            Map.of(
                "window",
                page.window(),
                "services",
                new ArrayList<>(page.data().stream().limit(50).toList()),
                "omittedItems",
                Math.max(0, page.data().size() - 50))));
  }

  @Tool(
      name = "get_service_health",
      description =
          "Compare a service with a previous window, including operations with the largest p95 change and open alerts. Latency attribution is observational, not proof of causality.")
  public String serviceHealth(
      String service,
      @ToolParam(required = false) Integer minutes,
      @ToolParam(required = false) Integer compareMinutes) {
    service(service);
    var current = window(minutes);
    int previousMinutes = compareMinutes == null ? minutes(minutes) : minutes(compareMinutes);
    var previous =
        QueryService.window(current.from().minusSeconds(previousMinutes * 60L), current.from());
    var now =
        queries.services(current).data().stream()
            .filter(row -> service.equals(row.get("service")))
            .toList();
    var before =
        queries.services(previous).data().stream()
            .filter(row -> service.equals(row.get("service")))
            .toList();
    var previousOps = queries.operations(previous, service).data();
    List<Map<String, Object>> operations = new ArrayList<>();
    for (var operation : queries.operations(current, service).data()) {
      var old =
          previousOps.stream()
              .filter(row -> Objects.equals(row.get("operation"), operation.get("operation")))
              .findFirst();
      Map<String, Object> op = new LinkedHashMap<>();
      op.put("operation", operation.get("operation"));
      op.put("p95", operation.get("p95"));
      op.put(
          "p95ChangeMs",
          old.isEmpty() || old.get().get("p95") == null || operation.get("p95") == null
              ? null
              : ((Number) operation.get("p95")).doubleValue()
                  - ((Number) old.get().get("p95")).doubleValue());
      operations.add(op);
    }
    operations.sort(
        Comparator.comparingDouble(
                (Map<String, Object> row) ->
                    row.get("p95ChangeMs") instanceof Number n
                        ? n.doubleValue()
                        : Double.NEGATIVE_INFINITY)
            .reversed());
    return bounded(
        new LinkedHashMap<>(
            Map.of(
                "window",
                current,
                "previousWindow",
                previous,
                "current",
                now,
                "previous",
                before,
                "operations",
                new ArrayList<>(operations.stream().limit(5).toList()),
                "alerts",
                new ArrayList<>(
                    alerts.events("open").stream()
                        .filter(row -> service.equals(row.get("service")))
                        .toList()),
                "omittedItems",
                Math.max(0, operations.size() - 5))));
  }

  @Tool(
      name = "find_slow_traces",
      description =
          "Find retained traces involving a service, filtered by end-to-end root duration. Metrics include unsampled traffic.")
  public String slowTraces(
      String service,
      @ToolParam(required = false) Integer minutes,
      @ToolParam(required = false) Double minMs,
      @ToolParam(required = false) Integer limit) {
    service(service);
    var page =
        queries.traces(
            window(minutes), service, minMs == null ? 500 : minMs, false, limit(limit), null);
    return traceList(page);
  }

  @Tool(
      name = "find_error_traces",
      description =
          "Find retained error traces involving a service. The deepest observed error span is a failure candidate; upstream spans may propagate the same failure.")
  public String errorTraces(
      String service,
      @ToolParam(required = false) Integer minutes,
      @ToolParam(required = false) Integer limit) {
    service(service);
    var page = queries.traces(window(minutes), service, null, true, limit(limit), null);
    for (var row : page.data()) {
      var tree = TraceTree.build(queries.spans(row.get("trace_id").toString()));
      tree.nodes().stream()
          .filter(n -> n.span().error())
          .max(Comparator.comparingInt(TraceTree.Node::depth))
          .ifPresent(
              n -> {
                row.put("deepestErrorService", n.span().service());
                row.put(
                    "errorType",
                    n.span()
                        .attributes()
                        .getOrDefault("exception.type", "HTTP " + n.span().httpStatus()));
              });
    }
    return traceList(page);
  }

  private String traceList(QueryService.Page page) {
    List<Map<String, Object>> traces = new ArrayList<>();
    for (var row : page.data()) {
      Map<String, Object> trace = new LinkedHashMap<>();
      for (String key :
          List.of(
              "trace_id",
              "root_service",
              "root_name",
              "duration_ns",
              "error_count",
              "deepestErrorService",
              "errorType")) if (row.containsKey(key)) trace.put(key, row.get(key));
      trace.put("uiLink", "/traces/" + row.get("trace_id"));
      traces.add(trace);
    }
    return bounded(
        new LinkedHashMap<>(
            Map.of(
                "window",
                page.window(),
                "traces",
                traces,
                "moreAvailable",
                page.nextCursor() != null,
                "retention",
                "kept traces; metrics include all received application requests")));
  }

  @Tool(
      name = "get_trace_summary",
      description =
          "Return a compact synchronous critical-path summary and deepest observed error spans. Display skew and incomplete-tree flags qualify attribution; no raw attribute maps are returned.")
  public String traceSummary(String traceId) {
    var spans = queries.spans(traceId);
    var path = CriticalPath.compute(spans);
    var metadata = queries.traceMetadata(traceId);
    Map<String, Span> byId = new HashMap<>();
    for (var span : spans) byId.put(span.spanId(), span);
    Map<String, Long> self = new HashMap<>();
    for (var segment : path.segments())
      self.merge(segment.spanId(), segment.durationNs(), Long::sum);
    List<Map<String, Object>> segments = new ArrayList<>();
    self.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(20)
        .forEach(
            entry -> {
              var span = byId.get(entry.getKey());
              Map<String, Object> segment = new LinkedHashMap<>();
              segment.put("spanId", span.spanId());
              segment.put("service", span.service());
              segment.put("name", span.name());
              segment.put("selfMs", entry.getValue() / 1e6);
              segment.put(
                  "pctOfTotal",
                  path.durationNs() == 0 ? 0 : 100.0 * entry.getValue() / path.durationNs());
              segment.put("attributes", safeAttributes(span));
              segments.add(segment);
            });
    List<Map<String, Object>> errors = new ArrayList<>();
    TraceTree.build(spans).nodes().stream()
        .filter(n -> n.span().error())
        .sorted(Comparator.comparingInt(TraceTree.Node::depth).reversed())
        .limit(5)
        .forEach(
            n ->
                errors.add(
                    Map.of(
                        "service",
                        n.span().service(),
                        "name",
                        n.span().name(),
                        "attributes",
                        safeAttributes(n.span()))));
    return bounded(
        new LinkedHashMap<>(
            Map.of(
                "traceId",
                traceId,
                "durationMs",
                path.durationNs() / 1e6,
                "segments",
                segments,
                "errors",
                errors,
                "metadata",
                metadata,
                "approximate",
                path.approximate() || metadata.knownIncomplete(),
                "spanCount",
                spans.size(),
                "omittedSegments",
                Math.max(0, self.size() - 20),
                "uiLink",
                "/traces/" + traceId,
                "attribution",
                "Synchronous timing heuristic; deepest error is an observed failure candidate")));
  }

  static Map<String, String> safeAttributes(Span span) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String key :
        List.of(
            "http.route",
            "http.response.status_code",
            "db.system",
            "db.system.name",
            "db.namespace",
            "exception.type",
            "exception.message"))
      if (span.attributes().containsKey(key)) {
        String value = span.attributes().get(key);
        result.put(key, value.length() > 160 ? value.substring(0, 160) : value);
      }
    return result;
  }

  @Tool(
      name = "get_service_dependencies",
      description =
          "Show upstream and downstream service/database/external edges with counts, error fractions and p95. Edge contributions arriving after trace finalization are omitted.")
  public String dependencies(String service, @ToolParam(required = false) Integer minutes) {
    service(service);
    var map = queries.serviceMap(window(minutes));
    var edges = (List<?>) map.get("edges");
    return bounded(
        new LinkedHashMap<>(
            Map.of(
                "window",
                map.get("window"),
                "edges",
                new ArrayList<>(
                    edges.stream()
                        .filter(
                            item ->
                                item instanceof Map<?, ?> row
                                    && (service.equals(row.get("caller"))
                                        || service.equals(row.get("callee"))))
                        .toList()),
                "completeness",
                map.get("edgeCompleteness"))));
  }

  @Tool(
      name = "list_alerts",
      description =
          "List persisted alerts and retained exemplar links. Missing data is explicit and does not resolve a firing incident.")
  public String listAlerts(
      @ToolParam(required = false, description = "pending, firing, resolved or open; default open")
          String state) {
    return bounded(
        new LinkedHashMap<>(
            Map.of("alerts", new ArrayList<>(alerts.events(state == null ? "open" : state)))));
  }

  String bounded(Map<String, Object> result) {
    for (var entry : new ArrayList<>(result.entrySet()))
      if (entry.getValue() instanceof List<?> list)
        result.put(entry.getKey(), new ArrayList<>(list));
    int omitted = ((Number) result.getOrDefault("omittedItems", 0)).intValue();
    result.put("omittedItems", omitted);
    try {
      while (json.writeValueAsString(result).length() > 6000) {
        List<?> largest =
            result.values().stream()
                .filter(v -> v instanceof List<?> list && !list.isEmpty())
                .map(v -> (List<?>) v)
                .max(Comparator.comparingInt(List::size))
                .orElse(null);
        if (largest == null)
          return json.writeValueAsString(
              Map.of("error", "Summary exceeds size budget", "omittedItems", omitted));
        largest.removeLast();
        result.put("omittedItems", ++omitted);
      }
      result.putIfAbsent("omittedItems", omitted);
      return json.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize summary", e);
    }
  }

  @Configuration
  static class Registration {
    @Bean
    ToolCallbackProvider toolCallbacks(McpTools tools) {
      return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
  }
}
