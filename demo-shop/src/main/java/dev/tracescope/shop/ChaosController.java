package dev.tracescope.shop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/chaos")
public class ChaosController {
  private final Chaos chaos;
  private final ShopSettings settings;

  public ChaosController(Chaos chaos, ShopSettings settings) {
    this.chaos = chaos;
    this.settings = settings;
  }

  private void auth(String token) {
    if (token == null
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            settings.chaosToken().getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  @PostMapping
  Object set(
      @RequestHeader(value = "X-Chaos-Token", required = false) String token,
      @RequestBody Chaos.Fault fault) {
    auth(token);
    chaos.set(fault);
    return chaos.stats();
  }

  @GetMapping("/stats")
  Object stats(@RequestHeader(value = "X-Chaos-Token", required = false) String token) {
    auth(token);
    return chaos.stats();
  }

  @DeleteMapping
  Object reset(@RequestHeader(value = "X-Chaos-Token", required = false) String token) {
    auth(token);
    chaos.set(new Chaos.Fault(0, 0, 0, ""));
    return chaos.stats();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
