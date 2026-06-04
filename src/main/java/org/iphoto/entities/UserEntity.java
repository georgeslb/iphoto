package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Setter(value = AccessLevel.PUBLIC)
@Getter
@ToString(onlyExplicitlyIncluded = true)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ToString.Include
    private String username;
    private String password;
    private String role;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    Set<SpaceEntity> spaces;
}