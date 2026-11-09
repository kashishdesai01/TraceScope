package dev.tracescope;

import dev.tracescope.model.OtlpMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.trace.v1.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import org.springframework.web.bind.annotation.*;

@RestController
public class IngestController {
  private final Pipeline pipeline;
  private final MeterRegistry meters;
  private final Semaphore requests = new Semaphore(4);

  public IngestController(Pipeline pipeline, MeterRegistry meters) {
    this.pipeline = pipeline;
    this.meters = meters;
  }

  @PostMapping("/v1/traces")
  void ingest(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!requests.tryAcquire()) {
      failure(response, 503, "Too many concurrent ingest requests");
      return;
    }
    try {
      String type = request.getContentType();
      if (type == null
          || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-protobuf")) {
        failure(response, 415, "Use application/x-protobuf");
        return;
      }
      String encoding = request.getHeader("Content-Encoding");
      if (encoding != null
          && !encoding.equalsIgnoreCase("gzip")
          && !encoding.equalsIgnoreCase("identity")) {
        failure(response, 415, "Unsupported content encoding");
        return;
      }
      byte[] wire = request.getInputStream().readNBytes(4 * 1024 * 1024 + 1);
      if (wire.length > 4 * 1024 * 1024) {
        failure(response, 413, "Compressed body exceeds 4 MiB");
        return;
      }
      byte[] body = wire;
      if ("gzip".equalsIgnoreCase(encoding)) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(wire))) {
          body = gzip.readNBytes(16 * 1024 * 1024 + 1);
        } catch (IOException badGzip) {
          failure(response, 400, "Invalid gzip stream");
          return;
        }
      }
      if (body.length > 16 * 1024 * 1024) {
        failure(response, 413, "Expanded body exceeds 16 MiB");
        return;
      }
      ExportTraceServiceRequest proto;
      try {
        proto = ExportTraceServiceRequest.parseFrom(body);
      } catch (IOException badProto) {
        failure(response, 400, "Invalid OTLP protobuf");
        return;
      }
      var mapped = OtlpMapper.map(proto);
      long now = Instant.now().getEpochSecond() * 1_000_000_000L;
      var valid =
          mapped.spans().stream()
              .filter(
                  s ->
                      s.startNs() >= now - 7L * 86400 * 1_000_000_000L
                          && s.endNs() <= now + 300L * 1_000_000_000L)
              .toList();
      int rejected = mapped.rejected() + mapped.spans().size() - valid.size();
      meters
          .counter("ingest_spans_received_total")
          .increment(mapped.spans().size() + mapped.rejected());
      try {
        pipeline.accept(valid);
      } catch (Pipeline.Full full) {
        failure(response, 503, full.getMessage());
        return;
      }
      var result = ExportTraceServiceResponse.newBuilder();
      if (rejected > 0) {
        result.setPartialSuccess(
            ExportTracePartialSuccess.newBuilder()
                .setRejectedSpans(rejected)
                .setErrorMessage(
                    "Invalid identity, timestamp, span kind, or outside the seven-day retention/five-minute future window"));
        meters.counter("ingest_spans_rejected_total", "reason", "validation").increment(rejected);
      }
      response.setStatus(200);
      response.setContentType("application/x-protobuf");
      response.getOutputStream().write(result.build().toByteArray());
      meters.counter("ingest_requests_total", "status", "200").increment();
    } finally {
      requests.release();
    }
  }

  private void failure(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/x-protobuf");
    if (status == 503) response.setHeader("Retry-After", "2");
    // google.rpc.Status has field 2 (length-delimited UTF-8 message); code and details are optional
    // in OTLP/HTTP.
    var bytes = new ByteArrayOutputStream();
    var coded = com.google.protobuf.CodedOutputStream.newInstance(bytes);
    coded.writeString(2, message);
    coded.flush();
    response.getOutputStream().write(bytes.toByteArray());
    meters.counter("ingest_requests_total", "status", Integer.toString(status)).increment();
  }
}
