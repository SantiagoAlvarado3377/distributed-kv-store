package com.kvstore.server.service;

import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.storage.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core service for GET/PUT/DELETE operations.
 *
 * <p>If this node is the primary it applies writes directly and then
 * replicates. If it is a replica it forwards writes to the primary.
 *
 * <p>Supports a "simulated failure" mode where key operations are rejected
 * (HTTP 503) to facilitate demo testing.
 */
@Service
public class KeyValueService {

    private static final Logger log = LoggerFactory.getLogger(KeyValueService.class);

    private final InMemoryStore store;
    private final NodeConfig config;
    private final ReplicationService replicationService;

    /** When true, this node pretends to be unavailable for key requests. */
    private final AtomicBoolean failed = new AtomicBoolean(false);

    public KeyValueService(InMemoryStore store,
                           NodeConfig config,
                           ReplicationService replicationService) {
        this.store = store;
        this.config = config;
        this.replicationService = replicationService;
    }

    // --- failure simulation ---

    public void simulateFailure() {
        failed.set(true);
        log.warn("Node {} is now simulating failure", config.getNodeId());
    }

    public void recover() {
        failed.set(false);
        log.info("Node {} has recovered", config.getNodeId());
    }

    public boolean isFailed() {
        return failed.get();
    }

    // --- key operations ---

    /**
     * Get a value by key.
     *
     * @return value string, or {@code null} if not found
     * @throws NodeFailedException if the node is in simulated-failure mode
     */
    public String get(String key) {
        checkNotFailed();
        String value = store.get(key);
        log.debug("GET {} = {}", key, value);
        return value;
    }

    /**
     * Put a key-value pair.
     *
     * <p>If this node is the primary, writes locally and replicates.
     * Otherwise, forwards the write to the primary.
     *
     * @throws NodeFailedException   if the node is in simulated-failure mode
     * @throws PrimaryDownException  if this node is not the primary and the forward fails
     * @throws IOException           on WAL / storage errors
     */
    public void put(String key, String value) throws IOException {
        checkNotFailed();
        if (config.isPrimary()) {
            store.put(key, value);
            log.info("PUT {} (primary)", key);
            replicationService.replicatePut(key, value);
        } else {
            log.info("PUT {} (forwarding to primary)", key);
            try {
                replicationService.forwardPut(key, value);
            } catch (RestClientException e) {
                throw new PrimaryDownException("Primary node is unreachable: " + e.getMessage());
            }
        }
    }

    /**
     * Delete a key.
     *
     * @return {@code true} if the key existed
     * @throws NodeFailedException   if the node is in simulated-failure mode
     * @throws PrimaryDownException  if this node is not the primary and the forward fails
     * @throws IOException           on WAL / storage errors
     */
    public boolean delete(String key) throws IOException {
        checkNotFailed();
        if (config.isPrimary()) {
            boolean removed = store.delete(key);
            log.info("DELETE {} = {} (primary)", key, removed);
            if (removed) {
                replicationService.replicateDelete(key);
            }
            return removed;
        } else {
            log.info("DELETE {} (forwarding to primary)", key);
            try {
                replicationService.forwardDelete(key);
                return true; // assume success
            } catch (RestClientException e) {
                throw new PrimaryDownException("Primary node is unreachable: " + e.getMessage());
            }
        }
    }

    /**
     * Apply a replicated write received from the primary without re-forwarding.
     */
    public void applyReplicatedPut(String key, String value) throws IOException {
        store.put(key, value);
        log.debug("Applied replicated PUT {}", key);
    }

    /**
     * Apply a replicated delete received from the primary without re-forwarding.
     */
    public void applyReplicatedDelete(String key) throws IOException {
        store.delete(key);
        log.debug("Applied replicated DELETE {}", key);
    }

    // --- helpers ---

    private void checkNotFailed() {
        if (failed.get()) {
            throw new NodeFailedException("Node " + config.getNodeId() + " is simulating failure");
        }
    }

    // --- exception types ---

    public static class NodeFailedException extends RuntimeException {
        public NodeFailedException(String message) { super(message); }
    }

    public static class PrimaryDownException extends RuntimeException {
        public PrimaryDownException(String message) { super(message); }
    }
}
