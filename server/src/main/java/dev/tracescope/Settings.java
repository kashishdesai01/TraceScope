package dev.tracescope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Settings.Values.class)
public class Settings {
  @ConfigurationProperties("tracescope")
  public record Values(
      String clickhouseUrl,
      String clickhouseUser,
      String clickhousePassword,
      String mcpToken,
      double samplingRate,
      int maxSpans,
      long maxBytes,
      int maxIdentities,
      int maxKeys,
      int maxFrames,
      int maxTraceSpans,
      long idleMs,
      long flushMs) {
    public Values {
      if (samplingRate < 0
          || samplingRate > 1
          || maxSpans < 1
          || maxBytes < 1024
          || maxIdentities < 1
          || maxKeys < 1
          || maxFrames < 1
          || maxTraceSpans < 1
          || idleMs < 1
          || flushMs < 1) throw new IllegalArgumentException("Invalid pipeline limits");
      if (mcpToken == null || mcpToken.length() < 12)
        throw new IllegalArgumentException("MCP token must contain at least 12 characters");
    }
  }
}
