package dev.tracescope.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class HistogramProperties {
  @Property(tries = 200)
  void mergingIsAssociativeAndCommutative(
      @ForAll @net.jqwik.api.constraints.LongRange(min = 100000, max = 100000000000L) long a,
      @ForAll @net.jqwik.api.constraints.LongRange(min = 100000, max = 100000000000L) long b,
      @ForAll @net.jqwik.api.constraints.LongRange(min = 100000, max = 100000000000L) long c) {
    var ha = new LatencyHistogram();
    ha.record(a);
    var hb = new LatencyHistogram();
    hb.record(b);
    var hc = new LatencyHistogram();
    hc.record(c);
    var ab = new LatencyHistogram(ha.buckets());
    ab.merge(hb);
    var ba = new LatencyHistogram(hb.buckets());
    ba.merge(ha);
    assertArrayEquals(ab.buckets(), ba.buckets());
    ab.merge(hc);
    var bc = new LatencyHistogram(hb.buckets());
    bc.merge(hc);
    var abc = new LatencyHistogram(ha.buckets());
    abc.merge(bc);
    assertArrayEquals(ab.buckets(), abc.buckets());
    for (double q : new double[] {.5, .95, .99}) {
      long[] vals = {a, b, c};
      Arrays.sort(vals);
      double exact = vals[(int) Math.ceil(q * 3) - 1] / 1e6;
      assertTrue(Math.abs(abc.percentile(q).ms() - exact) / exact <= .049);
    }
  }
}
