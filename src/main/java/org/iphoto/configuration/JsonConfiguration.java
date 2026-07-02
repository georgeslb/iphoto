package org.iphoto.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.datatype.hibernate5.jakarta.Hibernate5JakartaModule;

@Configuration
public class JsonConfiguration {
    @Bean
    public JacksonModule hibernateModule() {
        return new Hibernate5JakartaModule();
    }
}