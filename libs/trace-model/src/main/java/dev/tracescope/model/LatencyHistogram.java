package dev.tracescope.model;

import java.util.Arrays;

/** Finite buckets have <= sqrt(1.1)-1 relative error. Under/overflow are explicitly censored. */
public final class LatencyHistogram {
  public static final int SIZE = 148;
  public static final double MIN_MS = 0.1;
  public static final double OVERFLOW_MS = MIN_MS * Math.pow(1.1, SIZE - 2);
  private final long[] buckets;

  public LatencyHistogram() {
    buckets = new long[SIZE];
  }

  public LatencyHistogram(long[] source) {
    if (source.length != SIZE || Arrays.stream(source).anyMatch(n -> n < 0))
      throw new IllegalArgumentException("Invalid histogram");
    buckets = source.clone();
  }

  public void record(long ns) {
    if (ns < 0) throw new IllegalArgumentException("Negative latency");
    double ms = ns / 1_000_000.0;
    int index =
        ms < MIN_MS
            ? 0
            : Math.min(SIZE - 1, 1 + (int) Math.floor(Math.log(ms / MIN_MS) / Math.log(1.1)));
    buckets[index] = Math.addExact(buckets[index], 1);
  }

  public void merge(LatencyHistogram other) {
    for (int i = 0; i < SIZE; i++) buckets[i] = Math.addExact(buckets[i], other.buckets[i]);
  }

  public long[] buckets() {
    return buckets.clone();
  }

  public long count() {
    return Arrays.stream(buckets).sum();
  }

  public record Quantile(Double ms, String range) {}

  public Quantile percentile(double q) {
    if (!(q > 0 && q <= 1)) throw new IllegalArgumentException("Percentile must be in (0,1]");
    long total = count();
    if (total == 0) return new Quantile(null, "empty");
    long rank = (long) Math.ceil(q * total), seen = 0;
    for (int i = 0; i < SIZE; i++) {
      seen += buckets[i];
      if (seen < rank) continue;
      if (i == 0) return new Quantile(MIN_MS, "below_0.1ms");
      if (i == SIZE - 1) return new Quantile(OVERFLOW_MS, "overflow");
      return new Quantile(MIN_MS * Math.pow(1.1, i - 0.5), "finite");
    }
    throw new IllegalStateException("Histogram count overflow");
  }
}
