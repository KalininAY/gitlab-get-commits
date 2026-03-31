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
     * Returns GitLab username (e.g. "65karpovrv") or author's name as fallback.
     */
    public String resolve(GitLabApi api, String cacheKey, String author) {
        if (author == null || author.isBlank()) return "";

        Map<String, String> hostCache = cache.computeIfAbsent(cacheKey, k -> new ConcurrentHashMap<>());

        return hostCache.computeIfAbsent(author, a -> {
            try {
                List<User> users = api.getUserApi().findUsers(a);
                // findUsers searches by username AND email; prefer exact email match
                for (User u : users) {
                    if (a.equalsIgnoreCase(u.getName())) {
                        return u.getUsername();
                    }
                }
                // No exact match — return first result's username if any
                if (!users.isEmpty())
                    return users.get(0).getUsername();
            } catch (Exception ex) {
                System.err.println("UserResolver: failed to resolve '" + a + "': " + ex.getMessage());
            }
            // Fallback
            return author;
        });
    }

    public void clearCache(String cacheKey) {
        cache.remove(cacheKey);
    }
}
