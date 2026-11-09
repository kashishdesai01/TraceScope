package dev.tracescope.model;

import java.util.*;

/** Synchronous timing attribution, not causal analysis of arbitrary async traces. */
public final class CriticalPath {
  public record Segment(
      String spanId,
      String service,
      String name,
      long startNs,
      long durationNs,
      double pctOfTotal) {}

  public record Display(String spanId, long startNs, boolean skewAdjusted) {}

  public record Result(
      List<Segment> segments, List<Display> display, long durationNs, boolean approximate) {}

  public static Result compute(Collection<Span> input) {
    if (input.isEmpty()) return new Result(List.of(), List.of(), 0, true);
    var tree = TraceTree.build(input);
    Map<String, Span> spans = new HashMap<>();
    Map<String, List<Span>> children = new HashMap<>();
    for (var n : tree.nodes()) {
      spans.put(n.span().spanId(), n.span());
      if (!n.parentId().isEmpty())
        children.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n.span());
    }
    Span root =
        tree.rootIds().stream()
            .map(spans::get)
            .filter(Objects::nonNull)
            .min(Comparator.comparingLong(Span::startNs).thenComparing(Span::spanId))
            .orElse(tree.nodes().getFirst().span());
    Map<String, Long> starts = new HashMap<>();
    List<Display> display = new ArrayList<>();
    adjust(root, root.startNs(), false, children, starts, display, new HashSet<>());
    for (var n : tree.nodes())
      if (!starts.containsKey(n.span().spanId())) {
        starts.put(n.span().spanId(), n.span().startNs());
        display.add(new Display(n.span().spanId(), n.span().startNs(), false));
      }
    List<Segment> result = new ArrayList<>();
    path(
        root,
        root.startNs(),
        root.endNs(),
        root.durationNs(),
        children,
        starts,
        result,
        new HashSet<>());
    result.sort(Comparator.comparingLong(Segment::startNs).thenComparing(Segment::spanId));
    return new Result(
        List.copyOf(result),
        List.copyOf(display),
        root.durationNs(),
        tree.knownIncomplete()
            || tree.rootIds().size() != 1
            || display.stream().anyMatch(Display::skewAdjusted)
            || tree.nodes().stream()
                .anyMatch(
                    n -> {
                      var parent = spans.get(n.parentId());
                      return parent != null
                          && (n.span().startNs() < parent.startNs()
                              || n.span().endNs() > parent.endNs());
                    }));
  }

  private static void adjust(
      Span s,
      long start,
      boolean inherited,
      Map<String, List<Span>> children,
      Map<String, Long> starts,
      List<Display> display,
      Set<String> seen) {
    if (!seen.add(s.spanId())) return;
    starts.put(s.spanId(), start);
    display.add(new Display(s.spanId(), start, inherited));
    long shift = start - s.startNs();
    for (var c : children.getOrDefault(s.spanId(), List.of())) {
      long cs = c.startNs() + shift;
      boolean skew = false;
      if (s.kind() == Span.Kind.CLIENT
          && c.kind() == Span.Kind.SERVER
          && (cs < start || cs + c.durationNs() > start + s.durationNs())) {
        cs = start + (s.durationNs() - c.durationNs()) / 2;
        skew = true;
      }
      adjust(c, cs, inherited || skew, children, starts, display, seen);
    }
  }

  private static void path(
      Span s,
      long left,
      long right,
      long total,
      Map<String, List<Span>> children,
      Map<String, Long> starts,
      List<Segment> out,
      Set<String> seen) {
    if (!seen.add(s.spanId()) || right <= left) return;
    long cursor = right;
    while (cursor > left) {
      final long boundary = cursor;
      Span child =
          children.getOrDefault(s.spanId(), List.of()).stream()
              .filter(
                  c ->
                      starts.get(c.spanId()) < boundary
                          && Math.min(starts.get(c.spanId()) + c.durationNs(), right) <= boundary
                          && starts.get(c.spanId()) + c.durationNs() > left)
              .max(
                  Comparator.comparingLong((Span c) -> starts.get(c.spanId()) + c.durationNs())
                      .thenComparing(Span::spanId))
              .orElse(null);
      if (child == null) {
        add(s, left, cursor, total, out);
        break;
      }
      long end = Math.min(starts.get(child.spanId()) + child.durationNs(), right),
          start = Math.max(left, starts.get(child.spanId()));
      add(s, end, cursor, total, out);
      path(child, start, end, total, children, starts, out, seen);
      cursor = start;
    }
  }

  private static void add(Span s, long start, long end, long total, List<Segment> out) {
    if (end > start)
      out.add(
          new Segment(
              s.spanId(),
              s.service(),
              s.name(),
              start,
              end - start,
              total == 0 ? 0 : 100.0 * (end - start) / total));
  }
}
