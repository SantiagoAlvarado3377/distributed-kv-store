package com.kvstore.server.controller;

import com.kvstore.server.model.PutRequest;
import com.kvstore.server.service.KeyValueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * REST endpoints for key-value operations.
 *
 * <pre>
 * PUT    /keys/{key}   { "value": "..." }
 * GET    /keys/{key}
 * DELETE /keys/{key}
 * </pre>
 */
@RestController
@RequestMapping("/keys")
public class KeyValueController {

    private final KeyValueService kvService;

    public KeyValueController(KeyValueService kvService) {
        this.kvService = kvService;
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> put(@PathVariable String key, @RequestBody PutRequest request) {
        try {
            if (request.getValue() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing 'value' field"));
            }
            kvService.put(key, request.getValue());
            return ResponseEntity.ok(Map.of("status", "ok", "key", key));
        } catch (KeyValueService.NodeFailedException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (KeyValueService.PrimaryDownException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Storage error: " + e.getMessage()));
        }
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        try {
            String value = kvService.get(key);
            if (value == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("key", key, "value", value));
        } catch (KeyValueService.NodeFailedException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        try {
            boolean removed = kvService.delete(key);
            if (!removed) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("status", "deleted", "key", key));
        } catch (KeyValueService.NodeFailedException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (KeyValueService.PrimaryDownException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Storage error: " + e.getMessage()));
        }
    }
}
