package com.example.gitlabcommits;

import org.gitlab4j.api.*;
import org.gitlab4j.api.models.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GitLabService {

    private final GitLabApi api;
    private final String cacheKey;
    private final String segment;
    private final String since;
    private final String until;

    private final BiConsumer<Integer, Integer> progressCallback;
    private final Consumer<String> phaseCallback;
    private final Consumer<String> logCallback;
    private final UserResolver userResolver = new UserResolver();

    private final AtomicInteger done  = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    public GitLabService(String host, String token, String segment,
                         String since, String until,
                         BiConsumer<Integer, Integer> progressCallback,
                         Consumer<String> phaseCallback,
                         Consumer<String> logCallback) {
        GitLabApi gitLabApi = new GitLabApi(host, token);
        gitLabApi.setIgnoreCertificateErrors(true);
        this.api = gitLabApi;
        this.cacheKey = host + "|" + token;
        this.segment = segment;
        this.since = since;
        this.until = until;
        this.progressCallback = progressCallback;
        this.phaseCallback = phaseCallback;
        this.logCallback = logCallback;
    }

    public CompletableFuture<List<CommitDetail>> fetchProjects(List<Integer> projectIds) {
        List<CompletableFuture<List<CommitDetail>>> futures = projectIds.stream()
                .map(this::fetchProject)
                .collect(Collectors.toList());

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
                Map<String, CommitDetail> unique = new ConcurrentHashMap<>();
                CommitsApi commitsApi = api.getCommitsApi();
                Date sinceDate = Date.from(Instant.parse(since));
                Date untilDate = Date.from(Instant.parse(until));

                // ── Phase 1: direct commits ──────────────────────────────────────
                phaseCallback.accept("[" + projectName + "] прямые коммиты...");
                List<Commit> directCommits = commitsApi.getCommits(
                        projectId, null, sinceDate, untilDate, null,
                        Boolean.TRUE, Boolean.TRUE, Boolean.FALSE
                );
                total.addAndGet(directCommits.size());
                fireProgress();
                for (Commit c : directCommits) {
                    String branch = resolveBranch(commitsApi, projectId, c.getId());
                    CommitDetail cd = toDetail(c, segment, projectName, branch);
                    if (cd != null) unique.put(cd.id(), cd);
                    done.incrementAndGet();
                    fireProgress();
                }

                // ── Phase 2: MR commits ──────────────────────────────────────────
                phaseCallback.accept("[" + projectName + "] MR-списки...");
                List<MergeRequest> mrs = api.getMergeRequestApi()
                        .getMergeRequests(projectId, Constants.MergeRequestState.ALL, 1, 100);

                List<CompletableFuture<Void>> allMrFutures = new ArrayList<>();
                for (MergeRequest mr : mrs) {
                    final String mrBranch = mr.getSourceBranch() != null ? mr.getSourceBranch() : "";
                    allMrFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            List<Commit> mrCommits = api.getMergeRequestApi()
                                    .getCommits(projectId, mr.getIid(), 1, 100);
                            total.addAndGet(mrCommits.size());
                            fireProgress();

                            CompletableFuture.allOf(mrCommits.stream()
                                    .map(Commit::getId)
                                    .map(sha -> CompletableFuture.runAsync(() -> {
                                        try {
                                            Commit detail = commitsApi.getCommit(projectId, sha);
                                            CommitDetail cd = toDetail(detail, segment, projectName, mrBranch);
                                            if (cd != null) unique.put(cd.id(), cd);
                                        } catch (Exception e) {
                                            err("Error fetching commit " + sha + ": " + e.getMessage());
                                        } finally {
                                            done.incrementAndGet();
                                            fireProgress();
                                        }
                                    })).toArray(CompletableFuture[]::new)).join();
                        } catch (Exception e) {
                            err("Error fetching MR " + mr.getIid() + ": " + e.getMessage());
                        }
                    }));
                }

                phaseCallback.accept("[" + projectName + "] детали коммитов...");
                CompletableFuture.allOf(allMrFutures.toArray(new CompletableFuture[0])).join();

                return new ArrayList<>(unique.values());

            } catch (Exception e) {
                err("Project " + projectId + " error: " + e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private String resolveBranch(CommitsApi commitsApi, int projectId, String sha) {
        try {
            List<CommitRef> refs = commitsApi.getCommitRefs(projectId, sha, CommitRef.RefType.BRANCH);
            if (refs != null && !refs.isEmpty()) {
                return refs.get(0).getName();
            }
        } catch (Exception e) {
            err("resolveBranch " + sha + ": " + e.getMessage());
        }
        return "";
    }

    private void err(String message) {
        System.err.println(message);
        logCallback.accept("[ERR] " + message);
    }

    private void fireProgress() {
        progressCallback.accept(done.get(), total.get());
    }

    private CommitDetail toDetail(Commit c, String segment, String projectName, String branch) {
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

        String username = userResolver.resolve(api, cacheKey, c.getAuthorName());

        return new CommitDetail(
                c.getId(), dateStr, msg,
                author(username),
                additions, deletions, segment, projectName, branch);
    }

    private String author(String name) {
        if (name == null || name.isBlank()) return "";
        int pos = name.indexOf('@');
        if (pos > -1) name = name.substring(0, pos);
        return name;
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
