package dev.tracescope;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AlertController {
  private final AlertService alerts;

  public AlertController(AlertService alerts) {
    this.alerts = alerts;
  }

  @GetMapping("/alert-rules")
  Object rules() {
    return alerts.rules();
  }

  @PostMapping("/alert-rules")
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@RequestBody AlertService.Rule rule) {
    return alerts.save(null, rule);
  }

  @PutMapping("/alert-rules/{id}")
  Object update(@PathVariable UUID id, @RequestBody AlertService.Rule rule) {
    return alerts.save(id, rule);
  }

  @DeleteMapping("/alert-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    alerts.delete(id);
  }

  @PostMapping("/alert-rules/preview")
  Object preview(@RequestBody AlertService.Rule rule) {
    return alerts.measure(rule, Instant.now());
  }

  @GetMapping("/alert-events")
  Object events(@RequestParam(required = false) String state) {
    return alerts.events(state);
  }
}
