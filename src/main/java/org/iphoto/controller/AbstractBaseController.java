package org.iphoto.controller;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.NoSuchElementException;

@RequiredArgsConstructor
public abstract class AbstractBaseController {

    private final UserRepository userRepository; // Inject UserRepository

    public UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

}
