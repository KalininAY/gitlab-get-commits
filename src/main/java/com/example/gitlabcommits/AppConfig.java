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
 *   "hosts": ["http://...", "https://..."],
 *   "hostData": {
 *     "http://10.1.5.6": {
 *       "token":      ["tok1", "tok2"],
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
        if (!root.has("hosts"))    root.set("hosts",    mapper.createArrayNode());
        if (!root.has("hostData")) root.set("hostData", mapper.createObjectNode());
    }

    public void save() {
        try {
            mapper.writeValue(new File(CONFIG_FILE), root);
        } catch (Exception e) {
            System.err.println("Cannot save config: " + e.getMessage());
        }
    }

    // --- Hosts ---

    public List<String> getHosts() {
        List<String> list = new ArrayList<>();
        root.get("hosts").forEach(n -> list.add(n.asText()));
        return list;
    }

    public void addHost(String host) {
        ArrayNode arr = (ArrayNode) root.get("hosts");
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            if (n.asText().equals(host)) return;
        }
        arr.add(host);
    }

    // --- Per-host data ---

    private ObjectNode hostNode(String host) {
        ObjectNode hd = (ObjectNode) root.get("hostData");
        if (!hd.has(host)) hd.set(host, mapper.createObjectNode());
        return (ObjectNode) hd.get(host);
    }

    /** Returns list of remembered values for a combo field (first = last used) */
    public List<String> getList(String host, String key) {
        List<String> list = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode n = hostNode(host).get(key);
        if (n != null && n.isArray()) n.forEach(e -> list.add(e.asText()));
        return list;
    }

    /** Saves the list, current value goes to position 0 */
    public void setList(String host, String key, String currentValue, List<String> allValues) {
        ArrayNode arr = mapper.createArrayNode();
        arr.add(currentValue);
        for (String v : allValues) {
            if (!v.equals(currentValue)) arr.add(v);
        }
        hostNode(host).set(key, arr);
    }
}
