package com.flowpay.backend.webhook.infrastructure.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebhookHttpProperties.class, WebhookEgressProperties.class})
class WebhookHttpConfiguration {
    static final String HTTP_CLIENT = "webhookHttpClient";

    @Bean(name = HTTP_CLIENT, destroyMethod = "close")
    CloseableHttpAsyncClient webhookHttpClient(WebhookHttpProperties properties, WebhookEgressProperties egress,
            Environment environment) throws NoSuchAlgorithmException {
        if (environment.acceptsProfiles(Profiles.of("prod")) && !egress.configured()) {
            throw new IllegalStateException("Production Webhook HTTP requires an enforcing egress proxy");
        }
        Timeout connect = Timeout.of(properties.connectTimeout());
        var connections = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDnsResolver(egress.configured()
                        ? FixedWebhookProxyDnsResolver.snapshot(egress.proxyHost(), properties.connectTimeout()) : null)
                .setMaxConnTotal(32).setMaxConnPerRoute(8)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connect)
                        .setSocketTimeout(Timeout.DISABLED).build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(connect)
                        .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1).build())
                .setTlsStrategy(new DefaultClientTlsStrategy(SSLContext.getDefault(),
                        HostnameVerificationPolicy.BOTH, new DefaultHostnameVerifier()))
                .build();
        var client = HttpAsyncClients.custom().setConnectionManager(connections)
                .setIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(2)
                        .setSoTimeout(Timeout.DISABLED).build())
                .setRoutePlanner(egress.routePlanner())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(properties.requestTimeout()))
                        .setRedirectsEnabled(false).setAuthenticationEnabled(false)
                        .setContentCompressionEnabled(false).setHardCancellationEnabled(true).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().disableConnectionState()
                .build();
        client.start();
        return client;
    }
}
