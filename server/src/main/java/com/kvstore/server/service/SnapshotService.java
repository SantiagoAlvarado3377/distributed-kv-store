package com.kvstore.server.service;

import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.storage.InMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Periodically snapshots the in-memory store to disk.
 * Runs every 30 seconds by default.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final InMemoryStore store;
    private final NodeConfig config;

    public SnapshotService(InMemoryStore store, NodeConfig config) {
        this.store = store;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${snapshot.interval.ms:30000}")
    public void takeSnapshot() {
        try {
            store.snapshot();
        } catch (IOException e) {
            log.error("Failed to write snapshot on node {}: {}", config.getNodeId(), e.getMessage(), e);
        }
    }
}
