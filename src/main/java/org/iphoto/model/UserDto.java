package org.iphoto.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private UUID id;
    private String username;
    private String role;
    private Boolean duplicationRegrouping = true;

    public UserDto(UUID id, String username, String role) {
        this(id, username, role, true);
    }
}
