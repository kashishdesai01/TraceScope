package dev.tracescope.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.*;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OtlpMapperTest {
  static KeyValue attr(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }

  static io.opentelemetry.proto.trace.v1.Span.Builder valid() {
    return io.opentelemetry.proto.trace.v1.Span.newBuilder()
        .setTraceId(
            ByteString.copyFrom(HexFormat.of().parseHex("0123456789abcdef0123456789abcdef")))
        .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex("0123456789abcdef")))
        .setName("POST /checkout")
        .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER)
        .setStartTimeUnixNano(100)
        .setEndTimeUnixNano(200);
  }

  static ExportTraceServiceRequest request(io.opentelemetry.proto.trace.v1.Span... spans) {
    return ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(
            ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(attr("service.name", "checkout")))
                .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(List.of(spans))))
        .build();
  }

  @Test
  void mapsResourceIdsStatusStableDatabaseAndExceptionEvents() {
    var proto =
        valid()
            .addAttributes(attr("http.response.status_code", "503"))
            .addAttributes(attr("db.system.name", "postgresql"))
            .addAttributes(attr("db.namespace", "payments"))
            .addAttributes(attr("authorization", "hidden"))
            .addEvents(
                io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                    .setName("exception")
                    .addAttributes(attr("exception.type", "IllegalStateException")))
            .build();
    var result = OtlpMapper.map(request(proto));
    assertEquals(0, result.rejected());
    var s = result.spans().getFirst();
    assertEquals("checkout", s.service());
    assertEquals("0123456789abcdef0123456789abcdef", s.traceId());
    assertEquals("", s.parentSpanId());
    assertEquals(100, s.durationNs());
    assertTrue(s.error());
    assertEquals("postgresql:payments", s.peer());
    assertTrue(s.database());
    assertFalse(s.attributes().containsKey("authorization"));
    assertEquals("IllegalStateException", s.attributes().get("exception.type"));
  }

  @Test
  void mapsLegacyNamesAndCapsAttributes() {
    var proto =
        valid()
            .addAttributes(attr("http.status_code", "500"))
            .addAttributes(attr("db.system", "postgresql"))
            .addAttributes(attr("db.name", "inventory"));
    for (int i = 0; i < 100; i++) proto.addAttributes(attr("key" + i, "x".repeat(2000)));
    var s = OtlpMapper.map(request(proto.build())).spans().getFirst();
    assertEquals("postgresql:inventory", s.peer());
    assertEquals(64, s.attributes().size());
    assertEquals(1024, s.attributes().get("key0").length());
  }

  @Test
  void invalidHttpCodesAreUnknownWithoutChangingExplicitSpanError() {
    for (String code : List.of("-1", "99", "600", "65536", "2147483648", "garbage")) {
      var proto = valid().addAttributes(attr("http.response.status_code", code)).build();
      var mapped = OtlpMapper.map(request(proto));
      assertEquals(0, mapped.rejected());
      assertEquals(0, mapped.spans().getFirst().httpStatus(), code);
      assertFalse(mapped.spans().getFirst().error(), code);
      var error =
          proto.toBuilder()
              .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR))
              .build();
      assertTrue(OtlpMapper.map(request(error)).spans().getFirst().error(), code);
    }
    for (int code : List.of(100, 200, 404, 500, 599)) {
      var mapped =
          OtlpMapper.map(
                  request(
                      valid().addAttributes(attr("http.response.status_code", "" + code)).build()))
              .spans()
              .getFirst();
      assertEquals(code, mapped.httpStatus());
      assertEquals(code >= 500, mapped.error());
    }
  }

  @Test
  void rejectsInvalidIdentitiesTimesAndKindsIndividually() {
    var result =
        OtlpMapper.map(
            request(
                valid().build(),
                valid().setTraceId(ByteString.copyFrom(new byte[16])).build(),
                valid().setSpanId(ByteString.copyFrom(new byte[4])).build(),
                valid().setEndTimeUnixNano(99).build(),
                valid().setParentSpanId(ByteString.copyFrom(new byte[3])).build(),
                valid().setKindValue(99).build()));
    assertEquals(1, result.spans().size());
    assertEquals(5, result.rejected());
  }
}
