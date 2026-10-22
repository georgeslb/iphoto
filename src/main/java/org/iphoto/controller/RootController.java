package org.iphoto.controller;

import org.iphoto.model.UserDto;
import org.iphoto.mappers.UserMapper;
import org.iphoto.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class RootController {

    private final UserService userService;
    private final UserMapper userMapper;

    public RootController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @GetMapping("/api/user/me")
    @ResponseBody
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

    @PutMapping("/api/user/me/duplication-regrouping")
    @ResponseBody
    public ResponseEntity<UserDto> updateMyDuplicationRegrouping(@RequestBody Map<String, Boolean> payload) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            String username = authentication.getName();
            Boolean rawEnabled = payload.get("duplicationRegrouping");
            if (rawEnabled == null) {
                rawEnabled = payload.get("enabled");
            }
            boolean enabled = rawEnabled != null ? rawEnabled : true;
            return userService.findByUsername(username)
                    .flatMap(user -> userService.updateDuplicationRegrouping(user.getId(), enabled))
                    .map(userMapper::toDto)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @RequestMapping(value = {
            "/{path:[^\\.]*}",
            "/{path:^(?!api$).*$}/{path2:[^\\.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }

    @RequestMapping("/share/**")
    public String forwardShare() {
        return "forward:/index.html";
    }
}
