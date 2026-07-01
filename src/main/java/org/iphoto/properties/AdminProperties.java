package org.iphoto.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "iphoto.admin")
@Data
public class AdminProperties {
    private String username;
    private String password;
    private String role;
}
