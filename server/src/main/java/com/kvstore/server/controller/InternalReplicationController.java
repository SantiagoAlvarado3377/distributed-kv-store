package com.kvstore.server.controller;

import com.kvstore.server.model.PutRequest;
import com.kvstore.server.service.KeyValueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * Internal endpoints used for inter-node replication.
 * These are called by the primary when replicating a write to replicas.
 *
 * <pre>
 * PUT    /internal/replicate/{key}   — apply a replicated PUT
 * DELETE /internal/replicate/{key}   — apply a replicated DELETE
 * </pre>
 */
@RestController
@RequestMapping("/internal")
public class InternalReplicationController {

    private final KeyValueService kvService;

    public InternalReplicationController(KeyValueService kvService) {
        this.kvService = kvService;
    }

    @PutMapping("/replicate/{key}")
    public ResponseEntity<?> replicatePut(@PathVariable String key,
                                          @RequestBody PutRequest request) {
        try {
            kvService.applyReplicatedPut(key, request.getValue());
            return ResponseEntity.ok(Map.of("status", "replicated"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Replication failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/replicate/{key}")
    public ResponseEntity<?> replicateDelete(@PathVariable String key) {
        try {
            kvService.applyReplicatedDelete(key);
            return ResponseEntity.ok(Map.of("status", "replicated"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Replication failed: " + e.getMessage()));
        }
    }
}
