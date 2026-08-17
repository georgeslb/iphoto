package org.iphoto.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.*;
import org.iphoto.model.request.ShareRequest;
import org.iphoto.repository.UserRepository;
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
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/share")
@AllArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final UserRepository userRepository; // Inject UserRepository
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(); // Use a single instance

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    @PostMapping
    public ResponseEntity<ShareResponse> createShare(@RequestBody ShareRequest request) {
        UserEntity currentUser = getCurrentUser();
        try {
            ShareResponse share = shareService.createShare(currentUser, request);
            return new ResponseEntity<>(share, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.CONFLICT); // Album with name already exists for this user
        }
    }

    @GetMapping("/my-shares")
    public ResponseEntity<List<ShareResponse>> getAllMyShares() {
        UserEntity currentUser = getCurrentUser();
        List<ShareResponse> shares = shareService.getAllShares(currentUser);
        return ResponseEntity.ok(shares);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShareResponse> getMyShareById(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        try {
            ShareResponse share = shareService.getShareById(currentUser, id);
            return ResponseEntity.ok(share);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMyShare(@PathVariable UUID id) {
        UserEntity currentUser = getCurrentUser();
        try {
            shareService.deleteShare(currentUser, id);
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        } catch (NoSuchElementException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    // This endpoint is for public access to a share link, so it does not filter by user.
    @GetMapping("{albumId}/{name}")
    public ResponseEntity<Map<String, Serializable>> getShareDetailsByName(@PathVariable("albumId") UUID albumId, @PathVariable("name")  String name, HttpSession session) {
        ShareResponse share = shareService.getShareDetails(albumId, name);

        // non protégé
        if (!share.isPasswordProtected()) {
            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumIds", share.albumId()
                    )
            );
        }

        // déjà authentifié
        Object authenticatedShare = session.getAttribute("SHARE_ID");

        if (authenticatedShare != null && authenticatedShare.toString().equals(share.id())) {
            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumIds", share.albumId()
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

    // This endpoint is for public access to a share link, so it does not filter by user.
    @PostMapping("/{shareId}/login")
    public ResponseEntity<?> loginShare(@PathVariable String shareId, @RequestBody Map<String, String> body, HttpServletRequest request) {
        ShareResponse share = shareService.getShareDetailsById(shareId);

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