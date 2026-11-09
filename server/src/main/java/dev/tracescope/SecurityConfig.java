package dev.tracescope;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, Settings.Values settings) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                if (request.getRequestURI().equals("/mcp")
                    || request.getRequestURI().startsWith("/mcp/")) {
                  String expected = "Bearer " + settings.mcpToken(),
                      actual = request.getHeader("Authorization");
                  if (actual == null
                      || !MessageDigest.isEqual(
                          expected.getBytes(StandardCharsets.UTF_8),
                          actual.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Bearer");
                    return;
                  }
                  String origin = request.getHeader("Origin");
                  if (origin != null
                      && !origin.equals("http://localhost:3000")
                      && !origin.equals("http://127.0.0.1:3000")) {
                    response.setStatus(403);
                    return;
                  }
                }
                chain.doFilter(request, response);
              }
            },
            AnonymousAuthenticationFilter.class)
        .build();
  }
}
