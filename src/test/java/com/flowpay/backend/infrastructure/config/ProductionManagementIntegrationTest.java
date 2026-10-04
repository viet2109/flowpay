package com.flowpay.backend.infrastructure.config;

import com.flowpay.backend.FlowPayApplication;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Owned disposable infrastructure; no dev/test profile or mocked health contributors. */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionManagementIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    @TempDir
    static Path keyDirectory;

    private static final CountDownLatch REQUEST_ENTERED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_REQUEST = new CountDownLatch(1);
    private static ConfigurableApplicationContext context;
    private static int businessPort;
    private static int managementPort;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeAll
    static void startProductionApplication() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Path publicKey = writePem("public.pem", "PUBLIC KEY", pair.getPublic().getEncoded());
        Path privateKey = writePem("private.pem", "PRIVATE KEY", pair.getPrivate().getEncoded());
        SpringApplication application = new SpringApplication(FlowPayApplication.class, DrainTestConfiguration.class);
        application.setRegisterShutdownHook(false);
        // Highest-precedence direct properties prevent local DB/broker environment settings being used.
        List<String> arguments = new ArrayList<>(Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(), "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(), "spring.rabbitmq.host", RABBIT.getHost(),
                "spring.rabbitmq.port", RABBIT.getAmqpPort(), "spring.rabbitmq.username", RABBIT.getAdminUsername(),
                "spring.rabbitmq.password", RABBIT.getAdminPassword(),
                "flowpay.security.jwt.public-key-location", publicKey.toUri().toString(),
                "flowpay.security.jwt.private-key-location", privateKey.toUri().toString(),
                "flowpay.webhook.secret.encryption-key", Base64.getEncoder().encodeToString(new byte[32])
        ).entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toList());
        arguments.addAll(List.of("--spring.profiles.active=prod", "--server.port=0", "--management.server.port=0",
                "--management.server.address=127.0.0.1",
                "--server.forward-headers-strategy=framework",
                "--spring.config.location=classpath:/application.yaml,classpath:/application-prod.yaml",
                "--spring.main.banner-mode=off", "--management.otlp.metrics.export.enabled=false",
                "--management.opentelemetry.tracing.sampler=always-off",
                "--flowpay.messaging.ledger-consumer.enabled=false",
                "--flowpay.messaging.webhook-consumer.enabled=false", "--flowpay.messaging.outbox.relay.enabled=false",
                "--flowpay.webhook.delivery-worker.enabled=false", "--flowpay.idempotency.cleanup.enabled=false",
                "--spring.rabbitmq.connection-timeout=1s", "--spring.datasource.hikari.connection-timeout=1500",
                "--spring.datasource.hikari.validation-timeout=1000", "--spring.datasource.hikari.minimum-idle=0",
                "--spring.datasource.hikari.maximum-pool-size=2"));
        context = application.run(arguments.toArray(String[]::new));
        businessPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
    }

    @AfterAll
    static void closeApplication() {
        RELEASE_REQUEST.countDown();
        if (context != null) {
            context.close();
        }
    }

    @Test
    @Order(1)
    void shouldExposeOnlyInternalScrapesAndMinimalProbesWithoutWeakeningBusinessAuthentication() throws Exception {
        assertThat(managementPort).isNotEqualTo(businessPort);
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("prod");
        assertThat(context.getEnvironment().getProperty("management.server.address")).isEqualTo("127.0.0.1");
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes").doesNotContain("PRIVATE KEY");
        assertThat(get(businessPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get(businessPort, "/actuator/health").statusCode()).isEqualTo(401);
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/configprops",
                "/actuator/loggers", "/actuator/shutdown", "/api/v1/merchant")) {
            assertThat(get(managementPort, path).statusCode()).isEqualTo(401);
        }
        assertThat(get(managementPort, "/actuator/health/db").body()).doesNotContain("details", "components", "jdbc:");
        assertThat(get(managementPort, "/api/v1/payment-intents").statusCode()).isEqualTo(401);
        assertThat(get(businessPort, "/api/v1/payment-intents").statusCode()).isEqualTo(401);
        assertStatus(businessPort, "/livez", 200, "UP");
        assertStatus(businessPort, "/readyz", 200, "UP");

        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("https://flowpay.dev")
                .subject("usr_probe_test").claim("merchant", "mrc_probe_test").claim("role", "OWNER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        String token = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(claims)).getTokenValue();
        HttpRequest authenticated = HttpRequest.newBuilder(uri(businessPort, "/actuator/prometheus"))
                .header("Authorization", "Bearer " + token)
                .header("Forwarded", "host=127.0.0.1:" + managementPort)
                .header("X-Forwarded-Port", Integer.toString(managementPort)).GET().build();
        assertThat(CLIENT.send(authenticated, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        HttpRequest anonymousSpoofed = HttpRequest.newBuilder(uri(businessPort, "/actuator/prometheus"))
                .header("X-Forwarded-Port", Integer.toString(managementPort)).GET().build();
        assertThat(CLIENT.send(anonymousSpoofed, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        HttpRequest post = HttpRequest.newBuilder(uri(managementPort, "/actuator/prometheus"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(CLIENT.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void shouldReflectRefusingTrafficWithoutDeclaringTheProcessDead() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertStatus(businessPort, "/readyz", 503, "OUT_OF_SERVICE");
            assertStatus(managementPort, "/actuator/health/readiness", 503, "OUT_OF_SERVICE");
            assertStatus(businessPort, "/livez", 200, "UP");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    @Order(3)
    void shouldKeepBrokerOutageOffReadinessButRejectTrafficWhenPostgresIsUnavailable() throws Exception {
        RABBIT.stop();
        assertStatus(managementPort, "/actuator/health", 503, "DOWN");
        // Component routes are hidden as well as their details; only groups/status are public to the lab.
        assertThat(get(managementPort, "/actuator/health/rabbit").statusCode()).isEqualTo(404);
        assertStatus(businessPort, "/readyz", 200, "UP");
        assertStatus(businessPort, "/livez", 200, "UP");
        POSTGRES.stop();
        assertStatus(businessPort, "/readyz", 503, "DOWN");
        assertStatus(managementPort, "/actuator/health/readiness", 503, "DOWN");
        assertStatus(businessPort, "/livez", 200, "UP");
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void shouldDrainAnInFlightHttpRequestDuringBoundedGracefulShutdown() throws Exception {
        assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(context.getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("30s");
        HttpRequest request = HttpRequest.newBuilder(uri(businessPort, "/livez"))
                .header("X-Test-Block", "true").timeout(Duration.ofSeconds(15)).GET().build();
        var response = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        assertThat(REQUEST_ENTERED.await(5, TimeUnit.SECONDS)).isTrue();
        var gracefulShutdown = context.getBean(WebServerGracefulShutdownLifecycle.class);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var shutdown = executor.submit(context::close);
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> !gracefulShutdown.isRunning());
                assertThat(shutdown.isDone()).isFalse();
                assertThat(response.isDone()).isFalse();
            } finally {
                RELEASE_REQUEST.countDown();
            }
            assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            shutdown.get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertStatus(int port, String path, int expectedCode, String status) throws Exception {
        HttpResponse<String> response = get(port, path);
        assertThat(response.statusCode()).isEqualTo(expectedCode);
        assertThat(response.body()).contains("\"status\":\"" + status + "\"")
                .doesNotContain("details", "components", "jdbc:", "password", "exception");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Path writePem(String filename, String type, byte[] bytes) throws IOException {
        return Files.writeString(keyDirectory.resolve(filename), "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder().encodeToString(bytes) + "\n-----END " + type + "-----\n");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DrainTestConfiguration {
        @Bean
        FilterRegistrationBean<Filter> drainTestFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                if ("true".equals(((HttpServletRequest) request).getHeader("X-Test-Block"))) {
                    REQUEST_ENTERED.countDown();
                    try {
                        if (!RELEASE_REQUEST.await(15, TimeUnit.SECONDS)) {
                            throw new jakarta.servlet.ServletException("Test request release timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new jakarta.servlet.ServletException("Test interrupted");
                    }
                }
                chain.doFilter(request, response);
            });
            registration.addUrlPatterns("/livez");
            return registration;
        }
    }
}
