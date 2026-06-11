package com.kvstore.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for this node, driven by environment variables.
 */
@Component
public class NodeConfig {

    @Value("${NODE_ID:node-1}")
    private String nodeId;

    @Value("${NODE_PORT:8081}")
    private int nodePort;

    @Value("${PRIMARY_HOST:node-1}")
    private String primaryHost;

    @Value("${PEER_NODES:}")
    private String peerNodesRaw;

    @Value("${REPLICATION_FACTOR:3}")
    private int replicationFactor;

    @Value("${DATA_DIR:/data}")
    private String dataDir;

    public String getNodeId() {
        return nodeId;
    }

    public int getNodePort() {
        return nodePort;
    }

    public String getPrimaryHost() {
        return primaryHost;
    }

    public List<String> getPeerNodes() {
        if (peerNodesRaw == null || peerNodesRaw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(peerNodesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public String getDataDir() {
        return dataDir;
    }

    public boolean isPrimary() {
        // node is primary if its NODE_ID matches the PRIMARY_HOST or the PRIMARY_HOST equals "node-1"
        // and this is node-1, etc. We match by nodeId == primaryHost for simplicity.
        return nodeId.equals(primaryHost);
    }
}
