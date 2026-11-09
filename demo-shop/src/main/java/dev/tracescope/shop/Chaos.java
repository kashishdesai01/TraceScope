package dev.tracescope.shop;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Chaos {
  public record Fault(int latencyMs, double errorRate, int dbSlowMs, String downstreamDown) {
    public Fault {
      if (latencyMs < 0
          || latencyMs > 5000
          || dbSlowMs < 0
          || dbSlowMs > 5000
          || !Double.isFinite(errorRate)
          || errorRate < 0
          || errorRate > 1)
        throw new IllegalArgumentException("Fault values outside allowed bounds");
      if (downstreamDown != null
          && !downstreamDown.isEmpty()
          && !downstreamDown.equals("inventory")
          && !downstreamDown.equals("payment"))
        throw new IllegalArgumentException("downstreamDown must be inventory or payment");
    }
  }

  private Fault fault = new Fault(0, 0, 0, "");
  private Random random = new Random(42);
  private long injected, requests;

  public synchronized Fault get() {
    return fault;
  }

  public synchronized void set(Fault f) {
    fault = f;
    random = new Random(42);
    injected = 0;
    requests = 0;
  }

  public synchronized Map<String, Object> stats() {
    return Map.of("injectedErrors", injected, "requests", requests, "fault", fault);
  }

  public void apply() {
    boolean error;
    int latency;
    synchronized (this) {
      requests++;
      latency = fault.latencyMs;
      error = random.nextDouble() < fault.errorRate;
      if (error) injected++;
    }
    if (latency > 0)
      try {
        Thread.sleep(latency);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
      }
    if (error)
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Injected service failure");
  }
}
