package dev.tracescope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryServiceTest {
  @Test
  void coarseBoundaryBucketsUseTheActualWindowIntersection() {
    var storage = mock(Storage.class);
    var query = new QueryService(storage);
    var start = Instant.parse("2026-11-08T12:01:00Z");
    var window = QueryService.window(start, start.plusSeconds(120));
    long[] buckets = new long[148];
    buckets[1] = 20;
    Map<String, Object> row =
        new LinkedHashMap<>(
            Map.of(
                "service",
                "checkout",
                "time",
                "2026-11-08T12:00:00Z",
                "bucket_epoch",
                start.minusSeconds(60).getEpochSecond(),
                "requests",
                20,
                "errors",
                0,
                "buckets",
                buckets));
    when(storage.query(anyString(), any(Object[].class))).thenReturn(new ArrayList<>(List.of(row)));
    var result = query.series(window, "checkout", 300);
    assertEquals(20.0 / 120, (Double) result.data().getFirst().get("rps"), 1e-12);
  }

  @Test
  void sparseSeriesRetainsElapsedTimeAndUnknownPercentiles() {
    var storage = mock(Storage.class);
    var query = new QueryService(storage);
    var start = Instant.parse("2026-11-08T12:00:00Z");
    var rows = new ArrayList<Map<String, Object>>();
    for (int minute : List.of(0, 10, 11)) {
      long[] buckets = new long[148];
      buckets[1] = 20;
      rows.add(
          new LinkedHashMap<>(
              Map.of(
                  "service",
                  "checkout",
                  "time",
                  "unused",
                  "bucket_epoch",
                  start.plusSeconds(minute * 60).getEpochSecond(),
                  "requests",
                  20,
                  "errors",
                  0,
                  "buckets",
                  buckets)));
    }
    when(storage.query(anyString(), any(Object[].class))).thenReturn(rows);
    var series =
        query
            .series(new QueryService.Window(start, start.plusSeconds(720), "UTC"), "checkout", 60)
            .data();
    assertEquals(12, series.size());
    assertEquals("2026-11-08T12:10:00Z", series.get(10).get("time"));
    assertEquals(
        60, series.stream().mapToLong(row -> QueryService.number(row.get("requests"))).sum());
    for (int i = 1; i < 10; i++) {
      assertEquals(0.0, series.get(i).get("rps"));
      assertNull(series.get(i).get("p95"));
      assertNull(series.get(i).get("errorRate"));
    }
  }

  @Test
  void persistedCompletenessMetadataSurvivesBooleanRepresentations() {
    var storage = mock(Storage.class);
    var query = new QueryService(storage);
    for (Object value : List.of(true, 1, "1", "true")) {
      when(storage.query(anyString(), any(Object[].class)))
          .thenReturn(
              List.of(
                  Map.of(
                      "known_incomplete",
                      value,
                      "finalization_reason",
                      "max_age",
                      "late_span_count",
                      2)));
      var metadata = query.traceMetadata("0123456789abcdef0123456789abcdef");
      assertTrue(metadata.knownIncomplete());
      assertEquals("max_age", metadata.finalizationReason());
      assertEquals(2, metadata.lateSpanCount());
    }
    when(storage.query(anyString(), any(Object[].class))).thenReturn(List.of());
    assertTrue(query.traceMetadata("0123456789abcdef0123456789abcdef").knownIncomplete());
  }

  @Test
  void futureEndCannotIncludeTheCurrentPartialMinute() {
    Instant closed = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    var window = QueryService.window(closed.minusSeconds(120), closed.plusSeconds(3600));
    assertEquals(closed, window.to());
  }
}
