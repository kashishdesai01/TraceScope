package dev.tracescope;

import static org.junit.jupiter.api.Assertions.*;

import dev.tracescope.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PipelineTest {
  static class MutableClock extends Clock {
    long millis = Instant.parse("2026-11-08T20:00:00Z").toEpochMilli();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    public long millis() {
      return millis;
    }

    void advance(long n) {
      millis += n;
    }
  }

  static class FakeStorage implements Storage {
    final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
    final Set<String> tokens = new HashSet<>();
    final List<String> attempts = new ArrayList<>();
    boolean fail, loseAck;

    public void insert(String table, List<Map<String, Object>> batch, String token) {
      if (batch.isEmpty()) return;
      attempts.add(token);
      if (fail) throw new IllegalStateException("offline");
      if (tokens.add(token)) rows.computeIfAbsent(table, k -> new ArrayList<>()).addAll(batch);
      if (loseAck) {
        loseAck = false;
        throw new IllegalStateException("committed but acknowledgment lost");
      }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
      throw new UnsupportedOperationException();
    }

    long sum(String table, String column) {
      return rows.getOrDefault(table, List.of()).stream()
          .mapToLong(r -> ((Number) r.get(column)).longValue())
          .sum();
    }
  }

  static Settings.Values limits(int spans, int frames, int identities) {
    return new Settings.Values(
        "http://localhost:8123",
        "user",
        "password",
        "local-mcp-token",
        1,
        spans,
        64 * 1024 * 1024,
        identities,
        1000,
        frames,
        1000,
        3000,
        10000);
  }

  static Span span(String trace, String id, String parent, String service, long start) {
    return new Span(
        trace,
        id,
        parent,
        service,
        "POST /checkout",
        Span.Kind.SERVER,
        start,
        800_000_000,
        false,
        200,
        "/checkout",
        "",
        false,
        Map.of());
  }

  static Span root(MutableClock c) {
    return span(
        "0123456789abcdef0123456789abcdef",
        "0123456789abcdef",
        "",
        "checkout",
        c.millis() * 1_000_000);
  }

  @Test
  void duplicatesBeforeAndAfterFinalizationDoNotChangeCountsOrHistograms() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
    var s = root(clock);
    assertEquals(1, p.accept(List.of(s, s)));
    assertEquals(0, p.accept(List.of(s)));
    clock.advance(4000);
    p.tick();
    assertEquals(0, p.accept(List.of(s)));
    clock.advance(6000);
    p.tick();
    assertEquals(1, store.sum("red_minute", "requests"));
    assertEquals(1, store.rows.get("spans").size());
    assertEquals(0, p.stats().bufferedSpans());
    long[] histogram = (long[]) store.rows.get("red_minute").getFirst().get("latency_buckets");
    assertEquals(1, Arrays.stream(histogram).sum());
  }

  @Test
  void unavailableTargetsKeepTheirServiceIdentityWithoutAServerChild() {
    for (String target : List.of("payment", "inventory")) {
      var clock = new MutableClock();
      var store = new FakeStorage();
      var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
      var root = root(clock);
      var client =
          new Span(
              root.traceId(),
              "1111111111111111",
              root.spanId(),
              "checkout",
              "POST",
              Span.Kind.CLIENT,
              root.startNs() + 1000000,
              1000000,
              true,
              0,
              "",
              target + "-unavailable",
              false,
              Map.of());
      p.accept(List.of(root, client));
      clock.advance(11000);
      p.tick();
      var edges = store.rows.get("edges_minute");
      assertEquals(1, edges.size());
      assertEquals(target, edges.getFirst().get("callee"));
      assertEquals("service", edges.getFirst().get("callee_kind"));
      assertEquals(1L, edges.getFirst().get("errors"));
    }
  }

  @Test
  void structurallyCompleteTreeStillRecordsMaximumAgeFinalization() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
    var root = root(clock);
    p.accept(List.of(root));
    for (int i = 1; i <= 14; i++) {
      clock.advance(2000);
      p.accept(
          List.of(
              span(
                  root.traceId(),
                  String.format("%016x", i),
                  root.spanId(),
                  "checkout",
                  root.startNs() + 1000)));
      p.tick();
    }
    clock.advance(2000);
    p.tick();
    assertEquals("max_age", store.rows.get("traces").getLast().get("finalization_reason"));
    assertEquals(true, store.rows.get("traces").getLast().get("known_incomplete"));
  }

  @Test
  void rejectedBatchContributesNothing() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(1, 10, 100), store, new SimpleMeterRegistry(), clock);
    var a = root(clock);
    var b = span(a.traceId(), "1111111111111111", a.spanId(), "payment", a.startNs());
    assertThrows(Pipeline.Full.class, () -> p.accept(List.of(a, b)));
    assertEquals(0, p.stats().identities());
    assertEquals(0, p.stats().aggregateKeys());
    assertEquals(1, p.accept(List.of(a)));
  }

  @Test
  void lostAcknowledgmentRetriesIdenticalFlushWithoutDoubleCounting() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
    p.accept(List.of(root(clock)));
    clock.advance(11000);
    store.loseAck = true;
    p.tick();
    assertFalse(p.stats().writerHealthy());
    assertTrue(p.stats().writerFrames() > 0);
    p.tick();
    assertTrue(p.stats().writerHealthy());
    assertEquals(1, store.sum("red_minute", "requests"));
    assertEquals(1, store.rows.get("spans").size());
    assertEquals(store.attempts.get(0), store.attempts.get(1));
  }

  @Test
  void outageIsBoundedThenRecovers() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    store.fail = true;
    var p = new Pipeline(limits(3, 2, 1000), store, new SimpleMeterRegistry(), clock);
    p.accept(List.of(root(clock)));
    clock.advance(11000);
    p.tick();
    assertFalse(p.stats().writerHealthy());
    assertEquals(2, p.stats().writerFrames());
    var next =
        span(
            "22222222222222222222222222222222",
            "2222222222222222",
            "",
            "checkout",
            clock.millis() * 1_000_000);
    assertThrows(Pipeline.Full.class, () -> p.accept(List.of(next)));
    assertEquals(1, p.stats().identities());
    assertTrue(p.stats().estimatedBytes() < 64 * 1024 * 1024);
    store.fail = false;
    p.tick();
    assertEquals(0, p.stats().writerFrames());
    assertEquals(1, p.accept(List.of(next)));
  }

  @Test
  void lateKeptSpanRevisesMetadataAndLookupBoundsButNotEdges() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
    var s = root(clock);
    p.accept(List.of(s));
    clock.advance(4000);
    p.tick();
    var late =
        span(
            s.traceId(), "1111111111111111", s.spanId(), "inventory", s.startNs() + 2_000_000_000L);
    p.accept(List.of(late));
    p.tick();
    var meta = store.rows.get("traces").getLast();
    assertEquals(2, meta.get("span_count"));
    assertEquals(1, meta.get("late_span_count"));
    assertEquals(true, meta.get("known_incomplete"));
    assertTrue(((List<?>) meta.get("services")).contains("inventory"));
    assertEquals(
        Pipeline.time(late.endNs()), store.rows.get("trace_index").getLast().get("end_time"));
    assertEquals(0, p.accept(List.of(late)));
  }

  @Test
  void identityCapacityRejectsRatherThanEvictingUnexpiredKeys() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1), store, new SimpleMeterRegistry(), clock);
    var s = root(clock);
    p.accept(List.of(s));
    clock.advance(11000);
    p.tick();
    var next =
        span(
            "22222222222222222222222222222222",
            "2222222222222222",
            "",
            "checkout",
            clock.millis() * 1_000_000);
    assertThrows(Pipeline.Full.class, () -> p.accept(List.of(next)));
    assertEquals(0, p.accept(List.of(s)));
    clock.advance(Pipeline.DEDUPE_MS);
    assertEquals(1, p.accept(List.of(next)));
  }

  @Test
  void operationalRoutesDoNotInflateApplicationRedCounts() {
    var clock = new MutableClock();
    var store = new FakeStorage();
    var p = new Pipeline(limits(100, 100, 1000), store, new SimpleMeterRegistry(), clock);
    var s = root(clock);
    var health =
        new Span(
            s.traceId(),
            s.spanId(),
            "",
            "gateway",
            "GET /actuator/health/readiness",
            Span.Kind.SERVER,
            s.startNs(),
            1,
            false,
            200,
            "/actuator/health/readiness",
            "",
            false,
            Map.of());
    p.accept(List.of(health));
    clock.advance(11000);
    p.tick();
    assertEquals(0, store.sum("red_minute", "requests"));
  }

  @Test
  void minuteAlignmentIsVisibleAndRejectsEmptyOrOversizedWindows() {
    var w =
        QueryService.window(
            Instant.parse("2026-11-08T12:00:30Z"), Instant.parse("2026-11-08T12:02:30Z"));
    assertEquals(Instant.parse("2026-11-08T12:00:00Z"), w.from());
    assertEquals(120, w.seconds());
    assertThrows(IllegalArgumentException.class, () -> QueryService.window(w.from(), w.from()));
    assertThrows(
        IllegalArgumentException.class,
        () -> QueryService.window(w.from(), w.from().plusSeconds(86460)));
  }
}
