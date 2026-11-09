package dev.tracescope;

import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class QueryController {
  private final QueryService queries;
  private final Pipeline pipeline;

  public QueryController(QueryService queries, Pipeline pipeline) {
    this.queries = queries;
    this.pipeline = pipeline;
  }

  @GetMapping("/services")
  Object services(
      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
    return queries.services(QueryService.window(from, to));
  }

  @GetMapping("/services/{service}/operations")
  Object operations(
      @PathVariable String service,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to) {
    return queries.operations(QueryService.window(from, to), service);
  }

  @GetMapping("/services/{service}/series")
  Object series(
      @PathVariable String service,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "60") int step) {
    return queries.series(QueryService.window(from, to), service, step);
  }

  @GetMapping("/service-map")
  Object map(
      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
    return queries.serviceMap(QueryService.window(from, to));
  }

  @GetMapping("/traces")
  Object traces(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String service,
      @RequestParam(required = false) Double minMs,
      @RequestParam(defaultValue = "false") boolean errorsOnly,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor) {
    return queries.traces(QueryService.window(from, to), service, minMs, errorsOnly, limit, cursor);
  }

  @GetMapping("/traces/{id}")
  Object trace(@PathVariable String id) {
    return queries.trace(id);
  }

  @GetMapping("/traces/{id}/critical-path")
  Object critical(@PathVariable String id) {
    return dev.tracescope.model.CriticalPath.compute(queries.spans(id));
  }

  @GetMapping("/pipeline")
  Object pipeline() {
    return pipeline.stats();
  }
}
