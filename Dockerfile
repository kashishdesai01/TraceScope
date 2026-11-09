FROM eclipse-temurin:21-jdk-alpine@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3 AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY libs/trace-model/build.gradle.kts libs/trace-model/build.gradle.kts
COPY server/build.gradle.kts server/build.gradle.kts
COPY demo-shop/build.gradle.kts demo-shop/build.gradle.kts
COPY tests/acceptance/build.gradle.kts tests/acceptance/build.gradle.kts
RUN ./gradlew resolveDependencies :demo-shop:copyAgent --no-daemon
COPY libs libs
COPY server server
COPY demo-shop demo-shop
COPY tests/acceptance/build.gradle.kts tests/acceptance/build.gradle.kts
RUN ./gradlew :server:bootJar :demo-shop:bootJar :demo-shop:copyAgent --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52 AS runtime
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app
WORKDIR /app
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -Xms64m -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1

FROM runtime AS server
COPY --from=build --chown=10001:10001 /build/server/build/libs/server-0.1.0.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM runtime AS demo-shop
COPY --from=build --chown=10001:10001 /build/demo-shop/build/libs/demo-shop-0.1.0.jar app.jar
COPY --from=build --chown=10001:10001 /build/demo-shop/build/otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:+UseG1GC -Xms64m -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/app.jar"]
