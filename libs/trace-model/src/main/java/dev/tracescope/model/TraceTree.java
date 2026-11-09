package dev.tracescope.model;

import java.util.*;

public final class TraceTree {
  public record Node(Span span, String parentId, int depth, boolean missingParent, boolean cycle) {}

  public record Tree(List<Node> nodes, List<String> rootIds, boolean knownIncomplete) {}

  public static Tree build(Collection<Span> input) {
    Map<String, Span> spans = new TreeMap<>();
    for (var s : input) spans.putIfAbsent(s.spanId(), s);
    List<Node> nodes = new ArrayList<>();
    List<String> roots = new ArrayList<>();
    boolean incomplete = false;
    for (var s : spans.values()) {
      Set<String> seen = new HashSet<>();
      seen.add(s.spanId());
      Span cursor = s;
      int depth = 0;
      boolean missing = false, cycle = false;
      while (!cursor.parentSpanId().isEmpty()) {
        if (!seen.add(cursor.parentSpanId())) {
          cycle = true;
          break;
        }
        cursor = spans.get(cursor.parentSpanId());
        if (cursor == null) {
          missing = true;
          break;
        }
        depth++;
      }
      String parent = cycle || !spans.containsKey(s.parentSpanId()) ? "" : s.parentSpanId();
      if (parent.isEmpty()) roots.add(s.spanId());
      incomplete |= missing || cycle;
      nodes.add(new Node(s, parent, cycle ? 0 : depth, missing, cycle));
    }
    nodes.sort(
        Comparator.comparingLong((Node n) -> n.span().startNs())
            .thenComparing(n -> n.span().spanId()));
    boolean hasRoot = spans.values().stream().anyMatch(s -> s.parentSpanId().isEmpty());
    return new Tree(List.copyOf(nodes), List.copyOf(roots), incomplete || !hasRoot);
  }
}
