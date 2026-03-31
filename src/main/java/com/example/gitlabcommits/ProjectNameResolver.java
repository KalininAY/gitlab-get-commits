package com.example.gitlabcommits;

import org.gitlab4j.api.GitLabApi;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Resolves GitLab project IDs to names. Results are cached per host+token pair.
 * Thread-safe; uses a single shared executor.
 */
public class ProjectNameResolver {

    // cache key: "host|token" -> projectId -> name
    private final Map<String, Map<Integer, String>> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "resolver");
        t.setDaemon(true);
        return t;
    });

    private static GitLabApi buildApi(String host, String token) {
        GitLabApi api = new GitLabApi(host, token);
        api.setIgnoreCertificateErrors(true);
        return api;
    }

    /**
     * Resolves names for all given IDs.
     * Already-cached IDs are returned immediately; unknown IDs are fetched async.
     */
    public CompletableFuture<Map<Integer, String>> resolve(String host, String token, List<Integer> projectIds) {
        String cacheKey = host + "|" + token;
        Map<Integer, String> hostCache = cache.computeIfAbsent(cacheKey, k -> new ConcurrentHashMap<>());

        List<Integer> missing = projectIds.stream()
                .filter(id -> !hostCache.containsKey(id))
                .collect(Collectors.toList());

        if (missing.isEmpty()) {
            Map<Integer, String> result = new LinkedHashMap<>();
            for (int id : projectIds) result.put(id, hostCache.getOrDefault(id, "#" + id));
            return CompletableFuture.completedFuture(result);
        }

        List<CompletableFuture<Void>> fetches = missing.stream()
                .map(id -> CompletableFuture.runAsync(() -> {
                    try {
                        GitLabApi api = buildApi(host, token);
                        String name = api.getProjectApi().getProject(id).getName();
                        hostCache.put(id, name);
                    } catch (Exception e) {
                        hostCache.put(id, "#" + id + " (?)");
                        System.err.println("ProjectNameResolver: project " + id + " error: " + e.getMessage());
                    }
                }, executor))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(fetches.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<Integer, String> result = new LinkedHashMap<>();
                    for (int id : projectIds) result.put(id, hostCache.getOrDefault(id, "#" + id));
                    return result;
                });
    }

    /** Clears the cache for a specific host+token pair */
    public void clearCache(String host, String token) {
        cache.remove(host + "|" + token);
    }
}
