package com.kvstore.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Simple CLI client for the distributed key-value store.
 *
 * <p>Usage:
 * <pre>
 * java -jar client.jar &lt;base-url&gt; put &lt;key&gt; &lt;value&gt;
 * java -jar client.jar &lt;base-url&gt; get &lt;key&gt;
 * java -jar client.jar &lt;base-url&gt; delete &lt;key&gt;
 * java -jar client.jar &lt;base-url&gt; health
 * </pre>
 *
 * <p>Example:
 * <pre>
 * java -jar client.jar http://localhost:8081 put greeting "hello world"
 * java -jar client.jar http://localhost:8081 get greeting
 * java -jar client.jar http://localhost:8081 delete greeting
 * java -jar client.jar http://localhost:8081 health
 * </pre>
 */
public class KVStoreClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public KVStoreClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String get(String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/keys/" + urlEncode(key)))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        checkStatus(response);
        JsonNode body = mapper.readTree(response.body());
        return body.path("value").asText();
    }

    public void put(String key, String value) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("value", value));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/keys/" + urlEncode(key)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
    }

    public boolean delete(String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/keys/" + urlEncode(key)))
                .DELETE()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return false;
        }
        checkStatus(response);
        return true;
    }

    public String health() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response);
        return response.body();
    }

    private void checkStatus(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Request failed with status " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    // --- CLI entry point ---

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String baseUrl = args[0];
        String command = args[1].toLowerCase();
        KVStoreClient client = new KVStoreClient(baseUrl);

        try {
            switch (command) {
                case "get" -> {
                    if (args.length < 3) { printUsage(); System.exit(1); }
                    String value = client.get(args[2]);
                    if (value == null) {
                        System.out.println("Key not found: " + args[2]);
                    } else {
                        System.out.println(args[2] + " = " + value);
                    }
                }
                case "put" -> {
                    if (args.length < 4) { printUsage(); System.exit(1); }
                    client.put(args[2], args[3]);
                    System.out.println("OK");
                }
                case "delete" -> {
                    if (args.length < 3) { printUsage(); System.exit(1); }
                    boolean removed = client.delete(args[2]);
                    System.out.println(removed ? "Deleted" : "Key not found");
                }
                case "health" -> {
                    String status = client.health();
                    System.out.println(status);
                }
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: kv-client <base-url> <command> [args...]");
        System.err.println("Commands:");
        System.err.println("  get    <key>");
        System.err.println("  put    <key> <value>");
        System.err.println("  delete <key>");
        System.err.println("  health");
    }
}
