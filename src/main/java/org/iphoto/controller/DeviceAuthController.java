package org.iphoto.controller;

import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.DeviceDTO;
import org.iphoto.model.DeviceTokenResponse;
import org.iphoto.model.UserDto;
import org.iphoto.model.request.DeviceLoginRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.auth.DeviceTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

// Mobile-only auth surface, separate from SecurityConfig's session-cookie /api/login: issues a
// long-lived device token (see DeviceTokenService for why it never expires on its own) instead of
// a session, for the Android app. /login is permitAll (SecurityConfig); the two /devices routes
// require the caller to already be authenticated somehow - either a web session or a Bearer
// token, both work, since AbstractBaseController.getCurrentUser() doesn't care which established
// it.
@RestController
@RequestMapping("/api/auth/mobile")
public class DeviceAuthController extends AbstractBaseController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceTokenService deviceTokenService;

    public DeviceAuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, DeviceTokenService deviceTokenService) {
        super(userRepository);
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<DeviceTokenResponse> login(@RequestBody DeviceLoginRequest request) {
        // No AuthenticationManager bean exists in this codebase to delegate to (SecurityConfig's
        // formLogin drives Spring Security's own internal one for the web login form) - validate
        // the same direct way AbstractBaseController/UploadController already resolve users
        // elsewhere in this codebase.
        UserEntity user = userRepository.findByUsername(request.username()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        DeviceTokenService.IssuedToken issued = deviceTokenService.issueToken(user, request.deviceName());
        UserDto userDto = new UserDto(user.getId(), user.getUsername(), user.getRole());
        return ResponseEntity.ok(new DeviceTokenResponse(
                issued.id(), issued.rawToken(), issued.deviceName(), issued.createdAt(), userDto
        ));
    }

    @GetMapping("/devices")
    public List<DeviceDTO> listDevices() {
        UserEntity currentUser = getCurrentUser();
        return deviceTokenService.listForUser(currentUser.getId()).stream()
                .map(DeviceAuthController::toDeviceDTO)
                .toList();
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID id) {
        try {
            deviceTokenService.revoke(id, getCurrentUser());
            return ResponseEntity.ok().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static DeviceDTO toDeviceDTO(DeviceTokenEntity entity) {
        return new DeviceDTO(
                entity.getId(),
                entity.getDeviceName(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getRevokedAt() != null
        );
    }
}
