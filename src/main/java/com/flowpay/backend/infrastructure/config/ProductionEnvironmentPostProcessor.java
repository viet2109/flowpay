package com.flowpay.backend.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** Reject unsafe production settings before binding can print supplied secret values. */
public final class ProductionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        require(!environment.acceptsProfiles(Profiles.of("dev", "test")),
                "prod must not be combined with dev or test profiles");
        String url = required(environment, "spring.datasource.url");
        try {
            require(url.startsWith("jdbc:postgresql://"), "spring.datasource.url must use PostgreSQL");
            URI database = URI.create(url.substring(5));
            require(database.getHost() != null && database.getPath() != null
                            && database.getPath().length() > 1 && database.getUserInfo() == null
                            && (database.getPort() == -1 || database.getPort() > 0 && database.getPort() <= 65535),
                    "spring.datasource.url must identify a PostgreSQL host and database");
            if (database.getRawQuery() != null) {
                for (String parameter : database.getRawQuery().split("&")) {
                    String name = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
                            .toLowerCase(Locale.ROOT);
                    require(!Set.of("user", "password", "sslpassword").contains(name),
                            "PostgreSQL credentials must be supplied separately from spring.datasource.url");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw failure("spring.datasource.url must be a valid PostgreSQL JDBC URL");
        }
        required(environment, "spring.datasource.username");
        required(environment, "spring.datasource.password");
        String brokerHost = required(environment, "spring.rabbitmq.host");
        require(!brokerHost.contains("://") && !brokerHost.contains("/") && !brokerHost.contains("@")
                        && brokerHost.chars().noneMatch(Character::isWhitespace),
                "spring.rabbitmq.host must be a hostname or IP address");
        try {
            String host = brokerHost.contains(":") && !brokerHost.startsWith("[")
                    ? "[" + brokerHost + "]" : brokerHost;
            URI broker = URI.create("amqp://" + host);
            require(broker.getHost() != null && broker.getPort() == -1 && broker.getRawQuery() == null
                            && broker.getRawFragment() == null,
                    "spring.rabbitmq.host must be a hostname or IP address without a port");
        } catch (IllegalArgumentException exception) {
            throw failure("spring.rabbitmq.host must be a valid hostname or IP address");
        }
        port(environment, "spring.rabbitmq.port", false);
        required(environment, "spring.rabbitmq.username");
        required(environment, "spring.rabbitmq.password");
        require(value(environment, "spring.rabbitmq.addresses") == null,
                "prod uses spring.rabbitmq.host and port, not addresses");
        requireFlag(environment, "flowpay.security.jwt.generate-ephemeral-key-pair", false);
        required(environment, "flowpay.security.jwt.public-key-location");
        required(environment, "flowpay.security.jwt.private-key-location");
        requireFlag(environment, "flowpay.webhook.url-policy.allow-insecure-localhost", false);
        requireFlag(environment, "flowpay.security.prometheus-public", false);
        requireFlag(environment, "flowpay.security.refresh-cookie.cookie-secure", true);
        String encryptionKey = required(environment, "flowpay.webhook.secret.encryption-key");
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey);
            require(decoded.length == 32, "Webhook encryption key must decode to exactly 32 bytes");
        } catch (IllegalArgumentException exception) {
            throw failure("Webhook encryption key must be valid Base64 encoding of 32 bytes");
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
        int managementPort = port(environment, "management.server.port", true);
        int applicationPort = value(environment, "server.port") == null ? 8080 : port(environment, "server.port", true);
        require(managementPort == 0 || managementPort != applicationPort,
                "management.server.port must use a dedicated listener");
        required(environment, "management.server.address");
        require("never".equals(value(environment, "management.endpoint.health.show-details"))
                        && "never".equals(value(environment, "management.endpoint.health.show-components")),
                "prod health details and components must remain hidden");
        require(com.flowpay.backend.webhook.infrastructure.http.WebhookEgressProperties.validHost(
                        required(environment, "flowpay.webhook.egress.proxy-host")),
                "flowpay.webhook.egress.proxy-host must be a hostname or IP without credentials or port");
        port(environment, "flowpay.webhook.egress.proxy-port", false);
    }

    private static int port(ConfigurableEnvironment environment, String key, boolean randomAllowed) {
        try {
            int port = Integer.parseInt(required(environment, key));
            require(port >= (randomAllowed ? 0 : 1) && port <= 65535, key + " must be a valid port");
            return port;
        } catch (NumberFormatException exception) {
            throw failure(key + " must be a valid port");
        }
    }

    private static void requireFlag(ConfigurableEnvironment environment, String key, boolean expected) {
        require(Boolean.toString(expected).equalsIgnoreCase(value(environment, key)),
                key + " must be " + expected + " in prod");
    }

    private static String required(ConfigurableEnvironment environment, String key) {
        String value = value(environment, key);
        require(value != null && !value.isBlank() && !value.contains("${"), key + " must be configured");
        return value;
    }

    private static String value(ConfigurableEnvironment environment, String key) {
        try {
            String value = environment.getProperty(key);
            return value == null ? null : value.trim();
        } catch (RuntimeException exception) {
            throw failure(key + " must be configured with a valid value");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw failure(message);
        }
    }

    private static IllegalStateException failure(String message) {
        // Intentionally discard underlying parser/binder causes: they may include credentials.
        return new IllegalStateException("Invalid production configuration: " + message);
    }
}
