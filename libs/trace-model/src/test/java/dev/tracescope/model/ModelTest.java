package dev.tracescope.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

class ModelTest {
  static Span span(
      String id,
      String parent,
      String svc,
      Span.Kind kind,
      long start,
      long duration,
      boolean error,
      String peer,
      boolean db) {
    return new Span(
        "0123456789abcdef0123456789abcdef",
        id,
        parent,
        svc,
        "op",
        kind,
        start,
        duration,
        error,
        0,
        "",
        peer,
        db,
        Map.of());
  }

  @Test
  void treeIsOrderAndDuplicateInvariant() {
    var root = span("a", "", "gateway", Span.Kind.SERVER, 100, 100, false, "", false);
    var child = span("b", "a", "gateway", Span.Kind.CLIENT, 120, 60, false, "payment", false);
    assertEquals(
        TraceTree.build(List.of(root, child)), TraceTree.build(List.of(child, root, child)));
    assertFalse(TraceTree.build(List.of(root, child)).knownIncomplete());
    assertTrue(TraceTree.build(List.of(child)).knownIncomplete());
  }

  @Test
  void cyclesAreFlaggedAndDoNotHang() {
    var a = span("a", "b", "a", Span.Kind.SERVER, 100, 100, false, "", false);
    var b = span("b", "a", "a", Span.Kind.CLIENT, 110, 80, false, "", false);
    assertTrue(TraceTree.build(List.of(a, b)).knownIncomplete());
    assertTrue(CriticalPath.compute(List.of(a, b)).approximate());
  }

  @Test
  void missingAncestorPreservesAvailableSubtree() {
    var root = span("r", "", "gateway", Span.Kind.SERVER, 100, 100, false, "", false);
    var orphan = span("o", "absent", "checkout", Span.Kind.SERVER, 110, 80, false, "", false);
    var child = span("c", "o", "checkout", Span.Kind.CLIENT, 120, 60, false, "payment", false);
    var grandchild = span("g", "c", "payment", Span.Kind.SERVER, 125, 50, false, "", false);
    var tree = TraceTree.build(List.of(root, orphan, child, grandchild));
    assertEquals(tree, TraceTree.build(List.of(grandchild, child, orphan, root, child)));
    assertTrue(tree.knownIncomplete());
    assertEquals(Set.of("r", "o"), new HashSet<>(tree.rootIds()));
    var nodes = new HashMap<String, TraceTree.Node>();
    tree.nodes().forEach(n -> nodes.put(n.span().spanId(), n));
    assertTrue(nodes.get("o").missingParent());
    assertEquals("", nodes.get("o").parentId());
    assertEquals(0, nodes.get("o").depth());
    assertEquals("o", nodes.get("c").parentId());
    assertEquals(1, nodes.get("c").depth());
    assertTrue(nodes.get("c").missingParent());
    assertEquals("c", nodes.get("g").parentId());
    assertEquals(2, nodes.get("g").depth());
    assertTrue(nodes.get("g").missingParent());
    assertTrue(CriticalPath.compute(List.of(orphan, child, grandchild)).approximate());
  }

  @Test
  void sequentialTimingPartitionsRoot() {
    var r = span("r", "", "checkout", Span.Kind.SERVER, 100, 100, false, "", false);
    var a = span("a", "r", "checkout", Span.Kind.CLIENT, 110, 30, false, "payment", false);
    var b = span("b", "r", "checkout", Span.Kind.CLIENT, 150, 40, false, "inventory", false);
    var p = span("p", "a", "payment", Span.Kind.SERVER, 112, 26, false, "", false);
    var path = CriticalPath.compute(List.of(r, a, b, p));
    assertEquals(100, path.segments().stream().mapToLong(CriticalPath.Segment::durationNs).sum());
    assertEquals(
        26,
        path.segments().stream()
            .filter(s -> s.service().equals("payment"))
            .mapToLong(CriticalPath.Segment::durationNs)
            .sum());
  }

  @Test
  void parallelCallsDoNotDoubleCount() {
    var r = span("r", "", "checkout", Span.Kind.SERVER, 100, 100, false, "", false);
    var a = span("a", "r", "payment", Span.Kind.CLIENT, 110, 70, false, "", false);
    var b = span("b", "r", "inventory", Span.Kind.CLIENT, 120, 70, false, "", false);
    var path = CriticalPath.compute(List.of(r, a, b));
    assertEquals(100, path.segments().stream().mapToLong(CriticalPath.Segment::durationNs).sum());
    assertTrue(path.segments().stream().anyMatch(s -> s.spanId().equals("b")));
  }

  @Test
  void skewAdjustmentPreservesRawClockAndDuration() {
    var r = span("r", "", "checkout", Span.Kind.CLIENT, 100, 100, false, "", false);
    var c = span("c", "r", "payment", Span.Kind.SERVER, 70, 80, false, "", false);
    var path = CriticalPath.compute(List.of(r, c));
    assertTrue(
        path.display().stream()
            .anyMatch(d -> d.spanId().equals("c") && d.skewAdjusted() && d.startNs() == 110));
    assertEquals(70, c.startNs());
    assertEquals(80, c.durationNs());
  }

