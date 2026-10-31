package com.chubb.claims.config;

import static com.chubb.platform.security.Roles.CLAIMANT;
import static com.chubb.platform.security.Roles.MANAGER;
import static com.chubb.platform.security.Roles.OFFICER;

import com.chubb.platform.security.PlatformSecurityConfig;
import com.chubb.platform.security.ProblemJsonSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Role rules per URL. Record-level rules (own claims only, assigned officer only) are enforced in ClaimService.
 * <ul>
 *   <li>CLAIMANT: submit, list own, answer information requests, upload documents</li>
 *   <li>OFFICER: queue, pick up, request info, assess, approve, reject, settle, reassign own claims</li>
 *   <li>MANAGER: read-only views of staff data and reassignment of any claim</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@Import(PlatformSecurityConfig.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemJsonSecurityHandlers handlers) throws Exception {
        PlatformSecurityConfig.baseline(http, handlers).authorizeHttpRequests(auth -> auth
                .requestMatchers(PlatformSecurityConfig.PUBLIC_ENDPOINTS).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/me").authenticated()
                // claimant
                .requestMatchers(HttpMethod.POST, "/api/claims").hasRole(CLAIMANT)
                .requestMatchers(HttpMethod.GET, "/api/claims").hasRole(CLAIMANT)
                .requestMatchers(HttpMethod.POST, "/api/claims/*/info-requests/*/response").hasRole(CLAIMANT)
                .requestMatchers(HttpMethod.POST, "/api/claims/*/documents").hasRole(CLAIMANT)
                .requestMatchers(HttpMethod.GET, "/api/claims/**").hasAnyRole(CLAIMANT, OFFICER, MANAGER)
                // AI assistant: acts as the caller, so claimants and officers only; ClaimService still enforces the rules
                .requestMatchers(HttpMethod.POST, "/api/agent/chat").hasAnyRole(CLAIMANT, OFFICER)
                // staff
                .requestMatchers(HttpMethod.POST, "/api/staff/claims/*/reassign").hasAnyRole(OFFICER, MANAGER)
                .requestMatchers(HttpMethod.GET, "/api/staff/**").hasAnyRole(OFFICER, MANAGER)
                .requestMatchers("/api/staff/**").hasRole(OFFICER)
                .anyRequest().denyAll());
        return http.build();
    }
}
