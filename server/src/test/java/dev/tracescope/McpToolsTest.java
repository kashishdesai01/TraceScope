package dev.tracescope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.tracescope.model.Span;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpToolsTest {
  final QueryService queries = mock(QueryService.class);
  final AlertService alerts = mock(AlertService.class);
  final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  final McpTools tools = new McpTools(queries, alerts, json);

  @org.junit.jupiter.api.BeforeEach
  void metadata() {
    when(queries.traceMetadata(anyString()))
        .thenReturn(new QueryService.TraceMetadata(false, "root_idle", 0));
  }

  @Test
  void summaryRetainsPersistedLateAndMaximumAgeQualifications() throws Exception {
    var root = span("r", "", "checkout", Span.Kind.SERVER, 100, 1000000000);
    when(queries.spans(root.traceId())).thenReturn(List.of(root));
    for (String reason : List.of("root_idle", "max_age")) {
      when(queries.traceMetadata(root.traceId()))
          .thenReturn(new QueryService.TraceMetadata(true, reason, 1));
      var result = json.readTree(tools.traceSummary(root.traceId()));
      assertTrue(result.get("approximate").asBoolean());
      assertTrue(result.get("metadata").get("knownIncomplete").asBoolean());
      assertEquals(reason, result.get("metadata").get("finalizationReason").asText());
      assertEquals(1, result.get("metadata").get("lateSpanCount").asInt());
    }
  }

  Span span(String id, String parent, String service, Span.Kind kind, long start, long duration) {
    return new Span(
        "0123456789abcdef0123456789abcdef",
        id,
        parent,
        service,
        "POST /checkout",
        kind,
        start,
        duration,
        false,
        200,
        "/checkout",
        "",
        false,
        Map.of(
            "authorization",
            "hidden",
            "user.email",
            "private@example.test",
            "http.route",
            "/checkout"));
  }

  @Test
  void latencySummaryNamesPaymentAndNeverReturnsRawAttributes() throws Exception {
    var root = span("r", "", "checkout", Span.Kind.SERVER, 100, 1000000000);
    var client = span("c", "r", "checkout", Span.Kind.CLIENT, 100000100, 800000000);
    var payment = span("p", "c", "payment", Span.Kind.SERVER, 110000100, 780000000);
    when(queries.spans(root.traceId())).thenReturn(List.of(root, client, payment));
    String output = tools.traceSummary(root.traceId());
    var data = json.readTree(output);
    assertEquals("payment", data.get("segments").get(0).get("service").asText());
    assertTrue(output.length() <= 6000);
    assertFalse(output.contains("authorization"));
    assertFalse(output.contains("user.email"));
    assertFalse(output.contains("private@example.test"));
    assertFalse(output.contains("hidden"));
    assertTrue(output.contains("http.route"));
  }

  @Test
  void twoThousandSpanSummaryStaysValidAndReportsOmissions() throws Exception {
    List<Span> spans = new ArrayList<>();
    spans.add(span("r", "", "checkout", Span.Kind.SERVER, 1, 1000000000));
    for (int i = 0; i < 2000; i++)
      spans.add(span("s" + i, "r", "payment", Span.Kind.INTERNAL, 1 + i * 100000, 50000));
    when(queries.spans("0123456789abcdef0123456789abcdef")).thenReturn(spans);
    String result = tools.traceSummary("0123456789abcdef0123456789abcdef");
    assertTrue(result.length() <= 6000);
    var data = json.readTree(result);
    assertEquals(2001, data.get("spanCount").asInt());
    assertTrue(data.get("omittedSegments").asInt() > 0);
  }

  @Test
  void hardCapHandlesImmutableListsAndLongValues() throws Exception {
    Map<String, Object> payload =
        new LinkedHashMap<>(Map.of("items", List.of("x".repeat(5000), "y".repeat(5000))));
    String result = tools.bounded(payload);
    assertTrue(result.length() <= 6000);
    assertEquals(1, json.readTree(result).get("omittedItems").asInt());
  }

  @Test
  void invalidToolWindowsAndLimitsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> tools.listServices(0));
    assertThrows(IllegalArgumentException.class, () -> tools.slowTraces("payment", 15, 500., 11));
    assertThrows(IllegalArgumentException.class, () -> tools.dependencies("", 15));
  }
}
