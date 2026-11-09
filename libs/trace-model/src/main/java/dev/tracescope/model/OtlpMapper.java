package dev.tracescope.model;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.*;
import java.util.regex.Pattern;

public final class OtlpMapper {
  private static final Pattern SECRET =
      Pattern.compile(
          "authorization|cookie|password|secret|token|api[-_]?key", Pattern.CASE_INSENSITIVE);

  public record Result(List<Span> spans, int rejected) {}

  public static boolean sensitive(String key) {
    return SECRET.matcher(key).find();
  }

  public static Map<String, String> attributes(List<KeyValue> values) {
    Map<String, String> out = new LinkedHashMap<>();
    for (var kv : values) {
      if (out.size() == 64) break;
      if (!sensitive(kv.getKey())) out.put(cut(kv.getKey(), 128), cut(value(kv.getValue()), 1024));
    }
    return out;
  }

  private static String value(AnyValue v) {
    return switch (v.getValueCase()) {
      case STRING_VALUE -> v.getStringValue();
      case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
      case INT_VALUE -> Long.toString(v.getIntValue());
      case DOUBLE_VALUE -> Double.toString(v.getDoubleValue());
      default -> "[structured value omitted]";
    };
  }

  private static String cut(String v, int max) {
    return v.length() <= max ? v : v.substring(0, max);
  }

  private static String first(Map<String, String> a, String... keys) {
    for (String k : keys) if (a.containsKey(k)) return a.get(k);
    return "";
  }

  private static boolean valid(com.google.protobuf.ByteString b, int n) {
    return b.size() == n
        && b.toByteArray().length > 0
        && !b.equals(com.google.protobuf.ByteString.copyFrom(new byte[n]));
  }

  public static Result map(ExportTraceServiceRequest req) {
    List<Span> out = new ArrayList<>();
    int rejected = 0;
    for (var rs : req.getResourceSpansList()) {
      String service =
          cut(
              attributes(rs.getResource().getAttributesList())
                  .getOrDefault("service.name", "unknown_service"),
              128);
      for (var scope : rs.getScopeSpansList())
        for (var s : scope.getSpansList()) {
          if (!valid(s.getTraceId(), 16)
              || !valid(s.getSpanId(), 8)
              || (!s.getParentSpanId().isEmpty() && !valid(s.getParentSpanId(), 8))
              || s.getStartTimeUnixNano() <= 0
              || s.getEndTimeUnixNano() < s.getStartTimeUnixNano()
              || s.getKindValue() < 0
              || s.getKindValue() > 5) {
            rejected++;
            continue;
          }
          var a = attributes(s.getAttributesList());
          // Exception events are not top-level attributes in OTLP.
          for (var event : s.getEventsList())
            if (event.getName().equals("exception")) {
              var eventAttrs = attributes(event.getAttributesList());
              for (String key : List.of("exception.type", "exception.message"))
                if (eventAttrs.containsKey(key) && a.size() < 64)
                  a.putIfAbsent(key, eventAttrs.get(key));
            }
          int status = 0;
          try {
            status = Integer.parseInt(first(a, "http.response.status_code", "http.status_code"));
          } catch (NumberFormatException ignored) {
          }
          // Ignore malformed semantic attributes; never let one poison a storage frame.
          if (status < 100 || status > 599) status = 0;
          String db = first(a, "db.system.name", "db.system"),
              namespace = first(a, "db.namespace", "db.name");
          String peer =
              db.isEmpty() ? first(a, "server.address", "net.peer.name") : db + ":" + namespace;
          Span.Kind kind =
              switch (s.getKindValue()) {
                case 2 -> Span.Kind.SERVER;
                case 3 -> Span.Kind.CLIENT;
                case 4 -> Span.Kind.PRODUCER;
                case 5 -> Span.Kind.CONSUMER;
                default -> Span.Kind.INTERNAL;
              };
          out.add(
              new Span(
                  HexFormat.of().formatHex(s.getTraceId().toByteArray()),
                  HexFormat.of().formatHex(s.getSpanId().toByteArray()),
                  HexFormat.of().formatHex(s.getParentSpanId().toByteArray()),
                  service,
                  cut(s.getName(), 256),
                  kind,
                  s.getStartTimeUnixNano(),
                  s.getEndTimeUnixNano() - s.getStartTimeUnixNano(),
                  s.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR || status >= 500,
                  status,
                  first(a, "http.route"),
                  peer,
                  !db.isEmpty(),
                  a));
        }
    }
    return new Result(List.copyOf(out), rejected);
  }
}
