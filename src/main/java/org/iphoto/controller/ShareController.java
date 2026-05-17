package org.iphoto.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.iphoto.model.*;
import org.iphoto.service.ShareService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/share")
@AllArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(); // Use a single instance



    @PostMapping
    public ResponseEntity<ShareResponse> createShare(@RequestBody ShareRequest request) {
        try {
            ShareResponse share = shareService.createShare(request);
            return new ResponseEntity<>(share, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.CONFLICT); // Album with name already exists
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Serializable>> createShare(@PathVariable("name")  String name
            ,HttpSession session) {
        ShareResponse share =
                shareService.getShareDetails(name);

        // non protégé
        if (!share.isPasswordProtected()) {

            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumId", share.albumId()
                    )
            );
        }

        // déjà authentifié
        Object authenticatedShare =
                session.getAttribute("SHARE_ID");

        if (authenticatedShare != null
                && authenticatedShare.toString().equals(share.id())) {

            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumId", share.albumId()
                    )
            );
        }

        // nécessite password
        return ResponseEntity.ok(
                Map.of(
                        "protected", true,
                        "shareId", share.id()
                )
        );
    }
    @PostMapping("/{shareId}/login")
    public ResponseEntity<?> login(
            @PathVariable String shareId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request
    ) {

        ShareResponse share =
                shareService.getShareDetailsById(shareId);

        boolean ok = encoder.matches(
                body.get("password"),
                shareService.getSharePassword(shareId)
        );

        if (!ok) {
            return ResponseEntity.status(401).build();
        }

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        "share-" + shareId,
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_SHARE")
                        )
                );

        SecurityContext context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);

        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);

        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        session.setAttribute(
                "SHARE_ID",
                shareId
        );

        return ResponseEntity.ok(share);
    }

}
