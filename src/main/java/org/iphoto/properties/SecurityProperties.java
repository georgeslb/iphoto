package org.iphoto.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// Same shape/precedent as AdminProperties - a single deployment-config value, not worth folding
// into IPhotoProperties (that class is for app *behavior* knobs, this is a secret).
@Component
@ConfigurationProperties(prefix = "iphoto.security")
@Data
public class SecurityProperties {
    // Signs/verifies the "remember me" persistent-login cookie (SecurityConfig's
    // rememberMe().key(...)) - anyone who knows this value can forge a valid remember-me cookie
    // for any username, so it MUST be overridden to a real secret in production (see
    // application.yml/docker-compose.yml's IPHOTO_REMEMBER_ME_KEY - same required-override
    // pattern as iphoto.admin.password/IPHOTO_ADMIN_PASSWORD). Changing it invalidates every
    // outstanding remember-me cookie, which is the correct behavior if it's ever suspected leaked.
    private String rememberMeKey;
}
