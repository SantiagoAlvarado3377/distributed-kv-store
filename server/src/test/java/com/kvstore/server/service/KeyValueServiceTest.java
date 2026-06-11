package com.kvstore.server.service;

import com.kvstore.server.config.NodeConfig;
import com.kvstore.server.storage.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeyValueServiceTest {

    @Mock
    private InMemoryStore store;

    @Mock
    private NodeConfig config;

    @Mock
    private ReplicationService replicationService;

    private KeyValueService kvService;

    @BeforeEach
    void setUp() {
        kvService = new KeyValueService(store, config, replicationService);
    }

    @Test
    void getReturnsValueFromStore() {
        when(store.get("key")).thenReturn("value");
        assertEquals("value", kvService.get("key"));
    }

    @Test
    void getReturnsNullForMissingKey() {
        when(store.get("missing")).thenReturn(null);
        assertNull(kvService.get("missing"));
    }

    @Test
    void putOnPrimaryWritesToStoreAndReplicates() throws IOException {
        when(config.isPrimary()).thenReturn(true);

        kvService.put("k", "v");

        verify(store).put("k", "v");
        verify(replicationService).replicatePut("k", "v");
    }

    @Test
    void putOnReplicaForwardsToPrimary() throws IOException {
        when(config.isPrimary()).thenReturn(false);

        kvService.put("k", "v");

        verify(store, never()).put(anyString(), anyString());
        verify(replicationService).forwardPut("k", "v");
    }

    @Test
    void deleteOnPrimaryRemovesFromStoreAndReplicates() throws IOException {
        when(config.isPrimary()).thenReturn(true);
        when(store.delete("k")).thenReturn(true);

        boolean result = kvService.delete("k");

        assertTrue(result);
        verify(replicationService).replicateDelete("k");
    }

    @Test
    void deleteOnPrimaryKeyNotFoundDoesNotReplicate() throws IOException {
        when(config.isPrimary()).thenReturn(true);
        when(store.delete("missing")).thenReturn(false);

        boolean result = kvService.delete("missing");

        assertFalse(result);
        verify(replicationService, never()).replicateDelete(anyString());
    }

    @Test
    void simulateFailureCausesGetToThrow() {
        kvService.simulateFailure();
        assertThrows(KeyValueService.NodeFailedException.class, () -> kvService.get("k"));
    }

    @Test
    void recoverAllowsOperationsAfterFailure() {
        when(store.get("k")).thenReturn("v");

        kvService.simulateFailure();
        kvService.recover();

        assertDoesNotThrow(() -> kvService.get("k"));
    }

    @Test
    void applyReplicatedPutWritesToStore() throws IOException {
        kvService.applyReplicatedPut("k", "v");
        verify(store).put("k", "v");
    }

    @Test
    void applyReplicatedDeleteRemovesFromStore() throws IOException {
        kvService.applyReplicatedDelete("k");
        verify(store).delete("k");
    }
}
