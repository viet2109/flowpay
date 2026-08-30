package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.merchant.application.ApiKeyAuthenticationUseCase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        FlowPaySecurityProperties.class,
        JwtSecurityProperties.class,
        RefreshTokenProperties.class
})
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain merchantApiSecurityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationUseCase apiKeyAuthentication,
            SecurityProblemHandler securityProblemHandler
    ) {
        MerchantApiAuthenticationProvider authenticationProvider =
                new MerchantApiAuthenticationProvider(apiKeyAuthentication);
        BearerTokenAuthenticationFilter apiKeyFilter = new BearerTokenAuthenticationFilter(
                new ProviderManager(authenticationProvider)
        );
        apiKeyFilter.setAuthenticationFailureHandler(securityProblemHandler::commence);

        http
                .securityMatcher(
                        "/api/v1/payment-intents",
                        "/api/v1/payment-intents/**",
                        "/api/v1/refunds",
                        "/api/v1/refunds/**"
                )
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityProblemHandler)
                        .accessDeniedHandler(securityProblemHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            FlowPaySecurityProperties properties,
            DashboardJwtAuthenticationConverter jwtAuthenticationConverter,
            SecurityProblemHandler securityProblemHandler
    ) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(securityProblemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityProblemHandler)
                        .accessDeniedHandler(securityProblemHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                    if (properties.prometheusPublic()) {
                        authorize.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll();
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll();
                    authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll();
                    authorize.requestMatchers("/api/v1/merchant/**").authenticated();
                    authorize.anyRequest().denyAll();
                });

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Qualifier("refreshTokenTtl")
    Duration refreshTokenTtl(RefreshTokenProperties properties) {
        return properties.ttl();
    }
}
