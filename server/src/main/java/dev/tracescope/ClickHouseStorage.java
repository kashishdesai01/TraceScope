package dev.tracescope;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseStorage implements Storage {
  private final Client client;
  private final ObjectMapper json;
  private final Settings.Values settings;

  public ClickHouseStorage(Settings.Values settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
    client =
        new Client.Builder()
            .addEndpoint(settings.clickhouseUrl())
            .setUsername(settings.clickhouseUser())
            .setPassword(settings.clickhousePassword())
            .setDefaultDatabase("tracescope")
            .setConnectTimeout(2000)
            .setSocketTimeout(5000)
            .setMaxRetries(0)
            .setMaxConnections(8)
            .build();
  }

  @PostConstruct
  void initialize() throws Exception {
    String schema =
        new ClassPathResource("clickhouse/schema.sql").getContentAsString(StandardCharsets.UTF_8);
    for (String statement : schema.split(";"))
      if (!statement.isBlank())
        try (var ignored = client.execute(statement).get(10, TimeUnit.SECONDS)) {}
  }

  @Override
  public void insert(String table, List<Map<String, Object>> rows, String token) {
    if (rows.isEmpty()) return;
    if (!Set.of("spans", "traces", "trace_index", "red_minute", "edges_minute").contains(table))
      throw new IllegalArgumentException("Invalid table");
    try {
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      for (var row : rows) {
        body.write(json.writeValueAsBytes(row));
        body.write('\n');
      }
      var options =
          new InsertSettings()
              .setDeduplicationToken(token)
              .serverSetting("insert_deduplicate", "1")
              .serverSetting("date_time_input_format", "best_effort");
      try (var ignored =
          client
              .insert(
                  table,
                  new ByteArrayInputStream(body.toByteArray()),
                  ClickHouseFormat.JSONEachRow,
                  options)
              .get(10, TimeUnit.SECONDS)) {}
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("ClickHouse insert failed for " + table, e);
    }
  }

  @Override
  public List<Map<String, Object>> query(String sql, Object... parameters) {
    Properties properties = new Properties();
    properties.setProperty("user", settings.clickhouseUser());
    properties.setProperty("password", settings.clickhousePassword());
    properties.setProperty("socket_timeout", "10000");
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:clickhouse:" + settings.clickhouseUrl() + "/tracescope", properties);
        PreparedStatement statement =
            connection.prepareStatement(sql + " SETTINGS max_execution_time=10")) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      try (ResultSet rs = statement.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Array array) {
              value = array.getArray();
              array.free();
            }
            row.put(rs.getMetaData().getColumnLabel(i), value);
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("ClickHouse query failed", e);
    }
  }

  @PreDestroy
  void close() {
    client.close();
  }
}