  @Test
  void childBeyondParentIsClippedAndFlagged() {
    var root = span("r", "", "checkout", Span.Kind.SERVER, 100, 100, false, "", false);
    var child = span("c", "r", "payment", Span.Kind.INTERNAL, 150, 100, false, "", false);
    var path = CriticalPath.compute(List.of(root, child));
    assertTrue(path.approximate());
    assertEquals(100, path.segments().stream().mapToLong(CriticalPath.Segment::durationNs).sum());
    assertEquals(
        50,
        path.segments().stream()
            .filter(s -> s.spanId().equals("c"))
            .mapToLong(CriticalPath.Segment::durationNs)
            .sum());
  }

  @Test
  void histogramBoundariesAreExplicit() {
    var h = new LatencyHistogram();
    assertNull(h.percentile(.95).ms());
    h.record(0);
    assertEquals("below_0.1ms", h.percentile(.95).range());
    h.record(Long.MAX_VALUE);
    assertEquals("overflow", h.percentile(1).range());
    assertThrows(IllegalArgumentException.class, () -> h.record(-1));
    assertThrows(IllegalArgumentException.class, () -> h.percentile(0));
  }

  @Test
  void histogramQuantilesMatchBimodalAndLognormalSamples() {
    Random random = new Random(1234);
    for (int sample = 0; sample < 40; sample++) {
      long[] values = new long[10000];
      var h = new LatencyHistogram();
      for (int i = 0; i < values.length; i++) {
        double ms =
            sample % 2 == 0
                ? Math.exp(3 + random.nextGaussian())
                : ((random.nextDouble() < .9 ? 10 : 1000) * (1 + random.nextDouble()));
        values[i] = (long) (Math.max(.11, Math.min(100000, ms)) * 1e6);
        h.record(values[i]);
      }
      Arrays.sort(values);
      for (double q : new double[] {.5, .95, .99}) {
        double exact = values[(int) Math.ceil(q * values.length) - 1] / 1e6;
        assertTrue(Math.abs(h.percentile(q).ms() - exact) / exact <= .049, "q=" + q);
      }
    }
  }

  @Test
  void entriesAndFailedEdgesAreWellDefined() {
    var a = span("a", "", "gateway", Span.Kind.SERVER, 100, 100, false, "", false);
    var b = span("b", "a", "gateway", Span.Kind.CLIENT, 110, 30, true, "inventory", false);
    var db =
        span("d", "a", "gateway", Span.Kind.CLIENT, 150, 20, false, "postgresql:payments", true);
    assertTrue(a.entry());
    assertFalse(
        span(
                "startup",
                "",
                "payment",
                Span.Kind.CLIENT,
                100,
                10,
                false,
                "postgresql:payments",
                true)
            .entry());
    assertFalse(
        span("producer", "", "payment", Span.Kind.PRODUCER, 100, 10, false, "", false).entry());
    assertTrue(
        span("transaction", "", "payment", Span.Kind.INTERNAL, 100, 10, false, "", false).entry());
    assertFalse(b.entry());
    var edges = EdgeDeriver.derive(List.of(a, b, db), Map.of("inventory", "inventory"));
    assertEquals(2, edges.size());
    assertEquals("service", edges.getFirst().calleeKind());
    assertTrue(edges.getFirst().error());
    assertEquals("database", edges.get(1).calleeKind());
  }

  @Test
  void samplerKeepsErrorsAndSlowTracesAndIsStable() {
    var error = span("a", "", "a", Span.Kind.SERVER, 1, 1, true, "", false);
    assertEquals("error", Sampler.reason(List.of(error), 0, 500000000));
    var slow = span("a", "", "a", Span.Kind.SERVER, 1, 800000000, false, "", false);
    assertEquals("slow", Sampler.reason(List.of(slow), 0, 500000000));
    Random random = new Random(42);
    int kept = 0;
    for (int i = 0; i < 100000; i++) {
      var s =
          new Span(
              String.format("%032x", random.nextLong()),
              "a",
              "",
              "a",
              "op",
              Span.Kind.SERVER,
              1,
              1,
              false,
              0,
              "",
              "",
              false,
              Map.of());
      String reason = Sampler.reason(List.of(s), .25, 500000000);
      assertEquals(reason, Sampler.reason(List.of(s), .25, 500000000));
      if (reason.equals("sampled")) kept++;
    }
    assertTrue(kept >= 24000 && kept <= 26000, "kept=" + kept);
  }

  @Test
  void sensitiveKeysAreCaseInsensitiveSubstrings() {
    for (String key :
        List.of(
            "Authorization",
            "http.request.header.cookie",
            "db.password",
            "SECRET",
            "x-token",
            "API_KEY",
            "api-key")) assertTrue(OtlpMapper.sensitive(key));
    assertFalse(OtlpMapper.sensitive("http.route"));
  }
}
