# Phase 1 — model and compatibility

Verified locally on 2026-11-08 with Java 21 and Gradle 8.14.3.

Command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew :libs:trace-model:test :server:compileJava --no-daemon
```

Result: **PASS**. Fifteen model tests/properties, zero failures or skips. The property includes 200 generated merge/quantile trials. Distribution tests compare exact nearest-rank quantiles for 40 seeded samples of 10,000 observations; the sampler checks 100,000 IDs. Fixtures cover duplicates, arrival order, missing parents, cycles, sequential and parallel timing, skew, child-overrun clipping, failed-service/database edges, sensitive-key removal, and protobuf/resource/exception mapping.

Pinned Spring Boot 3.5.10, Spring AI 1.1.2 and ClickHouse client 0.9.4 compile together. This compatibility evidence does not prove MCP protocol sessions or runtime deployment.

Author review corrected child-overrun clipping and explicitly marked approximate timing attribution. Independent review is pending until the complete implementation exists.
