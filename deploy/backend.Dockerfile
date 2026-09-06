FROM node:24.13.0-bookworm-slim AS node-runtime

FROM gradle:9.6.1-jdk21 AS builder
COPY --from=node-runtime /usr/local/ /usr/local/
WORKDIR /workspace
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY --chown=gradle:gradle gradle/ gradle/
COPY --chown=gradle:gradle contracts/ contracts/
COPY --chown=gradle:gradle src/ src/
COPY --chown=gradle:gradle web/src/generated/ web/src/generated/
USER gradle
RUN ./gradlew bootJar --no-daemon --no-configuration-cache

FROM eclipse-temurin:21.0.8_9-jre-jammy
RUN groupadd --system --gid 10001 haoemr \
    && useradd --system --uid 10001 --gid haoemr --home-dir /opt/haoemr haoemr
WORKDIR /opt/haoemr
COPY --from=builder /workspace/build/libs/*.jar app.jar
COPY --chown=haoemr:haoemr samples/data/ samples/data/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "app.jar"]
