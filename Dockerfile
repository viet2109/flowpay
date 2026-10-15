# Artifact-first equivalent to a multi-stage build: use the exact clean-verified
# JAR, not a second Maven compilation. ops/container/image.py supplies a minimal
# context, checks verification evidence and records both JAR and image identity.
FROM eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:8c2dddf1bb2a8455160f4e23080059de5003eddc5cb839130b177c6be0c2cfe0
ARG JAR_SHA256
WORKDIR /opt/flowpay
COPY --chown=0:0 --chmod=0444 app.jar /opt/flowpay/app.jar
RUN test "${#JAR_SHA256}" = 64 && printf '%s  %s\n' "$JAR_SHA256" app.jar | sha256sum -c - >/dev/null
LABEL org.opencontainers.image.title="FlowPay" \
      org.opencontainers.image.description="Verified-artifact Java 21 runtime; runtime secrets required"
ENV SPRING_PROFILES_ACTIVE=prod
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-Xms128m", "-Xmx768m", "-XX:MaxDirectMemorySize=128m", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/flowpay/app.jar"]
