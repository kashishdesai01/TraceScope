package dev.tracescope.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ShopTest {
  @Test
  void seededErrorsHaveExactIndependentCounterAndReset() {
    var chaos = new Chaos();
    var fault = new Chaos.Fault(0, .2, 0, "");
    chaos.set(fault);
    int failures = 0;
    for (int i = 0; i < 1000; i++)
      try {
        chaos.apply();
      } catch (ResponseStatusException e) {
        assertEquals(500, e.getStatusCode().value());
        failures++;
      }
    assertEquals((long) failures, chaos.stats().get("injectedErrors"));
    assertEquals(1000L, chaos.stats().get("requests"));
    chaos.set(fault);
    int repeated = 0;
    for (int i = 0; i < 1000; i++)
      try {
        chaos.apply();
      } catch (ResponseStatusException e) {
        repeated++;
      }
    assertEquals(failures, repeated);
    chaos.set(new Chaos.Fault(0, 0, 0, ""));
    assertEquals(0L, chaos.stats().get("injectedErrors"));
  }

  @Test
  void invalidFaultsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Chaos.Fault(-1, 0, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> new Chaos.Fault(0, Double.NaN, 0, ""));
    assertThrows(IllegalArgumentException.class, () -> new Chaos.Fault(0, 0, 0, "anything"));
  }

  @Test
  void unavailableDependencyStillMakesAnActualHttpCall() throws Exception {
    var calls = new AtomicInteger();
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/pay",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    http.createContext(
        "/reserve",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    http.start();
    try {
      String url = "http://127.0.0.1:" + http.getAddress().getPort();
      var settings = new ShopSettings("checkout", "local-chaos-token", url, url, url, url, url);
      var chaos = new Chaos();
      chaos.set(new Chaos.Fault(0, 0, 0, "inventory"));
      var controller = new ShopController(settings, chaos, mock(JdbcTemplate.class));
      var failure = assertThrows(ResponseStatusException.class, controller::checkout);
      assertEquals(503, failure.getStatusCode().value());
      assertEquals(1, calls.get());
    } finally {
      http.stop(0);
    }
  }

  @Test
  void paymentAndInventoryFaultsUseTheirOwnHttpDestinations() throws Exception {
    var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          calls.add(path);
          exchange.sendResponseHeaders(path.startsWith("/failed-") ? 503 : 200, -1);
          exchange.close();
        });
    http.start();
    try {
      String url = "http://127.0.0.1:" + http.getAddress().getPort();
      var settings =
          new ShopSettings(
              "checkout",
              "local-chaos-token",
              url,
              url,
              url,
              url + "/failed-payment",
              url + "/failed-inventory");
      var chaos = new Chaos();
      var controller = new ShopController(settings, chaos, mock(JdbcTemplate.class));
      chaos.set(new Chaos.Fault(0, 0, 0, "payment"));
      assertThrows(ResponseStatusException.class, controller::checkout);
      assertEquals(java.util.List.of("/failed-payment/pay"), calls);
      calls.clear();
      chaos.set(new Chaos.Fault(0, 0, 0, "inventory"));
      assertThrows(ResponseStatusException.class, controller::checkout);
      assertEquals(java.util.List.of("/pay", "/failed-inventory/reserve"), calls);
    } finally {
      http.stop(0);
    }
  }

  @Test
  void databaseDelayRunsThroughJdbc() {
    var settings =
        new ShopSettings(
            "payment",
            "local-chaos-token",
            "http://checkout",
            "http://payment",
            "http://inventory",
            "http://payment-unavailable:1",
            "http://inventory-unavailable:1");
    var chaos = new Chaos();
    chaos.set(new Chaos.Fault(0, 0, 600, ""));
    var jdbc = mock(JdbcTemplate.class);
    new ShopController(settings, chaos, jdbc).pay();
    verify(jdbc).queryForList("SELECT pg_sleep(?)", .6);
  }
}
