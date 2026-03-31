package com.example.gitlabcommits;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * JSON-based config. Structure:
 * {
 *   "tokens": ["tok1", "tok2"],
 *   "tokenData": {
 *     "tok1": {
 *       "host":       ["http://10.1.5.6"],
 *       "segment":    ["Полигон"],
 *       "since":      ["2025-10-01T00:00:01Z"],
 *       "until":      ["2025-11-01T00:00:01Z"],
 *       "projectIds": ["153,114", "118"]
 *     }
 *   }
 * }
 */
public class AppConfig {

    private static final String CONFIG_FILE =
            System.getProperty("user.home") + "/.gitlab-get-commits.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ObjectNode root;

    public AppConfig() {
        load();
    }

    private void load() {
        Path path = Paths.get(CONFIG_FILE);
        if (Files.exists(path)) {
            try {
                root = (ObjectNode) mapper.readTree(path.toFile());
            } catch (Exception e) {
                System.err.println("Cannot load config: " + e.getMessage());
                root = mapper.createObjectNode();
            }
        } else {
            root = mapper.createObjectNode();
        }
        if (!root.has("tokens"))    root.set("tokens",    mapper.createArrayNode());
        if (!root.has("tokenData")) root.set("tokenData", mapper.createObjectNode());
    }

    public void save() {
        try {
            mapper.writeValue(new File(CONFIG_FILE), root);
        } catch (Exception e) {
            System.err.println("Cannot save config: " + e.getMessage());
        }
    }

    // --- Tokens ---

    public List<String> getTokens() {
        List<String> list = new ArrayList<>();
        root.get("tokens").forEach(n -> list.add(n.asText()));
        return list;
    }

    public void addToken(String token) {
        if (token == null || token.isEmpty()) return;
        ArrayNode arr = (ArrayNode) root.get("tokens");
        // move to front if already exists
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).asText().equals(token)) {
                arr.remove(i);
                break;
            }
        }
        ArrayNode newArr = mapper.createArrayNode();
        newArr.add(token);
        arr.forEach(newArr::add);
        root.set("tokens", newArr);
    }

    // --- Per-token data ---

    private ObjectNode tokenNode(String token) {
        ObjectNode td = (ObjectNode) root.get("tokenData");
        if (!td.has(token)) td.set(token, mapper.createObjectNode());
        return (ObjectNode) td.get(token);
    }

    /** Returns list of remembered values for a combo field (first = last used) */
    public List<String> getList(String token, String key) {
        List<String> list = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode n = tokenNode(token).get(key);
        if (n != null && n.isArray()) n.forEach(e -> list.add(e.asText()));
        return list;
    }

    /** Saves the list, current value goes to position 0 */
    public void setList(String token, String key, String currentValue, List<String> allValues) {
        ArrayNode arr = mapper.createArrayNode();
        arr.add(currentValue);
        for (String v : allValues) {
            if (!v.equals(currentValue)) arr.add(v);
        }
        tokenNode(token).set(key, arr);
    }
}
