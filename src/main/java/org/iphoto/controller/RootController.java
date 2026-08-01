package org.iphoto.controller;

import org.iphoto.dto.UserDto;
import org.iphoto.mappers.UserMapper;
import org.iphoto.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    private final UserService userService;
    private final UserMapper userMapper;

    public RootController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @GetMapping("/api/user/me")
    public UserDto getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            String username = authentication.getName();
            return userService.findByUsername(username)
                    .map(userMapper::toDto)
                    .orElse(null); // Or throw an exception if user not found
        }
        return null; // No authenticated user
    }

    @RequestMapping(value = {
            "/{path:[^\\.]*}",
            "/{path:^(?!api$).*$}/{path2:[^\\.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}