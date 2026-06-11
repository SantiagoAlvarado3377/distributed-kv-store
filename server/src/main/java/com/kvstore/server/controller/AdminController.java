package com.kvstore.server.controller;

import com.kvstore.server.service.KeyValueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin endpoints for failure simulation and recovery.
 *
 * <pre>
 * POST /admin/simulate-failure  — node stops responding to key requests
 * POST /admin/recover           — node resumes normal operation
 * </pre>
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final KeyValueService kvService;

    public AdminController(KeyValueService kvService) {
        this.kvService = kvService;
    }

    @PostMapping("/simulate-failure")
    public ResponseEntity<?> simulateFailure() {
        kvService.simulateFailure();
        return ResponseEntity.ok(Map.of("status", "failure-simulated"));
    }

    @PostMapping("/recover")
    public ResponseEntity<?> recover() {
        kvService.recover();
        return ResponseEntity.ok(Map.of("status", "recovered"));
    }
}
