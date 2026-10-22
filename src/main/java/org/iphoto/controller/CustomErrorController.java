package org.iphoto.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Custom error controller to handle application errors.
 * In particular, when a 401 (Unauthorized) occurs (e.g. user session has ended),
 * browser navigation requests are automatically forwarded/redirected to the login page (/login).
 */
@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        String requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        if (statusCode != null) {
            try {
                int status = Integer.parseInt(statusCode.toString());
                if (status == HttpStatus.UNAUTHORIZED.value()) {
                    // API callers receive 401 status
                    if (requestUri != null && requestUri.startsWith("/api/")) {
                        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                    }
                    // Browser requests automatically forward to the login page
                    return "redirect:/login";
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // For non-API routes, fallback to forwarding to index.html for SPA handling
        if (requestUri == null || !requestUri.startsWith("/api/")) {
            return "forward:/index.html";
        }

        int status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        if (statusCode != null) {
            try {
                status = Integer.parseInt(statusCode.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return ResponseEntity.status(status).build();
    }
}
