package dev.tracescope;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import dev.tracescope.model.LatencyHistogram;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.proto.collector.trace.v1.*;
import io.opentelemetry.proto.common.v1.*;
import io.opentelemetry.proto.trace.v1.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"tracescope.sampling-rate=1", "tracescope.idle-ms=50", "tracescope.flush-ms=50"})
class StorageIntegrationTest {
  @Container
  static final GenericContainer<?> CH =
      new GenericContainer<>(
              DockerImageName.parse(
                  "clickhouse/clickhouse-server:26.8@sha256:d426d2f612e1e189c90f9a3f8dcd85a03c6851273833817a296bb327e63aa7a7"))
          .withEnv("CLICKHOUSE_DB", "tracescope")
          .withEnv("CLICKHOUSE_USER", "tracescope")
          .withEnv("CLICKHOUSE_PASSWORD", "local-tracescope")
          .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
          .withExposedPorts(8123)
          .waitingFor(
              Wait.forHttp("/ping")
                  .forPort(8123)
                  .withBasicCredentials("tracescope", "local-tracescope"))
          .withStartupTimeout(Duration.ofMinutes(2));

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>(
              DockerImageName.parse(
                      "postgres:17.11-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("tracescope")
          .withUsername("tracescope")
          .withPassword("local-tracescope");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry r) {
    r.add(
        "tracescope.clickhouse-url", () -> "http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  @LocalServerPort int port;
  @Autowired Storage storage;
  @Autowired Pipeline pipeline;
  @Autowired QueryService queries;
  @Autowired AlertService alerts;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  static KeyValue attr(String k, String v) {
    return KeyValue.newBuilder()
        .setKey(k)
        .setValue(AnyValue.newBuilder().setStringValue(v))
        .build();
  }

  ExportTraceServiceRequest batch(String service, int count, long minute, int offset) {
    var scope = ScopeSpans.newBuilder();
    for (int i = 0; i < count; i++) {
      String id = String.format("%032x", offset + i + 1);
      scope.addSpans(
          io.opentelemetry.proto.trace.v1.Span.newBuilder()
              .setTraceId(ByteString.copyFrom(HexFormat.of().parseHex(id)))
              .setSpanId(
                  ByteString.copyFrom(
                      HexFormat.of().parseHex(String.format("%016x", offset + i + 1))))
              .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER)
              .setName("POST /checkout")
              .setStartTimeUnixNano(minute * 1_000_000_000L)
              .setEndTimeUnixNano(minute * 1_000_000_000L + 100_000_000));
    }
    return ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(
            ResourceSpans.newBuilder()
                .setResource(
                    io.opentelemetry.proto.resource.v1.Resource.newBuilder()
                        .addAttributes(attr("service.name", service)))
                .addScopeSpans(scope))
        .build();
  }

  HttpResponse<byte[]> send(byte[] data, String type, String encoding) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/traces"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", type);
    if (encoding != null) request.header("Content-Encoding", encoding);
    return http.send(
        request.POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  void drain() throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    do {
      Thread.sleep(80);
      pipeline.tick();
      var state = pipeline.stats();
      if (state.activeTraces() == 0
          && state.bufferedSpans() == 0
          && state.aggregateKeys() == 0
          && state.writerFrames() == 0) {
        assertTrue(state.writerHealthy());
        return;
      }
    } while (System.nanoTime() < deadline);
    fail("Pipeline did not finish persistence: " + pipeline.stats());
  }

  static byte[] gzip(byte[] bytes) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(out)) {
      gzip.write(bytes);
    }
    return out.toByteArray();
  }

  @Test
  void realSdkExportsAndPublicTraceApiReturnsMappedData() throws Exception {
    var exporter =
        OtlpHttpSpanExporter.builder()
            .setEndpoint("http://localhost:" + port + "/v1/traces")
            .build();
    var provider =
        SdkTracerProvider.builder()
            .setResource(
                Resource.create(Attributes.builder().put("service.name", "sdk-service").build()))
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    String id;
    try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()) {
      Instant start = Instant.now().minusSeconds(120);
      var span =
          sdk.getTracer("integration")
              .spanBuilder("POST /checkout")
              .setSpanKind(SpanKind.SERVER)
              .setStartTimestamp(start)
              .startSpan();
      id = span.getSpanContext().getTraceId();
      span.setAttribute("http.route", "/checkout");
      span.setAttribute("http.response.status_code", 200);
      span.setAttribute("authorization", "never-store-me");
      span.end(start.plusMillis(120));
      provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    drain();
    var spans = queries.spans(id);
    assertEquals(1, spans.size());
    var s = spans.getFirst();
    assertEquals("sdk-service", s.service());
    assertEquals(120_000_000, s.durationNs());
    assertEquals("/checkout", s.route());
    assertEquals(200, s.httpStatus());
    assertFalse(s.attributes().containsKey("authorization"));
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/traces/" + id))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("sdk-service"));
    assertFalse(response.body().contains("never-store-me"));
  }

  @Test
  void concurrentManualAndDiscoveredRulesRespectCapacityAtCommit() throws Exception {
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (boolean discovery : List.of(false, true)) {
        jdbc.update(
            "INSERT INTO alert_rules(id,name,service,kind,threshold,enabled) SELECT md5('capacity-fill-'||i)::uuid,'capacity-regression-fill','capacity-regression','error_rate',0.05,false FROM generate_series(1,99-(SELECT count(*)::int FROM alert_rules WHERE NOT deleted)) i");
        var manual =
            new AlertService.Rule(
                null,
                "capacity-regression-manual",
                "capacity-regression",
                null,
                "error_rate",
                null,
                .05,
                60,
                0,
                20,
                .8,
                false);
        var seed =
            new AlertService.Rule(
                UUID.randomUUID(),
                "capacity-regression-seed",
                "capacity-regression",
                null,
                "error_rate",
                null,
                .05,
                60,
                0,
                20,
                .8,
                false);
        try (var blocker = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
          blocker.setAutoCommit(false);
          blocker.createStatement().execute("SELECT pg_advisory_xact_lock(1953653091,1919706476)");
          var first = executor.submit(() -> createAtCapacity(manual));
          var second =
              executor.submit(
                  () -> {
                    if (discovery) {
                      Object target =
                          org.springframework.test.util.AopTestUtils.getTargetObject(alerts);
                      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                          target, "seedRule", seed);
                      return 0;
                    }
                    return createAtCapacity(manual);
                  });
          try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            int waiters;
            do {
              waiters =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid::bigint=1953653091 AND objid::bigint=1919706476 AND NOT granted",
                      Integer.class);
              if (waiters >= 2) break;
              Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertTrue(
                waiters >= 2, "Concurrent creators must both wait on the shared database lock");
          } finally {
            blocker.commit();
          }
          int one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
          int two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
          if (!discovery) assertEquals(Set.of(201, 409), Set.of(one, two));
          else assertTrue(one == 201 || one == 409);
          assertEquals(
              100,
              jdbc.queryForObject(
                  "SELECT count(*) FROM alert_rules WHERE NOT deleted", Integer.class));
          assertEquals(
              1,
              jdbc.queryForObject(
                  "SELECT count(*) FROM alert_rules WHERE name IN ('capacity-regression-manual','capacity-regression-seed')",
                  Integer.class));
        } finally {
          jdbc.update(
              "DELETE FROM alert_events WHERE rule_id IN (SELECT id FROM alert_rules WHERE name LIKE 'capacity-regression-%')");
          jdbc.update("DELETE FROM alert_rules WHERE name LIKE 'capacity-regression-%'");
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private int createAtCapacity(AlertService.Rule rule) {
    try {
      alerts.save(null, rule);
      return 201;
    } catch (org.springframework.web.server.ResponseStatusException conflict) {
      assertEquals(409, conflict.getStatusCode().value());
      return 409;
    }
  }

  @Test
  void malformedStatusCannotPoisonWriterAndLateMetadataReachesPublicDetail() throws Exception {
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 120;
    var original = batch("late-status-service", 1, minute, 9000);
    var root =
        original.getResourceSpans(0).getScopeSpans(0).getSpans(0).toBuilder()
            .addAttributes(attr("http.response.status_code", "-1"))
            .build();
    var resource = original.getResourceSpans(0).toBuilder();
    resource.setScopeSpans(0, ScopeSpans.newBuilder().addSpans(root));
    var initial = ExportTraceServiceRequest.newBuilder().addResourceSpans(resource).build();
    assertEquals(200, send(initial.toByteArray(), "application/x-protobuf", null).statusCode());
    drain();
    String id = HexFormat.of().formatHex(root.getTraceId().toByteArray());
    assertEquals(0, queries.spans(id).getFirst().httpStatus());
    assertTrue(pipeline.stats().writerHealthy());
    var child =
        root.toBuilder()
            .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex("000000000000ffff")))
            .setParentSpanId(root.getSpanId())
            .setStartTimeUnixNano(root.getStartTimeUnixNano() + 1000000)
            .setEndTimeUnixNano(root.getEndTimeUnixNano() - 1000000)
            .clearAttributes()
            .build();
    resource.setScopeSpans(0, ScopeSpans.newBuilder().addSpans(child));
    assertEquals(
        200,
        send(
                ExportTraceServiceRequest.newBuilder()
                    .addResourceSpans(resource)
                    .build()
                    .toByteArray(),
                "application/x-protobuf",
                null)
            .statusCode());
    drain();
    var detail = queries.trace(id);
    assertFalse(((dev.tracescope.model.TraceTree.Tree) detail.get("tree")).knownIncomplete());
    var metadata = (QueryService.TraceMetadata) detail.get("metadata");
    assertTrue(metadata.knownIncomplete());
    assertEquals(1, metadata.lateSpanCount());
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/traces/" + id))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"knownIncomplete\":true"));
    assertTrue(response.body().contains("\"lateSpanCount\":1"));
  }

  @Test
  void retriesAndMultipleFlushesKeepExactCountsAndHistograms() throws Exception {
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 180;
    for (int i = 0; i < 3; i++) {
      var bytes = batch("count-service", 7, minute + i * 60, 1000 + i * 10).toByteArray();
      assertEquals(200, send(bytes, "application/x-protobuf", null).statusCode());
      assertEquals(200, send(bytes, "application/x-protobuf", null).statusCode());
      drain();
      assertEquals(200, send(bytes, "application/x-protobuf", null).statusCode());
    }
    drain();
    var rows =
        storage.query(
            "SELECT sum(requests) AS requests,sumForEach(latency_buckets) AS buckets FROM red_minute WHERE service=?",
            "count-service");
    assertEquals(21, QueryService.number(rows.getFirst().get("requests")));
    var h = new LatencyHistogram(QueryService.buckets(rows.getFirst().get("buckets")));
    assertEquals(21, h.count());
    assertTrue(Math.abs(h.percentile(.95).ms() - 100) / 100 < .05);
    var w = QueryService.window(Instant.ofEpochSecond(minute), Instant.ofEpochSecond(minute + 180));
    assertEquals(
        21,
        QueryService.number(
            queries.operations(w, "count-service").data().getFirst().get("requests")));
    var series = queries.series(w, "count-service", 60);
    assertEquals(3, series.data().size());
    assertEquals(
        21,
        series.data().stream().mapToLong(row -> QueryService.number(row.get("requests"))).sum());
    assertEquals(7.0 / 60, (Double) series.data().getFirst().get("rps"), 1e-12);
  }

  @Test
  void clickHouseRetryTokenDeduplicatesCommittedAggregate() throws Exception {
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 60;
    long[] histogram = new long[148];
    histogram[1] = 1;
    var row =
        Map.<String, Object>of(
            "minute",
            Pipeline.time(minute * 1_000_000_000L),
            "service",
            "retry-token-service",
            "operation",
            "op",
            "requests",
            1,
            "errors",
            0,
            "latency_buckets",
            histogram);
    String token = UUID.randomUUID().toString();
    storage.insert("red_minute", List.of(row), token);
    storage.insert("red_minute", List.of(row), token);
    assertEquals(
        1,
        QueryService.number(
            storage
                .query(
                    "SELECT sum(requests) AS requests FROM red_minute WHERE service=?",
                    "retry-token-service")
                .getFirst()
                .get("requests")));
  }

  @Test
  void otlpErrorsGzipAndPartialSuccessAreProtocolCompatible() throws Exception {
    assertEquals(415, send("{}".getBytes(), "application/json", null).statusCode());
    assertEquals(
        413, send(new byte[4 * 1024 * 1024 + 1], "application/x-protobuf", null).statusCode());
    assertEquals(
        413,
        send(gzip(new byte[16 * 1024 * 1024 + 1]), "application/x-protobuf", "gzip").statusCode());
    assertEquals(400, send(new byte[] {-1}, "application/x-protobuf", null).statusCode());
    assertEquals(400, send(new byte[] {1, 2}, "application/x-protobuf", "gzip").statusCode());
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 60;
    var valid = batch("gzip-service", 1, minute, 3000);
    assertEquals(
        200, send(gzip(valid.toByteArray()), "application/x-protobuf", "gzip").statusCode());
    var invalid =
        valid.toBuilder()
            .setResourceSpans(
                0,
                valid.getResourceSpans(0).toBuilder()
                    .setScopeSpans(
                        0,
                        valid.getResourceSpans(0).getScopeSpans(0).toBuilder()
                            .addSpans(io.opentelemetry.proto.trace.v1.Span.getDefaultInstance())))
            .build();
    var response = send(invalid.toByteArray(), "application/x-protobuf", null);
    assertEquals(200, response.statusCode());
    assertEquals(
        1,
        ExportTraceServiceResponse.parseFrom(response.body())
            .getPartialSuccess()
            .getRejectedSpans());
  }

  @Test
  void mcpRequiresBearerToken() throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, response.statusCode());
  }

  @Test
  void initializedMcpSessionListsSevenToolsAndCallsJsonSummary() throws Exception {
    var transport =
        io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(
                "http://localhost:" + port)
            .endpoint("/mcp")
            .customizeRequest(builder -> builder.header("Authorization", "Bearer local-mcp-token"))
            .build();
    try (var client =
        io.modelcontextprotocol.client.McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(15))
            .build()) {
      client.initialize();
      assertTrue(client.isInitialized());
      var names =
          client.listTools().tools().stream()
              .map(io.modelcontextprotocol.spec.McpSchema.Tool::name)
              .collect(java.util.stream.Collectors.toSet());
      assertEquals(
          Set.of(
              "list_services",
              "get_service_health",
              "find_slow_traces",
              "find_error_traces",
              "get_trace_summary",
              "get_service_dependencies",
              "list_alerts"),
          names);
      long minute = Instant.now().getEpochSecond() / 60 * 60 - 60;
      assertEquals(
          200,
          send(batch("mcp-service", 1, minute, 7000).toByteArray(), "application/x-protobuf", null)
              .statusCode());
      drain();
      Map<String, Map<String, Object>> calls =
          Map.of(
              "list_services", Map.of("minutes", 15),
              "get_service_health",
                  Map.of("service", "mcp-service", "minutes", 15, "compareMinutes", 15),
              "find_slow_traces",
                  Map.of("service", "mcp-service", "minutes", 15, "minMs", 0, "limit", 5),
              "find_error_traces", Map.of("service", "mcp-service", "minutes", 15, "limit", 5),
              "get_trace_summary", Map.of("traceId", String.format("%032x", 7001)),
              "get_service_dependencies", Map.of("service", "mcp-service", "minutes", 15),
              "list_alerts", Map.of("state", "open"));
      for (var call : calls.entrySet()) {
        var result =
            client.callTool(
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    call.getKey(), call.getValue()));
        assertFalse(Boolean.TRUE.equals(result.isError()), call.getKey() + ": " + result);
        var text = (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().getFirst();
        assertTrue(text.text().length() <= 6000, call.getKey());
        assertTrue(
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.text()).isObject(),
            call.getKey());
      }
    }
  }

  @Test
  void alertEvaluationPersistsFiringExemplarsAndDisableResolution() throws Exception {
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 60;
    assertEquals(
        200,
        send(batch("alert-service", 3, minute, 5000).toByteArray(), "application/x-protobuf", null)
            .statusCode());
    drain();
    var rule =
        alerts.save(
            null,
            new AlertService.Rule(
                null,
                "test latency",
                "alert-service",
                "POST /checkout",
                "latency",
                .95,
                50,
                120,
                0,
                1,
                .8,
                true));
    assertEquals("ok", alerts.measure(rule, Instant.now()).status());
    alerts.evaluate();
    var event = jdbc.queryForMap("SELECT * FROM alert_events WHERE rule_id=?", rule.id());
    assertEquals("firing", event.get("state"));
    assertNotNull(event.get("firing_at"));
    assertEquals("available", event.get("exemplar_status"));
    alerts.save(
        rule.id(),
        new AlertService.Rule(
            rule.id(),
            rule.name(),
            rule.service(),
            rule.operation(),
            rule.kind(),
            rule.percentile(),
            rule.threshold(),
            rule.windowSec(),
            rule.forSec(),
            rule.minRequests(),
            rule.resolveRatio(),
            false));
    var resolved = jdbc.queryForMap("SELECT * FROM alert_events WHERE rule_id=?", rule.id());
    assertEquals("resolved", resolved.get("state"));
    assertEquals("rule_disabled", resolved.get("resolution"));
    alerts.delete(rule.id());
    assertFalse(alerts.rules().stream().anyMatch(r -> r.id().equals(rule.id())));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM alert_events WHERE rule_id=?", Integer.class, rule.id()));
  }

  @Test
  void changingRuleConditionsCannotReuseAnOldPendingTimer() throws Exception {
    long minute = Instant.now().getEpochSecond() / 60 * 60 - 60;
    assertEquals(
        200,
        send(
                batch("edited-rule-service", 3, minute, 8000).toByteArray(),
                "application/x-protobuf",
                null)
            .statusCode());
    drain();
    var rule =
        alerts.save(
            null,
            new AlertService.Rule(
                null,
                "edit test",
                "edited-rule-service",
                null,
                "latency",
                .95,
                80,
                120,
                60,
                1,
                .8,
                true));
    alerts.evaluate();
    jdbc.update(
        "UPDATE alert_events SET started_at=now()-interval '120 seconds' WHERE rule_id=?",
        rule.id());
    var changed =
        alerts.save(
            rule.id(),
            new AlertService.Rule(
                rule.id(),
                rule.name(),
                rule.service(),
                rule.operation(),
                rule.kind(),
                rule.percentile(),
                50,
                rule.windowSec(),
                rule.forSec(),
                rule.minRequests(),
                rule.resolveRatio(),
                true));
    alerts.evaluate();
    var open =
        jdbc.queryForMap(
            "SELECT * FROM alert_events WHERE rule_id=? AND state<>'resolved'", rule.id());
    assertEquals("pending", open.get("state"));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM alert_events WHERE rule_id=? AND resolution='rule_changed'",
            Integer.class,
            rule.id()));
    var started = open.get("started_at");
    alerts.save(
        changed.id(),
        new AlertService.Rule(
            changed.id(),
            "renamed only",
            changed.service(),
            changed.operation(),
            changed.kind(),
            changed.percentile(),
            changed.threshold(),
            changed.windowSec(),
            changed.forSec(),
            changed.minRequests(),
            changed.resolveRatio(),
            true));
    assertEquals(
        started,
        jdbc.queryForMap(
                "SELECT * FROM alert_events WHERE rule_id=? AND state<>'resolved'", rule.id())
            .get("started_at"));
  }
}
