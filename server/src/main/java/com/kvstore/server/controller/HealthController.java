package com.kvstore.server.controller;

import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.service.KeyValueService;
import com.kvstore.server.storage.InMemoryStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check endpoint.
 *
 * <pre>GET /health</pre>
 *
 * Returns the node's status, whether it is the primary, and the current
 * number of keys stored locally.
 */
@RestController
public class HealthController {

    private final NodeConfig config;
    private final KeyValueService kvService;
    private final InMemoryStore store;

    public HealthController(NodeConfig config, KeyValueService kvService, InMemoryStore store) {
        this.config = config;
        this.kvService = kvService;
        this.store = store;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        String status = kvService.isFailed() ? "simulated-failure" : "UP";
        return ResponseEntity.ok(Map.of(
                "nodeId", config.getNodeId(),
                "status", status,
                "isPrimary", config.isPrimary(),
                "keyCount", store.keySet().size()
        ));
    }
}
