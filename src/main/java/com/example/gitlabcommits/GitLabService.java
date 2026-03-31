package com.example.gitlabcommits;

import org.gitlab4j.api.*;
import org.gitlab4j.api.models.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class GitLabService {

    private final GitLabApi api;
    private final String segment;
    private final String since;
    private final String until;
    private final Consumer<String> statusCallback;

    public GitLabService(String host, String token, String segment,
                         String since, String until, Consumer<String> statusCallback) {
        this.api = new GitLabApi(host, token);
        this.segment = segment;
        this.since = since;
        this.until = until;
        this.statusCallback = statusCallback;
    }

    /** Fetch commits for all given project IDs concurrently, deduplicated across projects */
    public CompletableFuture<List<CommitDetail>> fetchProjects(List<Integer> projectIds) {
        List<CompletableFuture<List<CommitDetail>>> futures = projectIds.stream()
                .map(this::fetchProject)
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, CommitDetail> unique = new LinkedHashMap<>();
                    futures.forEach(f -> f.join().forEach(c -> unique.put(c.id(), c)));
                    List<CommitDetail> result = new ArrayList<>(unique.values());
                    result.sort(Comparator.comparing(CommitDetail::committedDate));
                    return result;
                });
    }

    private CompletableFuture<List<CommitDetail>> fetchProject(int projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Project project = api.getProjectApi().getProject(projectId);
                String projectName = project.getName();

                statusCallback.accept("[" + projectName + "] загрузка...");

                Map<String, CommitDetail> unique = new ConcurrentHashMap<>();

                Date sinceDate = Date.from(Instant.parse(since));
                Date untilDate = Date.from(Instant.parse(until));

                CommitsApi commitsApi = api.getCommitsApi();

                // Branch 1: direct commits in date range, all branches, withStats=true
                // Signature: getCommits(Object, String ref, Date since, Date until, String path,
                //                       Boolean all, Boolean withStats, Boolean firstParent)
                List<Commit> directCommits = commitsApi.getCommits(
                        (Object) projectId, null, sinceDate, untilDate, null,
                        Boolean.TRUE, Boolean.TRUE, Boolean.FALSE
                );

                List<CompletableFuture<Void>> detailFutures = new ArrayList<>();

                for (Commit c : directCommits) {
                    final CommitDetail cd = toDetail(c, segment, projectName);
                    if (cd != null) {
                        unique.put(cd.id(), cd);
                    }
                }

                // Branch 2: commits from all merge requests
                // MR commits do NOT return stats — need individual getCommit call
                // Signature: getCommit(Object projectIdOrPath, String sha)
                List<MergeRequest> mrs = api.getMergeRequestApi()
                        .getMergeRequests(projectId, Constants.MergeRequestState.ALL, 1, 100);

                for (MergeRequest mr : mrs) {
                    List<Commit> mrCommits = api.getMergeRequestApi()
                            .getCommits(projectId, mr.getIid(), 1, 100);
                    for (Commit c : mrCommits) {
                        final String sha = c.getId();
                        detailFutures.add(CompletableFuture.runAsync(() -> {
                            try {
                                // getCommit(Object, String) — stats are included by default
                                Commit detail = commitsApi.getCommit((Object) projectId, sha);
                                CommitDetail cd = toDetail(detail, segment, projectName);
                                if (cd != null) unique.put(cd.id(), cd);
                            } catch (Exception e) {
                                System.err.println("Error fetching commit " + sha + ": " + e.getMessage());
                            }
                        }));
                    }
                }

                CompletableFuture.allOf(detailFutures.toArray(new CompletableFuture[0])).join();

                statusCallback.accept("[" + projectName + "] готово: " + unique.size() + " коммитов");
                return new ArrayList<>(unique.values());

            } catch (Exception e) {
                statusCallback.accept("Ошибка проекта " + projectId + ": " + e.getMessage());
                System.err.println("Project " + projectId + " error: " + e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private CommitDetail toDetail(Commit c, String segment, String projectName) {
        if (c == null || c.getCommittedDate() == null) return null;
        String dateStr = c.getCommittedDate().toInstant().toString();
        if (!isTimeBound(dateStr)) return null;

        String msg = c.getMessage() == null ? "" : c.getMessage()
                .replaceAll("Merge branch '[^']+' into '[^']+'", "")
                .replaceAll("\\n+See merge request .+", "")
                .replaceAll("\\n{2,}", "\n")
                .trim();

        int additions = 0, deletions = 0;
        if (c.getStats() != null) {
            additions = c.getStats().getAdditions() != null ? c.getStats().getAdditions() : 0;
            deletions = c.getStats().getDeletions() != null ? c.getStats().getDeletions() : 0;
        }

        return new CommitDetail(
                c.getId(), dateStr, msg,
                c.getCommitterName() != null ? c.getCommitterName() : "",
                additions, deletions, segment, projectName);
    }

    private boolean isTimeBound(String dateStr) {
        try {
            Instant i = Instant.parse(dateStr);
            return i.isAfter(Instant.parse(since)) && i.isBefore(Instant.parse(until));
        } catch (Exception e) {
            return false;
        }
    }
}
