package com.example.gitlabcommits;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class AppConfig {

    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.gitlab-get-commits.properties";

    private final Properties props = new Properties();

    public AppConfig() {
        load();
    }

    private void load() {
        Path path = Path.of(CONFIG_FILE);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Cannot load config: " + e.getMessage());
            }
        }
    }

    public void save() {
        try (OutputStream out = Files.newOutputStream(Path.of(CONFIG_FILE))) {
            props.store(out, "GitLab Get Commits Config");
        } catch (IOException e) {
            System.err.println("Cannot save config: " + e.getMessage());
        }
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }
}
