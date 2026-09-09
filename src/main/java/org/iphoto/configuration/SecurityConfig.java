package org.iphoto.configuration;

import org.iphoto.properties.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    // A week, matching the "remember me for 1 week" feature request - not configurable per
    // request (the checkbox is just on/off), so kept as a plain constant here rather than a new
    // property.
    private static final int REMEMBER_ME_VALIDITY_SECONDS = 7 * 24 * 60 * 60;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ShareAccessFilter shareAccessFilter,
            DeviceTokenAuthenticationFilter deviceTokenAuthenticationFilter,
            SecurityProperties securityProperties
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/share/**",
                                "/api/login",
                                "/api/logout",
                                "/api/auth/mobile/login"
                        ).permitAll()

                        // Admin operations
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // React SPA
                        .requestMatchers(
                                "/",
                                "/share/**",
                                "/album/**",
                                "/assets/**",
                                "/static/**",
                                "/index.html",
                                "/favicon.ico"
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .formLogin(formLogin -> formLogin
                        .loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) -> response.setStatus(200))
                        .failureHandler((request, response, exception) -> response.sendError(401))
                )
                // "Remember me" checkbox (Login.jsx) - sends remember-me=true/on in the login
                // POST body, Spring's UsernamePasswordAuthenticationFilter picks it up under this
                // exact parameter name automatically (rememberMeParameter defaults to
                // "remember-me", spelled out here anyway for clarity) and, on successful login,
                // RememberMeAuthenticationFilter/TokenBasedRememberMeServices sign a persistent
                // cookie good for REMEMBER_ME_VALIDITY_SECONDS. That cookie only matters once the
                // session itself is gone (expired past server.servlet.session.timeout, or the
                // browser was closed and reopened) - it transparently re-establishes
                // authentication and mints a fresh session on the next request, no re-login
                // needed. key(...) signs/verifies the cookie and MUST be a real secret in
                // production (see SecurityProperties' own comment) - reusing the default here
                // would let anyone forge a valid cookie for any username.
                .rememberMe(rememberMe -> rememberMe
                        .key(securityProperties.getRememberMeKey())
                        .rememberMeParameter("remember-me")
                        .tokenValiditySeconds(REMEMBER_ME_VALIDITY_SECONDS)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(200))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (request, response, authException) -> response.sendError(401)
                        )
                )
                // deviceTokenAuthenticationFilter establishes authentication from a Bearer token;
                // shareAccessFilter authorizes an already-established one - registered in that
                // order (both anchored "before" UsernamePasswordAuthenticationFilter, so
                // registration order here is what determines their relative order in the chain).
                .addFilterBefore(
                        deviceTokenAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        shareAccessFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}