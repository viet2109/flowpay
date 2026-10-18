package com.flowpay.backend.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ProductionEnvironmentPostProcessorTest {

    private final ProductionEnvironmentPostProcessor processor = new ProductionEnvironmentPostProcessor();

    @Test
    void shouldAcceptExplicitProductionSettingsAndRandomDedicatedTestPorts() {
        MockEnvironment environment = productionEnvironment();
        assertThatCode(() -> processor.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
        environment.setProperty("server.port", "0");
        environment.setProperty("management.server.port", "0");
        assertThatCode(() -> processor.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void shouldNotApplyProductionRestrictionsToDevelopmentOrTests(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        assertThatCode(() -> processor.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void shouldRejectMixedProductionConvenienceProfiles(String profile) {
        MockEnvironment environment = productionEnvironment();
        environment.setActiveProfiles("prod", profile);
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .hasMessageContaining("prod must not be combined");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "spring.rabbitmq.host", "spring.rabbitmq.username", "spring.rabbitmq.password",
            "flowpay.security.jwt.public-key-location", "flowpay.security.jwt.private-key-location",
            "flowpay.webhook.secret.encryption-key", "management.server.address",
            "flowpay.webhook.egress.proxy-host", "flowpay.webhook.egress.proxy-port"})
    void shouldRejectMissingMandatorySettingsWithoutEchoingValues(String key) {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty(key, " ");
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .hasMessageContaining("must be configured").hasCause(null)
                .hasStackTraceContaining("Invalid production configuration")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("sentinel-password"));
    }

    @ParameterizedTest
    @CsvSource({
            "spring.datasource.url,jdbc:mysql://localhost/db",
            "spring.datasource.url,jdbc:postgresql://sentinel-password@localhost/db",
            "spring.datasource.url,jdbc:postgresql://localhost",
            "spring.datasource.url,jdbc:postgresql://localhost:99999/db",
            "spring.datasource.url,jdbc:postgresql://bad host/db?password=sentinel-password",
            "spring.datasource.url,jdbc:postgresql://localhost/db?password=sentinel-password",
            "spring.datasource.url,jdbc:postgresql://localhost/db?%70assword=sentinel-password",
            "spring.rabbitmq.host,https://sentinel-password@broker",
            "spring.rabbitmq.host,broker:5672",
            "spring.rabbitmq.host,[invalid-ip]",
            "spring.rabbitmq.addresses,amqp://sentinel-password@broker",
            "spring.rabbitmq.port,not-a-port-sentinel-password",
            "spring.rabbitmq.port,0",
            "spring.rabbitmq.port,65536",
            "management.server.port,-1",
            "management.server.port,8080",
            "flowpay.security.jwt.generate-ephemeral-key-pair,true",
            "flowpay.webhook.url-policy.allow-insecure-localhost,true",
            "flowpay.webhook.egress.proxy-host,http://sentinel-password@proxy",
            "flowpay.webhook.egress.proxy-host,proxy:3128",
            "flowpay.webhook.egress.proxy-port,0",
            "flowpay.webhook.egress.proxy-port,65536",
            "flowpay.webhook.egress.proxy-port,sentinel-password",
            "flowpay.security.prometheus-public,true",
            "flowpay.security.refresh-cookie.cookie-secure,false",
            "management.endpoint.health.show-details,always",
            "management.endpoint.health.show-components,always",
            "flowpay.webhook.secret.encryption-key,sentinel-password!",
            "flowpay.webhook.secret.encryption-key,AAAA"
    })
    void shouldRejectInvalidOrUnsafeProductionOverridesWithSanitizedErrors(String key, String value) {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty(key, value);
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class).hasCause(null)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("sentinel-password"));
    }

    @Test
    void shouldSanitizeUnresolvedPlaceholdersBeforePropertyBinding() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("spring.datasource.password", "${MISSING_PASSWORD}");
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .hasMessageContaining("spring.datasource.password must be configured").hasCause(null);
    }

    @Test
    void shouldRunTheRegisteredGuardAfterRealProductionConfigDataAndBeforeBeanCreation() {
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        assertThatThrownBy(() -> application.run("--spring.profiles.active=prod,dev", "--spring.main.banner-mode=off"))
                .hasMessage("Invalid production configuration: prod must not be combined with dev or test profiles");
    }

    @ParameterizedTest
    @CsvSource({"DB_URL,jdbc:postgresql://bad host/db?password=sentinel-private-material",
            "RABBITMQ_PASSWORD,' '", "WEBHOOK_SECRET_ENCRYPTION_KEY,sentinel-private-material!",
            "JWT_PRIVATE_KEY_LOCATION,' '"})
    void shouldFailRealProdStartupWithRedactedDiagnostics(String key, String invalid, CapturedOutput output) {
        SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/flowpay", "DB_USERNAME", "prod-test",
                "DB_PASSWORD", "sentinel-password", "RABBITMQ_HOST", "localhost",
                "RABBITMQ_USERNAME", "prod-test", "RABBITMQ_PASSWORD", "sentinel-password",
                "JWT_PUBLIC_KEY_LOCATION", "file:/mounted/public.pem",
                "JWT_PRIVATE_KEY_LOCATION", "file:/mounted/private.pem",
                "WEBHOOK_SECRET_ENCRYPTION_KEY", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        ));
        assertThatThrownBy(() -> application.run("--spring.profiles.active=prod", "--spring.main.banner-mode=off",
                "--" + key + "=" + invalid)).hasMessageContaining("Invalid production configuration").hasCause(null);
        assertThat(output.getAll()).doesNotContain("sentinel-private-material", "sentinel-password");
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:5432/flowpay",
                "spring.datasource.username", "sentinel-user",
                "spring.datasource.password", "sentinel-password",
                "spring.rabbitmq.host", "broker",
                "spring.rabbitmq.port", "5672",
                "spring.rabbitmq.username", "sentinel-user",
                "spring.rabbitmq.password", "sentinel-password",
                "flowpay.security.jwt.public-key-location", "file:/mounted/public.pem",
                "flowpay.security.jwt.private-key-location", "file:/mounted/private.pem",
                "flowpay.webhook.secret.encryption-key", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        ));
        properties.putAll(Map.of(
                "flowpay.security.jwt.generate-ephemeral-key-pair", "false",
                "flowpay.security.prometheus-public", "false",
                "flowpay.security.refresh-cookie.cookie-secure", "true",
                "flowpay.webhook.url-policy.allow-insecure-localhost", "false",
                "management.server.port", "9091",
                "management.server.address", "127.0.0.1",
                "management.endpoint.health.show-details", "never",
                "management.endpoint.health.show-components", "never"
        ));
        properties.put("flowpay.webhook.egress.proxy-host", "egress");
        properties.put("flowpay.webhook.egress.proxy-port", "3128");
        environment.getPropertySources().addLast(new MapPropertySource("production-test", properties));
        return environment;
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
