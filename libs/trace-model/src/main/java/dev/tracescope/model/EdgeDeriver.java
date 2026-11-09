package dev.tracescope.model;

import java.util.*;

public final class EdgeDeriver {
  public record Edge(
      String caller,
      String callee,
      String calleeKind,
      long startNs,
      long durationNs,
      boolean error,
      String spanId) {}

  public static List<Edge> derive(Collection<Span> spans, Map<String, String> knownTargets) {
    Map<String, Span> servers = new HashMap<>();
    for (var s : spans) if (s.kind() == Span.Kind.SERVER) servers.putIfAbsent(s.parentSpanId(), s);
    List<Edge> edges = new ArrayList<>();
    for (var s : spans)
      if (s.kind() == Span.Kind.CLIENT) {
        Span child = servers.get(s.spanId());
        String callee, kind;
        if (child != null && !child.service().equals(s.service())) {
          callee = child.service();
          kind = "service";
        } else if (s.database()) {
          callee = s.peer();
          kind = "database";
        } else if (knownTargets.containsKey(s.peer())) {
          callee = knownTargets.get(s.peer());
          kind = "service";
        } else if (!s.peer().isBlank()) {
          callee = s.peer();
          kind = "external";
        } else continue;
        edges.add(
            new Edge(
                s.service(),
                callee,
                kind,
                s.startNs(),
                s.durationNs(),
                s.error() || (child != null && child.error()),
                s.spanId()));
      }
    return List.copyOf(edges);
  }
}
