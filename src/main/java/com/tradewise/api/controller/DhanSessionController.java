package com.tradewise.api.controller;

import com.tradewise.model.dto.DhanLoginRequest;
import com.tradewise.security.DhanCredentialStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API controller for Dhan session management.
 */
@RestController
@RequestMapping("/api/dhan")
public class DhanSessionController {

    private final DhanCredentialStore dhanCredentialStore;

    public DhanSessionController(DhanCredentialStore dhanCredentialStore) {
        this.dhanCredentialStore = dhanCredentialStore;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody DhanLoginRequest request) {
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            throw new IllegalArgumentException("Access token is required");
        }

        dhanCredentialStore.update(request.accessToken(), request.clientId());

        return Map.of("message", "Dhan login successful");
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        dhanCredentialStore.clear();
        return Map.of("message", "Dhan logout successful");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleLoginError(Exception exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName()));
    }
}
