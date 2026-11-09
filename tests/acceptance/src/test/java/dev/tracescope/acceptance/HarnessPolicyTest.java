package dev.tracescope.acceptance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HarnessPolicyTest {
  final Instant start = Instant.parse("2026-11-09T12:10:00Z");
  final Instant end = Instant.parse("2026-11-09T12:18:30Z");

  ObjectNode event(String fired, String resolved) {
    ObjectNode value = new ObjectMapper().createObjectNode();
    value.put("started_at", "2026-11-09T12:00:00Z");
    if (fired == null) value.putNull("firing_at");
    else value.put("firing_at", fired);
    if (resolved == null) value.putNull("resolved_at");
    else value.put("resolved_at", resolved);
    return value;
  }

  boolean overlaps(String fired, String resolved) {
    return GroundTruthTest.firingOverlaps(event(fired, resolved), start, end);
  }

  @Test
  void resolvedStartupHistoryDoesNotInvalidateMeasuredBaseline() {
    assertFalse(overlaps("2026-11-09T12:01:00Z", "2026-11-09T12:05:00Z"));
  }

  @Test
  void activeOrBoundaryOverlappingFiringStillFails() {
    assertAll(
        () -> assertTrue(overlaps("2026-11-09T12:01:00Z", null)),
        () -> assertTrue(overlaps("2026-11-09T12:01:00Z", "2026-11-09T12:10:00Z")),
        () -> assertTrue(overlaps("2026-11-09T12:12:00Z", "2026-11-09T12:13:00Z")),
        () -> assertTrue(overlaps("2026-11-09T12:18:30Z", null)));
  }

  @Test
  void actualFiringControlsIntervalRatherThanEarlierPendingStart() {
    assertAll(
        () -> assertTrue(overlaps("2026-11-09T12:11:00Z", null)),
        () -> assertFalse(overlaps("2026-11-09T12:19:00Z", null)),
        () -> assertFalse(overlaps(null, "2026-11-09T12:04:00Z")));
  }
}
