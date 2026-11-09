package dev.tracescope.model;

import java.util.Map;

public record Span(
    String traceId,
    String spanId,
    String parentSpanId,
    String service,
    String name,
    Kind kind,
    long startNs,
    long durationNs,
    boolean error,
    int httpStatus,
    String route,
    String peer,
    boolean database,
    Map<String, String> attributes) {
  public enum Kind {
    INTERNAL,
    SERVER,
    CLIENT,
    PRODUCER,
    CONSUMER
  }

  public Span {
    attributes = Map.copyOf(attributes);
  }

  public long endNs() {
    return Math.addExact(startNs, durationNs);
  }

  public boolean entry() {
    return kind == Kind.SERVER
        || kind == Kind.CONSUMER
        || (kind == Kind.INTERNAL && parentSpanId.isEmpty());
  }

  public long estimatedBytes() {
    return 512L
        + 2L * (name.length() + service.length() + peer.length())
        + attributes.entrySet().stream()
            .mapToLong(e -> 96L + 2L * (e.getKey().length() + e.getValue().length()))
            .sum();
  }
}
