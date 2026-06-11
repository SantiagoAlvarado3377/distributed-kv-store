package com.kvstore.server.hashing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(5);
    }

    @Test
    void emptyRingReturnsNull() {
        assertNull(ring.getPrimaryNode("any-key"));
        assertTrue(ring.getReplicaNodes("any-key", 3).isEmpty());
    }

    @Test
    void singleNodeAlwaysGetsAllKeys() {
        ring.addNode("node-1:8081");
        assertEquals("node-1:8081", ring.getPrimaryNode("foo"));
        assertEquals("node-1:8081", ring.getPrimaryNode("bar"));
        assertEquals("node-1:8081", ring.getPrimaryNode("baz"));
    }

    @Test
    void virtualNodeCountIsCorrect() {
        ring.addNode("node-1:8081");
        assertEquals(5, ring.size());  // 5 virtual nodes per physical node
    }

    @Test
    void removedNodeIsNoLongerPrimary() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");
        ring.removeNode("node-1:8081");

        // All keys should now route to node-2
        assertEquals("node-2:8082", ring.getPrimaryNode("foo"));
        assertEquals("node-2:8082", ring.getPrimaryNode("bar"));
    }

    @Test
    void replicaNodesReturnsDistinctNodes() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");
        ring.addNode("node-3:8083");

        List<String> replicas = ring.getReplicaNodes("my-key", 3);
        assertEquals(3, replicas.size());
        // All replicas should be distinct
        assertEquals(3, Set.copyOf(replicas).size());
    }

    @Test
    void replicasNeverExceedAvailableNodes() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");

        // Request 3 replicas, but only 2 physical nodes exist
        List<String> replicas = ring.getReplicaNodes("my-key", 3);
        assertEquals(2, replicas.size());
    }

    @Test
    void getAllNodesReturnsAllPhysicalNodes() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");
        ring.addNode("node-3:8083");

        Set<String> all = ring.getAllNodes();
        assertEquals(3, all.size());
        assertTrue(all.contains("node-1:8081"));
        assertTrue(all.contains("node-2:8082"));
        assertTrue(all.contains("node-3:8083"));
    }

    @Test
    void deterministicRouting() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");
        ring.addNode("node-3:8083");

        String first = ring.getPrimaryNode("stable-key");
        // Same key should always route to the same node
        for (int i = 0; i < 100; i++) {
            assertEquals(first, ring.getPrimaryNode("stable-key"));
        }
    }

    @Test
    void primaryNodeIsFirstReplica() {
        ring.addNode("node-1:8081");
        ring.addNode("node-2:8082");
        ring.addNode("node-3:8083");

        String primary = ring.getPrimaryNode("some-key");
        List<String> replicas = ring.getReplicaNodes("some-key", 3);
        assertEquals(primary, replicas.get(0),
                "Primary node must be the first entry in the replica list");
    }
}
