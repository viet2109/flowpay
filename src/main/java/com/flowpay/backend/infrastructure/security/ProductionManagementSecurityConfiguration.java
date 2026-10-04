package com.flowpay.backend.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionManagementSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain productionManagementSecurityFilterChain(
            HttpSecurity http, Environment environment, SecurityProblemHandler problemHandler
    ) {
        http.securityMatcher(request -> {
                    // Connector-local port, never Host/Forwarded headers controlled by a caller.
                    Integer port = environment.getProperty("local.management.port", Integer.class);
                    return port != null && request.getLocalPort() == port;
                })
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
