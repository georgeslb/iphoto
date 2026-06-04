package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;

    public List<UserEntity> findAll() {
        return userRepository.findAll();
    }

    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    public Optional<UserEntity> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public UserEntity createUser(String username, String password, String role) {
        UserEntity user = UserEntity.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .role(role).build();
        SpaceEntity defaultSpace = spaceService.createDefaultSpace(user);
        Set<SpaceEntity> spaces = new HashSet<>();
        spaces.add(defaultSpace);
        user.setSpaces(spaces);
        return userRepository.save(user);
    }

    public void deleteUser(UUID id) {
        userRepository.deleteById(id);
    }

    public Optional<UserEntity> resetPassword(UUID id, String newPassword) {
        return userRepository.findById(id).map(user -> {
            user.setPassword(passwordEncoder.encode(newPassword));
            return userRepository.save(user);
        });
    }
}