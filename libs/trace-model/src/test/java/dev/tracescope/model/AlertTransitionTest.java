package dev.tracescope.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class AlertTransitionTest {
  final Instant start = Instant.parse("2026-11-08T00:00:00Z");
  final AlertTransition.Rule rule = new AlertTransition.Rule(500, .8, 60);

  @Test
  void sustainedBreachFiresAndRecoveryRequiresItsOwnPersistedTimer() {
    var pending = AlertTransition.evaluate(rule, null, 800., start);
    assertEquals("pending", pending.state());
    assertEquals(
        "pending", AlertTransition.evaluate(rule, pending, 800., start.plusSeconds(59)).state());
    var firing = AlertTransition.evaluate(rule, pending, 800., start.plusSeconds(60));
    assertEquals("firing", firing.state());
    var recovering = AlertTransition.evaluate(rule, firing, 100., start.plusSeconds(70));
    assertEquals(start.plusSeconds(70), recovering.recoverySince());
    assertEquals(
        "firing", AlertTransition.evaluate(rule, recovering, 100., start.plusSeconds(129)).state());
    var resolved = AlertTransition.evaluate(rule, recovering, 100., start.plusSeconds(130));
    assertEquals("resolved", resolved.state());
    assertEquals("recovered", resolved.resolution());
    assertEquals(800., resolved.peakValue());
  }

  @Test
  void noDataCancelsPendingButPreservesFiringAndResetsRecovery() {
    var pending = AlertTransition.evaluate(rule, null, 800., start);
    assertEquals(
        "resolved", AlertTransition.evaluate(rule, pending, null, start.plusSeconds(15)).state());
    var firing = AlertTransition.evaluate(rule, pending, 800., start.plusSeconds(60));
    var recovering = AlertTransition.evaluate(rule, firing, 100., start.plusSeconds(75));
    var noData = AlertTransition.evaluate(rule, recovering, null, start.plusSeconds(90));
    assertEquals("firing", noData.state());
    assertTrue(noData.noData());
    assertNull(noData.recoverySince());
    assertNull(noData.currentValue());
    var resumed = AlertTransition.evaluate(rule, noData, 100., start.plusSeconds(100));
    assertEquals(start.plusSeconds(100), resumed.recoverySince());
  }

  @Test
  void grayBandAndNewBreachResetRecovery() {
    var firing = AlertTransition.evaluate(new AlertTransition.Rule(500, .8, 0), null, 800., start);
    var recovering = AlertTransition.evaluate(rule, firing, 100., start.plusSeconds(1));
    assertNull(
        AlertTransition.evaluate(rule, recovering, 450., start.plusSeconds(30)).recoverySince());
    assertNull(
        AlertTransition.evaluate(rule, recovering, 800., start.plusSeconds(30)).recoverySince());
    assertNull(AlertTransition.evaluate(rule, null, null, start));
    assertNull(AlertTransition.evaluate(rule, null, 100., start));
  }

  @Test
  void nonSustainedBreachAndResolvedEventsDoNotKeepPendingTime() {
    var pending = AlertTransition.evaluate(rule, null, 800., start);
    var cleared = AlertTransition.evaluate(rule, pending, 100., start.plusSeconds(30));
    assertEquals("resolved", cleared.state());
    var fresh = AlertTransition.evaluate(rule, cleared, 800., start.plusSeconds(40));
    assertEquals(start.plusSeconds(40), fresh.startedAt());
    assertEquals("pending", fresh.state());
  }
}
