package org.iphoto.controller;

import org.iphoto.entities.UserEntity;
import org.iphoto.mappers.UserMapper;
import org.iphoto.model.UserDto;
import org.iphoto.model.request.UserCreateRequest;
import org.iphoto.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @GetMapping
    public List<UserDto> getAllUsers() {
        return userMapper.toDtoList(userService.findAll());
    }

    @PostMapping
    public UserDto createUser(@RequestBody UserCreateRequest userCreateRequest) {
        UserEntity newUser = userService.createUser(userCreateRequest.getUsername(), userCreateRequest.getPassword(), userCreateRequest.getRole());
        return userMapper.toDto(newUser);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<UserDto> resetPassword(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String newPassword = payload.get("newPassword");
        return userService.resetPassword(id, newPassword)
                .map(userMapper::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
