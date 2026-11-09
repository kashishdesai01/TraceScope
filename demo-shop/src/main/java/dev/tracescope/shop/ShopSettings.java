package dev.tracescope.shop;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shop")
public record ShopSettings(
    String role,
    String chaosToken,
    String checkoutUrl,
    String paymentUrl,
    String inventoryUrl,
    String paymentUnavailableUrl,
    String inventoryUnavailableUrl) {
  public ShopSettings {
    if (!java.util.Set.of("gateway", "checkout", "payment", "inventory").contains(role))
      throw new IllegalArgumentException("Unknown service role");
    if (chaosToken == null || chaosToken.length() < 12)
      throw new IllegalArgumentException("Chaos token too short");
  }
}
