package com.example.gitlabcommits;

import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves author email -> GitLab username.
 * Results are cached per host+token pair.
 * Falls back to the email itself if no matching user is found.
 */
public class UserResolver {

    // cache key: "host|token" -> email -> username
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * Synchronous lookup — call from a background thread only.
     * Returns GitLab username (e.g. "65karpovrv") or the original email as fallback.
     */
    public String resolve(GitLabApi api, String cacheKey, String email) {
        if (email == null || email.isBlank()) return "";

        Map<String, String> hostCache = cache.computeIfAbsent(cacheKey, k -> new ConcurrentHashMap<>());

        return hostCache.computeIfAbsent(email, e -> {
            try {
                List<User> users = api.getUserApi().findUsers(e);
                // findUsers searches by username AND email; prefer exact email match
                for (User u : users) {
                    if (e.equalsIgnoreCase(u.getEmail())) return u.getUsername();
                }
                // No exact match — return first result's username if any
                if (!users.isEmpty()) return users.get(0).getUsername();
            } catch (Exception ex) {
                System.err.println("UserResolver: failed to resolve '" + e + "': " + ex.getMessage());
            }
            // Fallback: strip domain from email to get something readable (e.g. "jsmith")
            int at = e.indexOf('@');
            return at > 0 ? e.substring(0, at) : e;
        });
    }

    public void clearCache(String cacheKey) {
        cache.remove(cacheKey);
    }
}
