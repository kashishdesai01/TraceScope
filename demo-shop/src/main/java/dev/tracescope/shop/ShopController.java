package dev.tracescope.shop;

import jakarta.annotation.PostConstruct;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ShopController {
  private final ShopSettings settings;
  private final Chaos chaos;
  private final JdbcTemplate jdbc;
  private final RestClient client;

  public ShopController(ShopSettings settings, Chaos chaos, JdbcTemplate jdbc) {
    this.settings = settings;
    this.chaos = chaos;
    this.jdbc = jdbc;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(12));
    client = RestClient.builder().requestFactory(factory).build();
  }

  @PostConstruct
  void initialize() {
    if (settings.role().equals("payment"))
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS payments(id UUID PRIMARY KEY,amount_cents INT NOT NULL,created_at TIMESTAMPTZ DEFAULT now())");
    if (settings.role().equals("inventory")) {
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS inventory(sku TEXT PRIMARY KEY,stock BIGINT NOT NULL)");
      jdbc.update(
          "INSERT INTO inventory(sku,stock) VALUES ('demo-item',1000000000) ON CONFLICT DO NOTHING");
    }
  }

  private void role(String expected) {
    if (!settings.role().equals(expected)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private void downstream(String name, String url, String path) {
    boolean unavailable = name.equals(chaos.get().downstreamDown());
    String destination =
        unavailable
            ? (name.equals("payment")
                ? settings.paymentUnavailableUrl()
                : settings.inventoryUnavailableUrl())
            : url;
    try {
      client.post().uri(destination + path).retrieve().toBodilessEntity();
    } catch (RestClientException failure) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Downstream " + name + " unavailable");
    }
  }

  private void dbDelay() {
    int ms = chaos.get().dbSlowMs();
    if (ms > 0) jdbc.queryForList("SELECT pg_sleep(?)", ms / 1000.0);
  }

  @PostMapping("/api/checkout")
  Object gatewayCheckout() {
    role("gateway");
    chaos.apply();
    downstream("checkout", settings.checkoutUrl(), "/checkout");
    return Map.of("status", "confirmed");
  }

  @GetMapping("/api/products")
  Object gatewayProducts() {
    role("gateway");
    chaos.apply();
    try {
      return client.get().uri(settings.checkoutUrl() + "/products").retrieve().body(Object.class);
    } catch (RestClientException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog unavailable");
    }
  }

  @GetMapping("/products")
  Object products() {
    role("checkout");
    chaos.apply();
    return List.of(Map.of("sku", "demo-item", "priceCents", 1200));
  }

  @PostMapping("/checkout")
  Object checkout() {
    role("checkout");
    chaos.apply();
    downstream("payment", settings.paymentUrl(), "/pay");
    downstream("inventory", settings.inventoryUrl(), "/reserve");
    return Map.of("status", "confirmed");
  }

  @PostMapping("/pay")
  Object pay() {
    role("payment");
    chaos.apply();
    dbDelay();
    jdbc.update("INSERT INTO payments(id,amount_cents) VALUES (?,?)", UUID.randomUUID(), 1200);
    return Map.of("paid", true);
  }

  @PostMapping("/reserve")
  Object reserve() {
    role("inventory");
    chaos.apply();
    dbDelay();
    int changed =
        jdbc.update("UPDATE inventory SET stock=stock-1 WHERE sku='demo-item' AND stock>0");
    if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Out of stock");
    return Map.of("reserved", true);
  }
}
