package com.kvstore.server.model;

/**
 * Request body for PUT /keys/{key}
 */
public class PutRequest {
    private String value;

    public PutRequest() {}

    public PutRequest(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
