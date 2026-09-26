package com.newrealityinteractive.untitled.config;

import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(ApiUserProperties.class)
public class SecurityConfig {

  @Bean
  SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    return http.authorizeExchange(
            exchanges ->
                exchanges
                    .matchers(
                        EndpointRequest.to(
                            HealthEndpoint.class,
                            InfoEndpoint.class,
                            PrometheusScrapeEndpoint.class))
                    .permitAll()
                    .pathMatchers(
                        "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        // Lambdas rather than method references: Customizer's type parameter is not null-annotated.
        .formLogin(formLogin -> formLogin.disable())
        .logout(logout -> logout.disable())
        .csrf(csrf -> csrf.disable())
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  MapReactiveUserDetailsService userDetailsService(
      ApiUserProperties apiUser, PasswordEncoder passwordEncoder) {
    return new MapReactiveUserDetailsService(
        User.withUsername(apiUser.username())
            .password(passwordEncoder.encode(apiUser.password()))
            .roles("API")
            .build());
  }
}
