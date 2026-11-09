package dev.tracescope;

import dev.tracescope.model.AlertTransition;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
  public record Rule(
      UUID id,
      String name,
      String service,
      String operation,
      String kind,
      Double percentile,
      double threshold,
      int windowSec,
      int forSec,
      int minRequests,
      double resolveRatio,
      boolean enabled) {
    public Rule {
      if (name == null
          || name.isBlank()
          || name.length() > 128
          || service == null
          || service.isBlank()
          || service.length() > 128
          || operation != null && operation.length() > 256)
        throw new IllegalArgumentException(
            "Name and service are required; name/service <=128 and operation <=256 characters");
      if (!Set.of("latency", "error_rate").contains(kind == null ? "" : kind))
        throw new IllegalArgumentException("kind must be latency or error_rate");
      if (!Double.isFinite(threshold)
          || threshold <= 0
          || threshold > 86400000
          || kind.equals("error_rate") && threshold > 1)
        throw new IllegalArgumentException("Invalid threshold");
      if (kind.equals("latency")
          && (percentile == null || !Set.of(.5, .95, .99).contains(percentile)))
        throw new IllegalArgumentException("Latency percentile must be 0.5, 0.95 or 0.99");
      if (windowSec < 60
          || windowSec > 3600
          || windowSec % 60 != 0
          || forSec < 0
          || forSec > 3600
          || minRequests < 1
          || minRequests > 1000000
          || !Double.isFinite(resolveRatio)
          || resolveRatio <= 0
          || resolveRatio >= 1)
        throw new IllegalArgumentException(
            "Invalid alert window, persistence duration, traffic minimum or recovery ratio");
      if (operation != null && operation.isBlank()) operation = null;
    }
  }

  public record Measurement(
      QueryService.Window window, Double value, long requests, String status) {}

  private final JdbcTemplate jdbc;
  private final QueryService queries;
  private final TransactionTemplate transactions;

  public AlertService(JdbcTemplate jdbc, QueryService queries, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.queries = queries;
    transactions = new TransactionTemplate(manager);
  }

  private final RowMapper<Rule> mapper =
      (rs, row) ->
          new Rule(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              rs.getString("service"),
              rs.getString("operation"),
              rs.getString("kind"),
              (Double) rs.getObject("percentile"),
              rs.getDouble("threshold"),
              rs.getInt("window_sec"),
              rs.getInt("for_sec"),
              rs.getInt("min_requests"),
              rs.getDouble("resolve_ratio"),
              rs.getBoolean("enabled"));

  public List<Rule> rules() {
    return jdbc.query(
        "SELECT * FROM alert_rules WHERE NOT deleted ORDER BY created_at,id LIMIT 100", mapper);
  }

  @org.springframework.transaction.annotation.Transactional
  public Rule save(UUID id, Rule input) {
    Rule previous = null;
    if (id == null) {
      lockRuleCreation();
      if (jdbc.queryForObject("SELECT count(*) FROM alert_rules WHERE NOT deleted", Integer.class)
          >= 100)
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "At most 100 active rules are supported");
      id = UUID.randomUUID();
    } else {
      var existing =
          jdbc.query("SELECT * FROM alert_rules WHERE id=? AND NOT deleted FOR UPDATE", mapper, id);
      if (existing.isEmpty())
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
      previous = existing.getFirst();
    }
    jdbc.update(
        "INSERT INTO alert_rules(id,name,service,operation,kind,percentile,threshold,window_sec,for_sec,min_requests,resolve_ratio,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,service=EXCLUDED.service,operation=EXCLUDED.operation,kind=EXCLUDED.kind,percentile=EXCLUDED.percentile,threshold=EXCLUDED.threshold,window_sec=EXCLUDED.window_sec,for_sec=EXCLUDED.for_sec,min_requests=EXCLUDED.min_requests,resolve_ratio=EXCLUDED.resolve_ratio,enabled=EXCLUDED.enabled",
        id,
        input.name,
        input.service,
        input.operation,
        input.kind,
        input.percentile,
        input.threshold,
        input.windowSec,
        input.forSec,
        input.minRequests,
        input.resolveRatio,
        input.enabled);
    if (!input.enabled) resolveDisabled(id);
    else if (previous != null && !condition(previous).equals(condition(input)))
      resolveOpen(id, "rule_changed");
    return jdbc.queryForObject("SELECT * FROM alert_rules WHERE id=?", mapper, id);
  }

  @org.springframework.transaction.annotation.Transactional
  public void delete(UUID id) {
    if (jdbc.update(
            "UPDATE alert_rules SET enabled=false,deleted=true WHERE id=? AND NOT deleted", id)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
    resolveDisabled(id);
  }

  private static List<Object> condition(Rule rule) {
    return Arrays.asList(
        rule.service,
        rule.operation,
        rule.kind,
        rule.percentile,
        rule.threshold,
        rule.windowSec,
        rule.forSec,
        rule.minRequests,
        rule.resolveRatio);
  }

  private void resolveDisabled(UUID id) {
    resolveOpen(id, "rule_disabled");
  }

  private void resolveOpen(UUID id, String reason) {
    jdbc.update(
        "UPDATE alert_events SET state='resolved',resolved_at=now(),recovery_since=NULL,resolution=? WHERE rule_id=? AND state<>'resolved'",
        reason,
        id);
  }

  public Measurement measure(Rule rule, Instant now) {
    var window = QueryService.window(now.minusSeconds(rule.windowSec), now);
    List<Map<String, Object>> rows =
        rule.operation == null
            ? queries.services(window).data()
            : queries.operations(window, rule.service).data();
    var metric =
        rows.stream()
            .filter(
                row ->
                    rule.service.equals(row.get("service"))
                        && (rule.operation == null || rule.operation.equals(row.get("operation"))))
            .findFirst();
    if (metric.isEmpty()) return new Measurement(window, null, 0, "no_data");
    var row = metric.get();
    long count = QueryService.number(row.get("requests"));
    if (count < rule.minRequests)
      return new Measurement(window, null, count, "insufficient_traffic");
    String key = rule.kind.equals("latency") ? "p" + (int) (rule.percentile * 100) : "errorRate";
    double value = ((Number) row.get(key)).doubleValue();
    if (rule.kind.equals("latency") && !"finite".equals(row.get(key + "Range"))) {
      boolean safeBound =
          "overflow".equals(row.get(key + "Range"))
              ? value > rule.threshold
              : value < rule.threshold * rule.resolveRatio;
      return new Measurement(
          window,
          safeBound ? value : null,
          count,
          safeBound ? "bounded_latency" : "censored_latency");
    }
    return new Measurement(window, value, count, "ok");
  }

  @Scheduled(fixedDelay = 15000, initialDelay = 5000)
  public void evaluate() {
    try {
      seed();
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(AlertService.class).warn("Alert discovery unavailable");
    }
    for (var rule : rules())
      if (rule.enabled) {
        Instant now = Instant.now();
        Measurement measurement;
        try {
          measurement = measure(rule, now);
        } catch (RuntimeException e) {
          measurement =
              new Measurement(
                  QueryService.window(now.minusSeconds(rule.windowSec), now),
                  null,
                  0,
                  "storage_unavailable");
        }
        Measurement value = measurement;
        try {
          transactions.executeWithoutResult(status -> evaluateOne(rule, value, now));
        } catch (RuntimeException e) {
          org.slf4j.LoggerFactory.getLogger(AlertService.class)
              .warn("Alert state persistence failed for {}", rule.id);
        }
      }
  }

  private static Instant instant(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  private void evaluateOne(Rule rule, Measurement measurement, Instant now) {
    var current =
        jdbc.query(
            "SELECT * FROM alert_rules WHERE id=? AND NOT deleted FOR UPDATE", mapper, rule.id);
    if (current.isEmpty() || !current.getFirst().enabled || !current.getFirst().equals(rule))
      return;
    var events =
        jdbc.queryForList(
            "SELECT * FROM alert_events WHERE rule_id=? AND state<>'resolved' FOR UPDATE", rule.id);
    Map<String, Object> previous = events.isEmpty() ? null : events.getFirst();
    AlertTransition.Event before =
        previous == null
            ? null
            : new AlertTransition.Event(
                previous.get("state").toString(),
                instant(previous, "started_at"),
                instant(previous, "firing_at"),
                instant(previous, "recovery_since"),
                instant(previous, "resolved_at"),
                (Double) previous.get("current_value"),
                (Double) previous.get("peak_value"),
                (Boolean) previous.get("no_data"),
                (String) previous.get("resolution"));
    var next =
        AlertTransition.evaluate(
            new AlertTransition.Rule(rule.threshold, rule.resolveRatio, rule.forSec),
            before,
            measurement.value,
            now);
    if (next == null) return;
    UUID id = previous == null ? UUID.randomUUID() : (UUID) previous.get("id");
    jdbc.update(
        "INSERT INTO alert_events(id,rule_id,state,started_at,firing_at,recovery_since,resolved_at,current_value,peak_value,no_data,resolution,evaluated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=EXCLUDED.state,firing_at=EXCLUDED.firing_at,recovery_since=EXCLUDED.recovery_since,resolved_at=EXCLUDED.resolved_at,current_value=EXCLUDED.current_value,peak_value=EXCLUDED.peak_value,no_data=EXCLUDED.no_data,resolution=EXCLUDED.resolution,evaluated_at=EXCLUDED.evaluated_at",
        id,
        rule.id,
        next.state(),
        Timestamp.from(next.startedAt()),
        timestamp(next.firingAt()),
        timestamp(next.recoverySince()),
        timestamp(next.resolvedAt()),
        next.currentValue(),
        next.peakValue(),
        next.noData(),
        next.resolution(),
        Timestamp.from(now));
    if (next.state().equals("firing")
        && (before == null
            || !before.state().equals("firing")
            || "unavailable".equals(previous.get("exemplar_status")))) {
      List<String> exemplars;
      try {
        exemplars =
            queries.exemplars(
                measurement.window, rule.service, rule.operation, rule.kind.equals("error_rate"));
      } catch (RuntimeException e) {
        exemplars = List.of();
      }
      List<String> ids = exemplars;
      jdbc.update(
          connection -> {
            PreparedStatement statement =
                connection.prepareStatement(
                    "UPDATE alert_events SET exemplar_trace_ids=?,exemplar_status=? WHERE id=?");
            statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
            statement.setString(2, ids.isEmpty() ? "unavailable" : "available");
            statement.setObject(3, id);
            return statement;
          });
    }
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  public List<Map<String, Object>> events(String state) {
    if (state != null && !Set.of("pending", "firing", "resolved", "open").contains(state))
      throw new IllegalArgumentException("Invalid alert state");
    String where =
        state == null
            ? ""
            : state.equals("open") ? " WHERE e.state<>'resolved'" : " WHERE e.state=?";
    var rows =
        jdbc.queryForList(
            "SELECT e.*,r.name AS rule_name,r.service,r.kind,r.threshold FROM alert_events e JOIN alert_rules r ON r.id=e.rule_id"
                + where
                + " ORDER BY e.started_at DESC LIMIT 100",
            state == null || state.equals("open") ? new Object[] {} : new Object[] {state});
    for (var row : rows)
      for (var entry : new ArrayList<>(row.entrySet())) {
        if (entry.getValue() instanceof Timestamp t)
          row.put(entry.getKey(), t.toInstant().toString());
        if (entry.getValue() instanceof java.sql.Array a)
          try {
            row.put(entry.getKey(), Arrays.asList((Object[]) a.getArray()));
            a.free();
          } catch (SQLException e) {
            throw new IllegalStateException("Cannot read exemplars", e);
          }
      }
    return rows;
  }

  private void seed() {
    var services = queries.services(QueryService.window(null, null)).data();
    for (var service : services) {
      String name = service.get("service").toString();
      seedRule(
          new Rule(
              stable("error:" + name),
              name + " error rate > 5%",
              name,
              null,
              "error_rate",
              null,
              .05,
              120,
              60,
              20,
              .8,
              true));
    }
    seedRule(
        new Rule(
            stable("checkout-latency"),
            "checkout p95 > 500ms",
            "checkout",
            null,
            "latency",
            .95,
            500,
            120,
            60,
            20,
            .8,
            true));
  }

  private static UUID stable(String name) {
    return UUID.nameUUIDFromBytes(("tracescope:" + name).getBytes(StandardCharsets.UTF_8));
  }

  private void lockRuleCreation() {
    // Shared by API and discovery. Keep this statement separate from the count:
    // after waiting, READ COMMITTED must obtain a fresh snapshot of committed rules.
    jdbc.execute("SELECT pg_advisory_xact_lock(1953653091, 1919706476)");
  }

  private void seedRule(Rule rule) {
    transactions.executeWithoutResult(
        status -> {
          lockRuleCreation();
          jdbc.update(
              "INSERT INTO alert_rules(id,name,service,operation,kind,percentile,threshold,window_sec,for_sec,min_requests,resolve_ratio,enabled) SELECT ?,?,?,?,?,?,?,?,?,?,?,? WHERE (SELECT count(*) FROM alert_rules WHERE NOT deleted)<100 ON CONFLICT(id) DO NOTHING",
              rule.id,
              rule.name,
              rule.service,
              rule.operation,
              rule.kind,
              rule.percentile,
              rule.threshold,
              rule.windowSec,
              rule.forSec,
              rule.minRequests,
              rule.resolveRatio,
              rule.enabled);
        });
  }
}
