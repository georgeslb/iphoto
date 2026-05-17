package org.iphoto.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ShareAlbumFilter shareAlbumFilter
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .formLogin(AbstractHttpConfigurer::disable)

                .httpBasic(AbstractHttpConfigurer::disable)

                .logout(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/share/**"
                        ).permitAll()

                        // React SPA
                        .requestMatchers(
                                "/",
                                "/share/**",
                                "/album/**",
                                "/assets/**"
                        ).permitAll()

                        .anyRequest().permitAll()
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (request, response, authException) -> response.sendError(401)
                        )
                )

                .addFilterBefore(
                        shareAlbumFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}