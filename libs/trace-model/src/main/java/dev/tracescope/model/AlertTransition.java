package dev.tracescope.model;

import java.time.*;

/** Pure alert lifecycle; timestamps survive evaluator restarts through the event store. */
public final class AlertTransition {
  public record Rule(double threshold, double resolveRatio, int forSeconds) {}

  public record Event(
      String state,
      Instant startedAt,
      Instant firingAt,
      Instant recoverySince,
      Instant resolvedAt,
      Double currentValue,
      Double peakValue,
      boolean noData,
      String resolution) {}

  public static Event evaluate(Rule rule, Event previous, Double value, Instant now) {
    boolean noData = value == null || !Double.isFinite(value);
    boolean breach = !noData && value > rule.threshold;
    if (previous == null || previous.state.equals("resolved")) {
      if (!breach) return null;
      return new Event(
          rule.forSeconds == 0 ? "firing" : "pending",
          now,
          rule.forSeconds == 0 ? now : null,
          null,
          null,
          value,
          value,
          false,
          null);
    }
    Double peak =
        noData
            ? previous.peakValue
            : previous.peakValue == null ? value : Math.max(value, previous.peakValue);
    if (previous.state.equals("pending")) {
      if (!breach)
        return new Event(
            "resolved",
            previous.startedAt,
            null,
            null,
            now,
            noData ? null : value,
            peak,
            noData,
            noData ? "no_data" : "breach_cleared");
      boolean firing = Duration.between(previous.startedAt, now).getSeconds() >= rule.forSeconds;
      return new Event(
          firing ? "firing" : "pending",
          previous.startedAt,
          firing ? now : null,
          null,
          null,
          value,
          peak,
          false,
          null);
    }
    if (noData)
      return new Event(
          "firing", previous.startedAt, previous.firingAt, null, null, null, peak, true, null);
    boolean recovering = value < rule.threshold * rule.resolveRatio;
    Instant recovery =
        recovering ? (previous.recoverySince == null ? now : previous.recoverySince) : null;
    boolean resolved =
        recovery != null && Duration.between(recovery, now).getSeconds() >= rule.forSeconds;
    return new Event(
        resolved ? "resolved" : "firing",
        previous.startedAt,
        previous.firingAt,
        recovery,
        resolved ? now : null,
        value,
        peak,
        false,
        resolved ? "recovered" : null);
  }
}
