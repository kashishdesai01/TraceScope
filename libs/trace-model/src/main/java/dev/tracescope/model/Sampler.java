package dev.tracescope.model;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class Sampler {
  public static String reason(Collection<Span> spans, double rate, long slowNs) {
    if (rate < 0 || rate > 1) throw new IllegalArgumentException("Invalid rate");
    if (spans.stream().anyMatch(Span::error)) return "error";
    if (spans.stream().anyMatch(s -> s.parentSpanId().isEmpty() && s.durationNs() >= slowNs))
      return "slow";
    String id = spans.iterator().next().traceId();
    long hash = 0xcbf29ce484222325L;
    for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
      hash ^= b & 255;
      hash *= 0x100000001b3L;
    }
    return Math.floorMod(hash, 10000) < rate * 10000 ? "sampled" : "dropped";
  }
}
