package org.iphoto.configuration;

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

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ShareAccessFilter shareAccessFilter,
            DeviceTokenAuthenticationFilter deviceTokenAuthenticationFilter
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