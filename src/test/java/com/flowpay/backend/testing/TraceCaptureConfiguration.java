package com.flowpay.backend.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Uses the application's Boot-owned SDK and production sanitizing exporter boundary. */
@TestConfiguration(proxyBeanMethods = false)
public class TraceCaptureConfiguration {
    @Bean public CapturingSpanExporter capturingSpanExporter() { return new CapturingSpanExporter(); }
}
