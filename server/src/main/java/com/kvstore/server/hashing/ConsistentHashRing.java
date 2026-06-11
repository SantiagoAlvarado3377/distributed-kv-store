package com.kvstore.server.hashing;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Consistent hash ring implementation with virtual nodes.
 *
 * <p>Each physical node is represented by {@code virtualNodesPerNode} virtual nodes
 * spread around a 2^32 ring. Keys are routed to the first virtual node whose
 * position is &gt;= the key's hash (clockwise lookup).
 */
@Component
public class ConsistentHashRing {

    private static final int DEFAULT_VIRTUAL_NODES = 5;

    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodesPerNode;

    public ConsistentHashRing() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    public ConsistentHashRing(int virtualNodesPerNode) {
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    /**
     * Add a node to the ring.
     *
     * @param nodeId physical node identifier (e.g. "node-1:8081")
     */
    public synchronized void addNode(String nodeId) {
        for (int i = 0; i < virtualNodesPerNode; i++) {
            long hash = hash(nodeId + "#" + i);
            ring.put(hash, nodeId);
        }
    }

    /**
     * Remove a node from the ring.
     *
     * @param nodeId physical node identifier
     */
    public synchronized void removeNode(String nodeId) {
        for (int i = 0; i < virtualNodesPerNode; i++) {
            long hash = hash(nodeId + "#" + i);
            ring.remove(hash);
        }
    }

    /**
     * Return the primary node for the given key.
     *
     * @param key the lookup key
     * @return node identifier, or {@code null} if the ring is empty
     */
    public synchronized String getPrimaryNode(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        long hash = hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    /**
     * Return up to {@code count} distinct physical nodes responsible for the key,
     * starting from the primary and walking clockwise.
     *
     * @param key   the lookup key
     * @param count maximum number of replicas
     * @return ordered list of node identifiers (primary first)
     */
    public synchronized List<String> getReplicaNodes(String key, int count) {
        if (ring.isEmpty()) {
            return List.of();
        }
        long hash = hash(key);

        List<String> replicas = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Walk ring clockwise starting from the key's position
        NavigableMap<Long, String> tailMap = ring.tailMap(hash, true);
        for (String node : tailMap.values()) {
            if (seen.add(node) && replicas.size() < count) {
                replicas.add(node);
            }
            if (replicas.size() == count) break;
        }

        // Wrap around if needed
        if (replicas.size() < count) {
            for (String node : ring.values()) {
                if (seen.add(node) && replicas.size() < count) {
                    replicas.add(node);
                }
                if (replicas.size() == count) break;
            }
        }

        return Collections.unmodifiableList(replicas);
    }

    /**
     * Return all nodes currently on the ring (distinct physical nodes).
     */
    public synchronized Set<String> getAllNodes() {
        return new LinkedHashSet<>(ring.values());
    }

    /**
     * Return the total number of virtual node entries on the ring.
     */
    public synchronized int size() {
        return ring.size();
    }

    // --- hash function ---

    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // Take the first 4 bytes as a positive long
            long h = 0;
            for (int i = 0; i < 4; i++) {
                h = (h << 8) | (digest[i] & 0xFF);
            }
            return h & 0xFFFFFFFFL;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
