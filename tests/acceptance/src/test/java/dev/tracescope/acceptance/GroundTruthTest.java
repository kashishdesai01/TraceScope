package dev.tracescope.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class GroundTruthTest {
  static final int WARMUP_SECONDS = 300;
  static final int ALERT_WINDOW_SECONDS = 120, ALERT_HOLD_SECONDS = 60, EVALUATION_SECONDS = 15;
  final ObjectMapper json = new ObjectMapper();
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  final Path root = Path.of(System.getProperty("tracescope.root"));
  final Path runtime = root.resolve("reports/runtime");
  final String base = System.getenv().getOrDefault("TRACESCOPE_URL", "http://localhost:8080");
  final String gateway = System.getenv().getOrDefault("GATEWAY_URL", "http://localhost:8081");
  final String project =
      Objects.requireNonNull(
          System.getenv("TRACESCOPE_PROJECT"),
          "Use scripts/acceptance.sh to create the isolated stack");
  final Map<String, String> status = new LinkedHashMap<>(), details = new LinkedHashMap<>();
  final Map<String, Load> loads = new LinkedHashMap<>();
  double baselineP95, measuredAccuracy = Double.NaN;
  boolean latencyRecovered, errorRecovered;
  Instant runStart;

  record Load(
      String name,
      Instant from,
      Instant to,
      long requests,
      long serverErrors,
      double p95,
      double p99) {}

  @FunctionalInterface
  interface Work {
    void run() throws Exception;
  }

  @FunctionalInterface
  interface Condition {
    boolean get() throws Exception;
  }

  @BeforeAll
  void setup() throws Exception {
    Files.createDirectories(runtime);
    runStart = Instant.now();
    for (String id : List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"))
      status.put(id, "NOT RUN");
    reset();
    writeReport();
    try {
      Load warmup = load("warmup", WARMUP_SECONDS, 20);
      assertEquals(0, warmup.serverErrors, "Healthy warmup must not contain server failures");
      JsonNode metrics = json.readTree(runtime.resolve("warmup.json").toFile()).path("metrics");
      assertEquals(
          0.0,
          metrics.path("http_req_failed").path("values").path("rate").asDouble(),
          "Healthy warmup must return successful responses");
      qualifyWarmup(warmup);
      details.put(
          "warmup",
          "PASS:fixed 300s healthy 20req/s warmup; zero dropped iterations, transport or HTTP failures; final closed 120s healthy for every enabled rule with minimum traffic; no open incident carries into baseline. Earlier resolved startup history is retained separately.");
    } catch (Exception | AssertionError failure) {
      details.put("warmup", "FAIL:" + failure);
      throw failure;
    } finally {
      writeReport();
    }
  }

  void qualifyWarmup(Load warmup) throws Exception {
    var rules = get("/alert-rules");
    var measurements = json.createArrayNode();
    ObjectNode evidence = json.createObjectNode();
    evidence.put("qualified", false);
    evidence.put("from", warmup.to.minusSeconds(ALERT_WINDOW_SECONDS).toString());
    evidence.put("to", warmup.to.toString());
    evidence.set("eventsAtEntry", get("/alert-events"));
    evidence.set("measurements", measurements);
    Files.writeString(runtime.resolve("warmup-qualification.json"), evidence.toPrettyString());
    Set<String> covered = new HashSet<>();
    for (JsonNode rule : rules) {
      if (!rule.path("enabled").asBoolean()) continue;
      assertEquals(ALERT_WINDOW_SECONDS, rule.path("windowSec").asInt());
      assertEquals(ALERT_HOLD_SECONDS, rule.path("forSec").asInt());
      JsonNode value = send(base + "/api/v1/alert-rules/preview", "POST", rule, false);
      ObjectNode entry = json.createObjectNode();
      entry.set("rule", rule);
      entry.set("measurement", value);
      measurements.add(entry);
      Files.writeString(runtime.resolve("warmup-qualification.json"), evidence.toPrettyString());
      assertEquals(warmup.to, Instant.parse(value.path("window").path("to").asText()));
      assertEquals(
          warmup.to.minusSeconds(ALERT_WINDOW_SECONDS),
          Instant.parse(value.path("window").path("from").asText()));
      assertTrue(value.path("requests").asLong() >= rule.path("minRequests").asLong());
      assertTrue(Set.of("ok", "bounded_latency").contains(value.path("status").asText()));
      assertTrue(value.path("value").isNumber());
      double measured = value.path("value").asDouble();
      assertTrue(
          Double.isFinite(measured)
              && measured >= 0
              && measured <= rule.path("threshold").asDouble(),
          "Warmup final window breaches " + rule.path("name").asText() + ": " + value);
      covered.add(rule.path("service").asText() + ":" + rule.path("kind").asText());
    }
    assertTrue(
        covered.containsAll(
            Set.of(
                "gateway:error_rate",
                "checkout:error_rate",
                "payment:error_rate",
                "inventory:error_rate",
                "checkout:latency")),
        "Warmup must qualify every expected default rule");
    evidence.set("eventsAtExit", get("/alert-events"));
    Files.writeString(runtime.resolve("warmup-qualification.json"), evidence.toPrettyString());
    assertNoOpenIncidents("Warmup must not carry an active incident or timer into baseline");
    evidence.put("qualified", true);
    Files.writeString(runtime.resolve("warmup-qualification.json"), evidence.toPrettyString());
  }

  void assertNoOpenIncidents(String context) throws Exception {
    JsonNode events = get("/alert-events?state=open");
    assertEquals(0, events.size(), context + ": " + events);
  }

  void assertNoFiringDuring(Instant started, Instant observedThrough) throws Exception {
    JsonNode events = get("/alert-events");
    ObjectNode evidence = json.createObjectNode();
    evidence.put("measurementStarted", started.toString());
    evidence.put("observedThrough", observedThrough.toString());
    evidence.set("events", events);
    Files.writeString(runtime.resolve("A1-alert-history.json"), evidence.toPrettyString());
    for (JsonNode event : events) {
      assertFalse(
          firingOverlaps(event, started, observedThrough),
          "A firing incident overlaps the baseline or its post-flush observation: " + event);
    }
  }

  static boolean firingOverlaps(JsonNode event, Instant started, Instant observedThrough) {
    if (event.path("firing_at").isNull()) return false;
    Instant fired = Instant.parse(event.path("firing_at").asText());
    if (fired.isAfter(observedThrough)) return false;
    boolean endedBefore =
        !event.path("resolved_at").isNull()
            && Instant.parse(event.path("resolved_at").asText()).isBefore(started);
    return !fired.isBefore(started) || !endedBefore;
  }

  @AfterAll
  void report() throws Exception {
    reset();
    writeReport();
  }

  @AfterEach
  void clearFaults() throws Exception {
    reset();
  }

  void scenario(String id, Work work) throws Exception {
    status.put(id, "RUNNING");
    writeReport();
    try {
      work.run();
      status.put(id, "PASS");
    } catch (Throwable failure) {
      status.put(id, "FAIL");
      details.put(id, failure.toString());
      if (failure instanceof Exception e) throw e;
      if (failure instanceof Error e) throw e;
      throw new RuntimeException(failure);
    } finally {
      writeReport();
    }
  }

  JsonNode send(String url, String method, JsonNode body, boolean chaos) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (chaos)
      builder.header(
          "X-Chaos-Token", System.getenv().getOrDefault("CHAOS_TOKEN", "local-chaos-token"));
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        url + " returned " + response.statusCode() + ": " + response.body());
    return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
  }

  JsonNode get(String path) throws Exception {
    return send(base + "/api/v1" + path, "GET", null, false);
  }

  String roleUrl(String role) {
    return System.getenv()
        .getOrDefault(
            role.toUpperCase(Locale.ROOT) + "_URL",
            switch (role) {
              case "payment" -> "http://localhost:8083";
              case "inventory" -> "http://localhost:8084";
              default -> "http://localhost:8082";
            });
  }

  void fault(String role, int latency, double errors, int dbSlow, String down) throws Exception {
    ObjectNode body = json.createObjectNode();
    body.put("latencyMs", latency);
    body.put("errorRate", errors);
    body.put("dbSlowMs", dbSlow);
    body.put("downstreamDown", down);
    send(roleUrl(role) + "/chaos", "POST", body, true);
  }

  void reset() throws Exception {
    for (String role : List.of("checkout", "payment", "inventory")) fault(role, 0, 0, 0, "");
  }

  int checkout() throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create(gateway + "/api/checkout"))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  void await(Duration timeout, Condition condition, String description) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.get()) return;
      Thread.sleep(2000);
    }
    fail("Timed out: " + description);
  }

  void waitClosed(Instant ended) throws Exception {
    Instant ready = Instant.ofEpochSecond((ended.getEpochSecond() / 60 + 1) * 60 + 20);
    await(
        Duration.ofSeconds(Math.max(95, Duration.between(Instant.now(), ready).getSeconds() + 10)),
        () -> !Instant.now().isBefore(ready),
        "closed minute and bounded pipeline drain");
  }

  String range(Load load) {
    return "from=" + load.from + "&to=" + load.to;
  }

  Load load(String name, int seconds, int rate) throws Exception {
    Instant started = Instant.now();
    Path log = runtime.resolve(name + "-k6.log");
    var command =
        List.of(
            root.resolve("scripts/compose.sh").toString(),
            "-p",
            project,
            "run",
            "--rm",
            "--no-deps",
            "-T",
            "-e",
            "DURATION=" + seconds + "s",
            "-e",
            "RATE=" + rate,
            "-e",
            "SUMMARY_PATH=/reports/" + name + ".json",
            "loadgen");
    var process =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(seconds + 90L, TimeUnit.SECONDS), "k6 did not finish: " + log);
      assertEquals(0, process.exitValue(), "k6 failed: " + Files.readString(log));
    } finally {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
      }
    }
    Instant ended = Instant.now();
    waitClosed(ended);
    JsonNode summary = json.readTree(runtime.resolve(name + ".json").toFile()).path("metrics");
    assertEquals(
        0,
        summary.path("transport_errors").path("values").path("count").asLong(),
        "Transport failures invalidate ground truth");
    assertEquals(
        0,
        summary.path("dropped_iterations").path("values").path("count").asLong(),
        "Insufficient k6 VUs invalidate arrival-rate scenario");
    var result =
        new Load(
            name,
            Instant.ofEpochSecond(started.getEpochSecond() / 60 * 60),
            Instant.ofEpochSecond((ended.getEpochSecond() / 60 + 1) * 60),
            summary.path("http_reqs").path("values").path("count").asLong(),
            summary.path("server_errors").path("values").path("count").asLong(),
            summary.path("http_req_duration").path("values").path("p(95)").asDouble(),
            summary.path("http_req_duration").path("values").path("p(99)").asDouble());
    assertTrue(result.requests > 0);
    loads.put(name, result);
    writeReport();
    return result;
  }

  JsonNode metric(Load load, String service) throws Exception {
    for (JsonNode row : get("/services?" + range(load)).path("data"))
      if (row.path("service").asText().equals(service)) return row;
    return json.createObjectNode().put("requests", 0).put("errors", 0);
  }

  JsonNode operation(Load load, String service) throws Exception {
    for (JsonNode row : get("/services/" + service + "/operations?" + range(load)).path("data"))
      if (row.path("operation").asText().equals("POST /api/checkout")) return row;
    throw new AssertionError("Gateway checkout operation missing");
  }

  List<JsonNode> traces(Load load, String service) throws Exception {
    List<JsonNode> rows = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode page =
          get(
              "/traces?"
                  + range(load)
                  + "&service="
                  + service
                  + "&limit=100"
                  + (cursor == null
                      ? ""
                      : "&cursor="
                          + URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8)));
      for (JsonNode row : page.path("data"))
        if (row.path("root_name").asText().equals("POST /api/checkout")) rows.add(row);
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText(null);
    } while (cursor != null);
    return rows;
  }

  JsonNode edge(Load load, String caller, String callee) throws Exception {
    for (JsonNode row : get("/service-map?" + range(load)).path("edges"))
      if (row.path("caller").asText().equals(caller) && row.path("callee").asText().equals(callee))
        return row;
    throw new AssertionError("Missing edge " + caller + " -> " + callee);
  }

  JsonNode firing(String service, String kind, Instant since) throws Exception {
    JsonNode found = null;
    for (JsonNode event : get("/alert-events"))
      if (event.path("service").asText().equals(service)
          && event.path("kind").asText().equals(kind)
          && !event.path("firing_at").isNull()
          && Instant.parse(event.path("firing_at").asText()).isAfter(since.minusSeconds(1)))
        found = event;
    return found;
  }

  JsonNode awaitFiring(String service, String kind, Instant since) throws Exception {
    JsonNode[] result = {null};
    await(
        Duration.ofSeconds(45),
        () -> (result[0] = firing(service, kind, since)) != null,
        "firing " + service + " " + kind);
    return result[0];
  }

  JsonNode trace(String id) throws Exception {
    return get("/traces/" + id);
  }

  JsonNode span(JsonNode trace, String id) {
    for (JsonNode node : trace.path("tree").path("nodes"))
      if (node.path("span").path("spanId").asText().equals(id)) return node.path("span");
    throw new AssertionError("Span not found");
  }

  void recover(String label, JsonNode incident) throws Exception {
    reset();
    Instant cleared = Instant.now();
    Load healthy = load(label, 180, 20);
    String id = incident.path("id").asText();
    await(
        Duration.ofSeconds(45),
        () -> {
          for (JsonNode event : get("/alert-events"))
            if (event.path("id").asText().equals(id)
                && event.path("state").asText().equals("resolved")) return true;
          return false;
        },
        "incident recovery");
    JsonNode event = null;
    for (JsonNode row : get("/alert-events")) if (row.path("id").asText().equals(id)) event = row;
    assertNotNull(event);
    assertTrue(
        Duration.between(cleared, Instant.parse(event.path("resolved_at").asText())).getSeconds()
            <= 285,
        "Recovery includes 120s window, minute alignment, 60s persistence and pipeline/evaluator delay");
    assertEquals(0, metric(healthy, "gateway").path("errors").asLong());
    double recovered = operation(healthy, "gateway").path("p95").asDouble();
    assertTrue(
        recovered <= baselineP95 * 1.10 + 1e-12,
        "Healthy p95 " + recovered + " vs baseline " + baselineP95);
    details.put(
        label,
        "Resolved after "
            + Duration.between(cleared, Instant.parse(event.path("resolved_at").asText()))
                .getSeconds()
            + "s; healthy p95="
            + recovered
            + "ms");
  }

  @Test
  @Order(1)
  void A1_baseline() throws Exception {
    scenario(
        "A1",
        () -> {
          assertNoOpenIncidents("Baseline must begin without an active warmup incident");
          Instant measurementStarted = Instant.now();
          Load baseline = load("A1", 300, 20);
          assertEquals(0, baseline.serverErrors);
          for (String service : List.of("gateway", "checkout", "payment", "inventory"))
            assertEquals(
                baseline.requests,
                metric(baseline, service).path("requests").asLong(),
                service + " request count");
          Set<String> actual = new TreeSet<>();
          for (JsonNode e : get("/service-map?" + range(baseline)).path("edges"))
            actual.add(e.path("caller").asText() + "->" + e.path("callee").asText());
          assertEquals(
              Set.of(
                  "gateway->checkout",
                  "checkout->payment",
                  "checkout->inventory",
                  "payment->postgresql:payments",
                  "inventory->postgresql:inventory"),
              actual);
          var traces = traces(baseline, "gateway");
          assertEquals(baseline.requests, traces.size());
          long complete =
              traces.stream()
                  .filter(
                      t ->
                          t.path("span_count").asInt() == 9
                              && !t.path("known_incomplete").asBoolean()
                              && t.path("services").size() == 4)
                  .count();
          assertTrue(complete / (double) baseline.requests >= .99);
          Instant observationEnd =
              baseline.to.plusSeconds(
                  ALERT_WINDOW_SECONDS + ALERT_HOLD_SECONDS + 2L * EVALUATION_SECONDS);
          await(
              Duration.ofMinutes(5),
              () -> !Instant.now().isBefore(observationEnd),
              "baseline alert observation through window, hold and evaluator delays");
          assertNoFiringDuring(measurementStarted, Instant.now());
          assertNoOpenIncidents("Baseline must end without an active incident");
          baselineP95 = operation(baseline, "gateway").path("p95").asDouble();
          assertTrue(baselineP95 > 0);
          details.put(
              "A1",
              baseline.requests
                  + " requests per service; "
                  + complete
                  + "/"
                  + traces.size()
                  + " complete nine-span traces; p95="
                  + baselineP95
                  + "ms; zero overlapping firing incidents through "
                  + observationEnd
                  + "; earlier resolved warmup history retained separately");
        });
  }

  @Test
  @Order(2)
  void A2_payment_latency() throws Exception {
    scenario(
        "A2",
        () -> {
          Assumptions.assumeTrue(status.get("A1").equals("PASS"));
          fault("payment", 800, 0, 0, "");
          Instant activated = Instant.now();
          Load latency = load("A2", 180, 20);
          JsonNode event = awaitFiring("checkout", "latency", activated);
          long sustained =
              Duration.between(
                      Instant.parse(event.path("started_at").asText()),
                      Instant.parse(event.path("firing_at").asText()))
                  .getSeconds();
          assertAll(
              "Latency diagnosis and recovery are independently verified",
              () ->
                  assertTrue(
                      Duration.between(activated, Instant.parse(event.path("firing_at").asText()))
                              .getSeconds()
                          <= 285),
              () -> assertTrue(sustained >= 60 && sustained <= 95, "For duration=" + sustained),
              () -> {
                assertEquals(5, event.path("exemplar_trace_ids").size());
                List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
                for (JsonNode id : event.path("exemplar_trace_ids"))
                  checks.add(
                      () -> {
                        JsonNode trace = trace(id.asText());
                        long payment = 0;
                        for (JsonNode segment : trace.path("criticalPath").path("segments"))
                          if (segment.path("service").asText().equals("payment"))
                            payment += segment.path("durationNs").asLong();
                        double share =
                            payment
                                / (double) trace.path("criticalPath").path("durationNs").asLong();
                        assertTrue(share >= .70, "Payment share=" + share + " in " + id);
                      });
                assertAll("All five unfiltered alert exemplars", checks);
              },
              () -> assertTrue(edge(latency, "checkout", "payment").path("p95").asDouble() > 800),
              () -> {
                fault("payment", 0, 0, 0, "");
                recover("A6-latency", event);
                latencyRecovered = true;
              });
          details.put(
              "A2",
              "Payment holds >=70% of path in all five exemplars; alert sustained="
                  + sustained
                  + "s");
        });
  }

  @Test
  @Order(3)
  void A3_database_latency() throws Exception {
    scenario(
        "A3",
        () -> {
          fault("payment", 0, 0, 600, "");
          Load database = load("A3", 120, 20);
          var examples = traces(database, "payment");
          assertTrue(examples.size() >= 5);
          for (JsonNode item : examples.subList(0, 5)) {
            JsonNode trace = trace(item.path("trace_id").asText()), largest = null;
            for (JsonNode segment : trace.path("criticalPath").path("segments"))
              if (largest == null
                  || segment.path("durationNs").asLong() > largest.path("durationNs").asLong())
                largest = segment;
            assertNotNull(largest);
            JsonNode span = span(trace, largest.path("spanId").asText());
            assertTrue(span.path("database").asBoolean(), span.toString());
            assertEquals("postgresql:payments", span.path("peer").asText());
            assertTrue(largest.path("durationNs").asLong() >= 600_000_000L);
          }
          details.put(
              "A3",
              "Largest path segment is the real PostgreSQL JDBC span in five observed traces");
        });
  }

  @Test
  @Order(4)
  void A4_inventory_errors() throws Exception {
    scenario(
        "A4",
        () -> {
          fault("inventory", 0, .2, 0, "");
          Instant activated = Instant.now();
          Load errors = load("A4", 180, 20);
          JsonNode stats = send(roleUrl("inventory") + "/chaos/stats", "GET", null, true),
              metric = metric(errors, "inventory");
          long injected = stats.path("injectedErrors").asLong();
          assertEquals(errors.requests, metric.path("requests").asLong());
          assertEquals(injected, metric.path("errors").asLong(), Math.max(1, injected * .01));
          assertEquals(.2, metric.path("errorRate").asDouble(), .03);
          assertEquals(injected, errors.serverErrors);
          JsonNode event = awaitFiring("inventory", "error_rate", activated);
          assertTrue(event.path("exemplar_trace_ids").size() > 0);
          for (JsonNode id : event.path("exemplar_trace_ids")) {
            JsonNode trace = trace(id.asText()), deepest = null;
            for (JsonNode node : trace.path("tree").path("nodes"))
              if (node.path("span").path("error").asBoolean()
                  && (deepest == null
                      || node.path("depth").asInt() > deepest.path("depth").asInt()))
                deepest = node;
            assertNotNull(deepest);
            assertEquals("inventory", deepest.path("span").path("service").asText());
          }
          details.put(
              "A4",
              injected
                  + " injected errors; "
                  + metric.path("errors").asLong()
                  + " measured; rate="
                  + metric.path("errorRate").asDouble());
          fault("inventory", 0, 0, 0, "");
          recover("A6-errors", event);
          errorRecovered = true;
        });
  }

  @Test
  @Order(5)
  void A5_unavailable_dependency() throws Exception {
    scenario(
        "A5",
        () -> {
          for (String target : List.of("payment", "inventory")) {
            fault("checkout", 0, 0, 0, target);
            Load unavailable = load(target.equals("payment") ? "A5-payment" : "A5", 60, 20);
            assertTrue(edge(unavailable, "checkout", target).path("errorRate").asDouble() >= .95);
            assertEquals(0, metric(unavailable, target).path("requests").asLong());
            assertTrue(metric(unavailable, "checkout").path("errorRate").asDouble() >= .95);
            assertEquals(unavailable.requests, unavailable.serverErrors);
            if (target.equals("payment")) {
              assertEquals(0, metric(unavailable, "inventory").path("requests").asLong());
              for (JsonNode e : get("/service-map?" + range(unavailable)).path("edges"))
                assertFalse(
                    e.path("caller").asText().equals("checkout")
                        && e.path("callee").asText().equals("inventory"),
                    "A failed payment must not become an inventory dependency");
            }
          }
          details.put(
              "A5",
              "Both unavailable targets produce correctly named failed HTTP service edges; failed targets receive zero requests; unavailable payment produces no inventory edge");
          reset();
          load("A5-cleanup", 180, 20);
          await(
              Duration.ofSeconds(60),
              () -> {
                for (JsonNode event : get("/alert-events?state=open"))
                  if (event.path("state").asText().equals("firing")) return false;
                return true;
              },
              "healthy cleanup before sparse-traffic test");
        });
  }

  @Test
  @Order(6)
  void A6_alert_recovery() throws Exception {
    scenario(
        "A6",
        () -> {
          assertTrue(latencyRecovered);
          assertTrue(errorRecovered);
          details.put("A6", details.get("A6-latency") + "; " + details.get("A6-errors"));
        });
  }

  @Test
  @Order(7)
  void A7_minimum_traffic() throws Exception {
    scenario(
        "A7",
        () -> {
          ObjectNode rule = json.createObjectNode();
          rule.put("name", "Sparse inventory guard");
          rule.put("service", "inventory");
          rule.put("kind", "error_rate");
          rule.put("threshold", .05);
          rule.put("windowSec", 60);
          rule.put("forSec", 0);
          rule.put("minRequests", 20);
          rule.put("resolveRatio", .8);
          rule.put("enabled", true);
          JsonNode saved = send(base + "/api/v1/alert-rules", "POST", rule, false);
          String id = saved.path("id").asText();
          // Wait one quiet closed minute so the sparse rule cannot inherit preceding healthy
          // traffic.
          waitClosed(Instant.now().plusSeconds(60));
          fault("inventory", 0, 1, 0, "");
          assertEquals(503, checkout());
          Instant failed = Instant.now();
          fault("inventory", 0, 0, 0, "");
          waitClosed(failed);
          JsonNode preview = send(base + "/api/v1/alert-rules/preview", "POST", saved, false);
          assertEquals("insufficient_traffic", preview.path("status").asText());
          assertEquals(1, preview.path("requests").asLong());
          assertTrue(preview.path("value").isNull());
          for (JsonNode event : get("/alert-events"))
            assertFalse(
                event.path("rule_id").asText().equals(id),
                "Sparse rule fired despite min_requests");
          assertEquals(200, checkout());
          waitClosed(Instant.now());
          for (JsonNode event : get("/alert-events"))
            if (!event.path("firing_at").isNull())
              assertTrue(
                  Instant.parse(event.path("firing_at").asText()).isBefore(failed),
                  "New firing during sparse traffic");
          details.put(
              "A7",
              "One forced failure in a one-request minute: measured no data under min_requests=20; no new firing");
        });
  }

  @Test
  @Order(8)
  void A8_independent_percentile_agreement() throws Exception {
    scenario(
        "A8",
        () -> {
          assertTrue(
              loads.containsKey("A2"), "A2 must have produced independent load measurements");
          var latency = loads.get("A2");
          double observed = operation(latency, "gateway").path("p95").asDouble();
          assertTrue(
              Double.isFinite(observed)
                  && observed > 0
                  && Double.isFinite(latency.p95)
                  && latency.p95 > 0);
          measuredAccuracy = Math.abs(observed - latency.p95) / latency.p95;
          assertTrue(
              Double.isFinite(measuredAccuracy) && measuredAccuracy <= .05,
              "TraceScope=" + observed + " k6=" + latency.p95);
          details.put(
              "A8",
              "Gateway p95="
                  + observed
                  + "ms; k6 p95="
                  + latency.p95
                  + "ms; relative difference="
                  + measuredAccuracy * 100
                  + "%");
        });
  }

  void writeReport() throws Exception {
    StringBuilder out =
        new StringBuilder(
            "# Ground-truth acceptance\n\nRun started: "
                + runStart
                + ". Protocol:fixed 300s healthy 20req/s warmup; final closed 120s rule qualification and no active incident at baseline entry. A1 observes firing through its last closed minute plus 120s window, 60s hold and two 15s evaluations. Earlier resolved warmup history remains visible but is excluded from the baseline. Then full A1–A8. Local real-agent Compose stack with fresh databases; sampling rate 1.0. Source has not been committed; runtime image IDs are recorded in `reports/runtime/acceptance-images.json`.\n\n| Scenario | Result | Evidence |\n|---|---|---|\n");
    for (var item : status.entrySet())
      out.append("| ")
          .append(item.getKey())
          .append(" | ")
          .append(item.getValue())
          .append(" | ")
          .append(details.getOrDefault(item.getKey(), "").replace("|", "/").replace("\n", " "))
          .append(" |\n");
    out.append("\nWarmup: ").append(details.getOrDefault("warmup", "NOT RUN")).append("\n");
    out.append(
        "\n## Independent load measurements\n\n| Load | Effective from | Effective to | Requests | 5xx | k6 p95 ms | k6 p99 ms |\n|---|---|---|---:|---:|---:|---:|\n");
    for (var load : loads.values())
      out.append("| ")
          .append(load.name)
          .append(" | ")
          .append(load.from)
          .append(" | ")
          .append(load.to)
          .append(" | ")
          .append(load.requests)
          .append(" | ")
          .append(load.serverErrors)
          .append(" | ")
          .append(load.p95)
          .append(" | ")
          .append(load.p99)
          .append(" |\n");
    out.append(
        "\nCounts compare identical checkout traffic populations. Minute-aligned measurement intervals include their boundary idle periods. Histogram error and client/server latency boundary differences are separate contracts. These tests do not establish production, cloud, crash recovery or scale behavior. Raw k6 reports and logs are retained locally under ignored `reports/runtime/`.\n");
    Files.writeString(root.resolve("reports/acceptance.md"), out);
  }
}
