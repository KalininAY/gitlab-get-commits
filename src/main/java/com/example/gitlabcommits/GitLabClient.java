package com.example.gitlabcommits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

public class GitLabClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String host;
    private final String token;
    private final int projectId;
    private final String since;
    private final String until;
    private final String segment;
    private final String name;

    public GitLabClient(String host, String token, int projectId,
                        String since, String until, String segment, String name) {
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.token = token;
        this.projectId = projectId;
        this.since = since;
        this.until = until;
        this.segment = segment;
        this.name = name;
    }

    public CompletableFuture<List<CommitDetail>> fetchAllCommits() {
        Map<String, CommitDetail> uniqueCommits = new ConcurrentHashMap<>();

        // Branch 1: commits by date range — branch resolved via refs API
        CompletableFuture<Void> branch1 = fetchJson(
                "/api/v4/projects/" + projectId + "/repository/commits?per_page=100&since=" + since + "&until=" + until + "&all=true"
        ).thenCompose(listNode -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            if (listNode.isArray()) {
                for (JsonNode commit : listNode) {
                    String id = commit.path("id").asText();
                    if (!id.isEmpty()) {
                        futures.add(
                            fetchBranch(id).thenCompose(branch ->
                                fetchCommitDetail(id, branch).thenAccept(detail -> {
                                    if (detail != null) uniqueCommits.put(detail.id(), detail);
                                })
                            )
                        );
                    }
                }
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        });

        // Branch 2: commits from merge requests — branch = MR source_branch
        CompletableFuture<Void> branch2 = fetchJson(
                "/api/v4/projects/" + projectId + "/merge_requests?per_page=100"
        ).thenCompose(mrListNode -> {
            List<CompletableFuture<Void>> mrFutures = new ArrayList<>();
            if (mrListNode.isArray()) {
                for (JsonNode mr : mrListNode) {
                    int iid = mr.path("iid").asInt();
                    String mrBranch = mr.path("source_branch").asText("");
                    if (iid > 0) {
                        mrFutures.add(
                            fetchJson("/api/v4/projects/" + projectId + "/merge_requests/" + iid + "/commits?per_page=100")
                            .thenCompose(commitList -> {
                                List<CompletableFuture<Void>> cFutures = new ArrayList<>();
                                if (commitList.isArray()) {
                                    for (JsonNode commit : commitList) {
                                        String cid = commit.path("id").asText();
                                        if (!cid.isEmpty()) {
                                            cFutures.add(fetchCommitDetail(cid, mrBranch).thenAccept(detail -> {
                                                if (detail != null) uniqueCommits.put(detail.id(), detail);
                                            }));
                                        }
                                    }
                                }
                                return CompletableFuture.allOf(cFutures.toArray(new CompletableFuture[0]));
                            })
                        );
                    }
                }
            }
            return CompletableFuture.allOf(mrFutures.toArray(new CompletableFuture[0]));
        });

        return CompletableFuture.allOf(branch1, branch2)
                .thenApply(v -> new ArrayList<>(uniqueCommits.values()));
    }

    /** Resolves branch for a direct commit via Commit Refs API. */
    private CompletableFuture<String> fetchBranch(String sha) {
        return fetchJson("/api/v4/projects/" + projectId + "/repository/commits/" + sha + "/refs?type=branch")
                .thenApply(refs -> {
                    if (!refs.isArray() || refs.isEmpty()) return "";
                    // Prefer non-default branches
                    for (JsonNode ref : refs) {
                        String n = ref.path("name").asText("");
                        if (!n.equals("main") && !n.equals("master") && !n.equals("develop")) return n;
                    }
                    return refs.get(0).path("name").asText("");
                })
                .exceptionally(ex -> "");
    }

    private CompletableFuture<CommitDetail> fetchCommitDetail(String id, String branch) {
        return fetchJson("/api/v4/projects/" + projectId + "/repository/commits/" + id)
                .thenApply(node -> {
                    if (node.isMissingNode() || node.isNull()) return null;
                    String committedDate = node.path("committed_date").asText("");
                    if (!isTimeBound(committedDate)) return null;
                    String message = cleanMessage(node.path("message").asText(""));
                    int additions = node.path("stats").path("additions").asInt(0);
                    int deletions = node.path("stats").path("deletions").asInt(0);
                    String committer = node.path("committer_name").asText("");
                    return new CommitDetail(id, committedDate, message, committer,
                            additions, deletions, segment, name, branch);
                });
    }

    private boolean isTimeBound(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return false;
        try {
            java.time.Instant instant = java.time.Instant.parse(dateStr);
            java.time.Instant sinceInstant = java.time.Instant.parse(since);
            java.time.Instant untilInstant = java.time.Instant.parse(until);
            return instant.isAfter(sinceInstant) && instant.isBefore(untilInstant);
        } catch (Exception e) {
            return false;
        }
    }

    private String cleanMessage(String msg) {
        return msg
                .replaceAll("Merge branch '[^']+' into '[^']+'", "")
                .replaceAll("\\n+See merge request .+", "")
                .trim();
    }

    private CompletableFuture<JsonNode> fetchJson(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + endpoint))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        return mapper.readTree(resp.body());
                    } catch (Exception e) {
                        return mapper.createObjectNode();
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("Request failed for " + endpoint + ": " + ex.getMessage());
                    return mapper.createObjectNode();
                });
    }
}
