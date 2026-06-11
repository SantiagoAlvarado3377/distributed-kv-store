package com.kvstore.server.service;

import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.hashing.ConsistentHashRing;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Handles routing and replication of writes/deletes to peer nodes.
 *
 * <p>All write requests entering the cluster are forwarded to the primary node
 * (node-1 by default). The primary then replicates to the next
 * {@code replicationFactor - 1} nodes on the consistent-hash ring.
 */
@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final NodeConfig config;
    private final ConsistentHashRing hashRing;
    private final RestClient restClient;

    public ReplicationService(NodeConfig config, ConsistentHashRing hashRing) {
        this.config = config;
        this.hashRing = hashRing;
        this.restClient = RestClient.create();
    }

    @PostConstruct
    public void initRing() {
        // Register all known peers on the ring
        for (String peer : config.getPeerNodes()) {
            hashRing.addNode(peer);
            log.info("Added peer {} to hash ring", peer);
        }
        // Ensure self is also on the ring
        String self = selfAddress();
        if (!config.getPeerNodes().contains(self)) {
            hashRing.addNode(self);
            log.info("Added self {} to hash ring", self);
        }
    }

    /**
     * Replicate a PUT operation to all replica nodes for the given key
     * (excluding the current node, which has already applied the write).
     */
    public void replicatePut(String key, String value) {
        List<String> replicas = hashRing.getReplicaNodes(key, config.getReplicationFactor());
        String self = selfAddress();

        for (String replica : replicas) {
            if (replica.equals(self)) continue;
            try {
                restClient.put()
                        .uri("http://" + replica + "/internal/replicate/{key}", key)
                        .body(Map.of("value", value))
                        .retrieve()
                        .toBodilessEntity();
                log.debug("Replicated PUT {} to {}", key, replica);
            } catch (RestClientException e) {
                log.warn("Failed to replicate PUT {} to {}: {}", key, replica, e.getMessage());
            }
        }
    }

    /**
     * Replicate a DELETE operation to all replica nodes for the given key.
     */
    public void replicateDelete(String key) {
        List<String> replicas = hashRing.getReplicaNodes(key, config.getReplicationFactor());
        String self = selfAddress();

        for (String replica : replicas) {
            if (replica.equals(self)) continue;
            try {
                restClient.delete()
                        .uri("http://" + replica + "/internal/replicate/{key}", key)
                        .retrieve()
                        .toBodilessEntity();
                log.debug("Replicated DELETE {} to {}", key, replica);
            } catch (RestClientException e) {
                log.warn("Failed to replicate DELETE {} to {}: {}", key, replica, e.getMessage());
            }
        }
    }

    /**
     * Returns the address string for this node as it appears in the ring.
     * Format: {@code <nodeId>:<port>}
     */
    public String selfAddress() {
        return config.getNodeId() + ":" + config.getNodePort();
    }

    /**
     * Returns the HTTP base URL of the primary node.
     */
    public String primaryBaseUrl() {
        return "http://" + config.getPrimaryHost() + ":" + getPrimaryPort();
    }

    /**
     * Forward a PUT request to the primary node.
     */
    public void forwardPut(String key, String value) {
        restClient.put()
                .uri(primaryBaseUrl() + "/keys/{key}", key)
                .body(Map.of("value", value))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Forward a DELETE request to the primary node.
     */
    public void forwardDelete(String key) {
        restClient.delete()
                .uri(primaryBaseUrl() + "/keys/{key}", key)
                .retrieve()
                .toBodilessEntity();
    }

    // --- helpers ---

    private int getPrimaryPort() {
        // Determine the primary's port from the peer node list by matching the primary host
        String primaryHost = config.getPrimaryHost();
        for (String peer : config.getPeerNodes()) {
            String[] parts = peer.split(":");
            if (parts.length == 2 && parts[0].equals(primaryHost)) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        // Default to 8081 (the conventional primary port)
        return 8081;
    }
}
