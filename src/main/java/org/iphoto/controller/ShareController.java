package org.iphoto.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.*;
import org.iphoto.model.request.ShareRequest;
import org.iphoto.model.request.ShareUpdateRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.ShareService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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

    // Lets the owner remove ("password": null/blank) or replace the password on a share they
    // created. Ownership is enforced by ShareService/ShareRepository's @MainSpaceLimited lookup.
    @PutMapping("/{id}")
    public ResponseEntity<ShareResponse> updateShare(@PathVariable UUID id, @RequestBody ShareUpdateRequest request) {
        UserEntity currentUser = getCurrentUser();
        try {
            ShareResponse share = shareService.updateSharePassword(currentUser, id, request.password());
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

    // This endpoint is for public/guest access to a share link, so it does not filter by user.
    // Share links are addressed as /share/{userMd5}/{name} — userMd5 identifies the owning user
    // without exposing their real UUID, name is unique per user (see ShareEntity.userMd5).
    @GetMapping("/{userMd5}/{name}")
    public ResponseEntity<Map<String, Serializable>> getShareDetailsByName(
            @PathVariable String userMd5, @PathVariable String name, HttpServletRequest request) {
        ShareResponse share;
        try {
            share = shareService.getShareDetails(userMd5, name);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        if (share.expired()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        // non protégé : grant guest access immediately, no password step needed. A session is
        // established here too (not just after a password login) — otherwise every subsequent
        // /api/albums/share/** and /api/photos/**/(thumbnail|original|stream) call would be
        // unauthenticated and rejected. Skipped when the caller already has a real logged-in
        // session (e.g. the owner previewing their own link in the same browser) — otherwise
        // this would silently replace their account session with a guest ROLE_SHARE one.
        if (!share.isPasswordProtected()) {
            if (isGuestOrAnonymous(SecurityContextHolder.getContext().getAuthentication())) {
                establishShareSession(request, share.id());
            }
            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumId", share.albumId().toString()
                    )
            );
        }

        // déjà authentifié pour CE partage précis
        HttpSession existingSession = request.getSession(false);
        Object authenticatedShare = existingSession != null ? existingSession.getAttribute("SHARE_ID") : null;

        if (authenticatedShare != null && authenticatedShare.toString().equals(share.id())) {
            return ResponseEntity.ok(
                    Map.of(
                            "protected", false,
                            "albumId", share.albumId().toString()
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
        ShareResponse share;
        try {
            share = shareService.getShareDetailsById(shareId);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        if (share.expired()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        boolean ok = encoder.matches(
                body.get("password"),
                shareService.getSharePassword(shareId)
        );

        if (!ok) {
            return ResponseEntity.status(401).build();
        }

        if (isGuestOrAnonymous(SecurityContextHolder.getContext().getAuthentication())) {
            establishShareSession(request, shareId);
        }

        return ResponseEntity.ok(share);
    }

    // A real logged-in user (not a guest, not anonymous) keeps their own session — establishing
    // a share session would silently log them out of their account. Re-establishing/switching
    // between two share sessions (ROLE_SHARE -> a different ROLE_SHARE) is fine.
    private boolean isGuestOrAnonymous(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return true;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SHARE".equals(authority.getAuthority()));
    }

    private void establishShareSession(HttpServletRequest request, String shareId) {
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
    }
}
