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
    private final String cacheKey;  // "host|token" for UserResolver
    private final String segment;
    private final String since;
    private final String until;

    /** (done, total) — both numbers grow over time */
    private final BiConsumer<Integer, Integer> progressCallback;
    /** Text phase label shown next to the numbers */
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

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Per-project pipeline
    //
    // Step 1 (async): resolve project name
    // Step 2 (async): fetch list of direct commits  ─┐  run in parallel
    //                 fetch list of MRs              ─┘
    // Step 3 (async × N): for each direct commit   ─┐  all in parallel
    //          per MR (async × M):                  │
    //            fetch MR commit list               │
    //            then async × K per commit detail   ─┘
    // -----------------------------------------------------------------------

    private CompletableFuture<List<CommitDetail>> fetchProject(int projectId) {
        ConcurrentHashMap<String, CommitDetail> unique = new ConcurrentHashMap<>();
        CommitsApi commitsApi = api.getCommitsApi();
        Date sinceDate = Date.from(Instant.parse(since));
        Date untilDate = Date.from(Instant.parse(until));

        // Step 1: resolve name
        CompletableFuture<String> nameFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return api.getProjectApi().getProject(projectId).getName();
            } catch (Exception e) {
                err("Cannot resolve project name " + projectId + ": " + e.getMessage());
                return "#" + projectId;
            }
        });

        // Step 2a: fetch direct commit list (depends on name for phase label)
        CompletableFuture<List<Commit>> directListFuture = nameFuture.thenApplyAsync(name -> {
            try {
                phaseCallback.accept("[" + name + "] прямые коммиты...");
                List<Commit> list = commitsApi.getCommits(
                        projectId, null, sinceDate, untilDate, null,
                        Boolean.TRUE, Boolean.TRUE, Boolean.FALSE
                );
                total.addAndGet(list.size());
                fireProgress();
                return list;
            } catch (Exception e) {
                err("Direct commits error [" + projectId + "]: " + e.getMessage());
                return Collections.emptyList();
            }
        });

        // Step 2b: fetch MR list (depends on name for phase label, runs in parallel with 2a)
        CompletableFuture<List<MergeRequest>> mrListFuture = nameFuture.thenApplyAsync(name -> {
            try {
                phaseCallback.accept("[" + name + "] MR-список...");
                return api.getMergeRequestApi()
                        .getMergeRequests(projectId, Constants.MergeRequestState.ALL, 1, 100);
            } catch (Exception e) {
                err("MR list error [" + projectId + "]: " + e.getMessage());
                return Collections.emptyList();
            }
        });

        // Step 3a: process direct commits — each in its own runAsync
        CompletableFuture<Void> directFuture = directListFuture.thenCompose(directCommits -> {
            String name = nameFuture.join(); // already done at this point
            List<CompletableFuture<Void>> tasks = directCommits.stream()
                    .map(c -> CompletableFuture.runAsync(() -> {
                        try {
                            String branch = resolveBranch(commitsApi, projectId, c.getId());
                            CommitDetail cd = toDetail(c, segment, name, branch);
                            if (cd != null) unique.put(cd.id(), cd);
                        } catch (Exception e) {
                            err("Direct commit detail error " + c.getId() + ": " + e.getMessage());
                        } finally {
                            done.incrementAndGet();
                            fireProgress();
                        }
                    }))
                    .collect(Collectors.toList());
            return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
        });

        // Step 3b: for each MR — fetch its commits list, then detail each in parallel
        CompletableFuture<Void> mrFuture = mrListFuture.thenCompose(mrs -> {
            String name = nameFuture.join(); // already done
            List<CompletableFuture<Void>> mrTasks = mrs.stream()
                    .map(mr -> CompletableFuture.supplyAsync(() -> {
                        // fetch commit list for this MR
                        try {
                            String branch = mr.getSourceBranch() != null ? mr.getSourceBranch() : "";
                            List<Commit> mrCommits = api.getMergeRequestApi()
                                    .getCommits(projectId, mr.getIid(), 1, 100);
                            total.addAndGet(mrCommits.size());
                            fireProgress();
                            return new AbstractMap.SimpleEntry<>(branch, mrCommits);
                        } catch (Exception e) {
                            err("MR commits error MR#" + mr.getIid() + ": " + e.getMessage());
                            return new AbstractMap.SimpleEntry<>("", Collections.<Commit>emptyList());
                        }
                    }).thenCompose(entry -> {
                        // detail each commit in this MR in parallel
                        String branch   = entry.getKey();
                        List<Commit> cs = entry.getValue();
                        List<CompletableFuture<Void>> detailTasks = cs.stream()
                                .map(c -> CompletableFuture.runAsync(() -> {
                                    try {
                                        Commit detail = commitsApi.getCommit(projectId, c.getId());
                                        CommitDetail cd = toDetail(detail, segment, name, branch);
                                        if (cd != null) unique.put(cd.id(), cd);
                                    } catch (Exception e) {
                                        err("MR commit detail error " + c.getId() + ": " + e.getMessage());
                                    } finally {
                                        done.incrementAndGet();
                                        fireProgress();
                                    }
                                }))
                                .collect(Collectors.toList());
                        return CompletableFuture.allOf(detailTasks.toArray(new CompletableFuture[0]));
                    }))
                    .collect(Collectors.toList());
            return CompletableFuture.allOf(mrTasks.toArray(new CompletableFuture[0]));
        });

        // Combine 3a + 3b, then collect results
        return CompletableFuture.allOf(directFuture, mrFuture)
                .thenApply(v -> new ArrayList<>(unique.values()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String resolveBranch(CommitsApi commitsApi, int projectId, String sha) {
        try {
            List<CommitRef> refs = commitsApi.getCommitRefs(projectId, sha, CommitRef.RefType.BRANCH);
            if (refs != null && !refs.isEmpty()) return refs.get(0).getName();
        } catch (Exception e) {
            err("resolveBranch " + sha + ": " + e.getMessage());
        }
        return "*undefined*";
    }

    private void err(String message) {
        System.err.println(message);
        logCallback.accept("[ERR] " + message);
    }

    private void fireProgress() {
        progressCallback.accept(done.get(), total.get());
    }

    private CommitDetail toDetail(Commit c, String segment, String projectName, String branch) {
        if (c == null || c.getCommittedDate() == null)
            return null;

        String dateStr = c.getCommittedDate().toInstant().toString();
        if (!isTimeBound(dateStr))
            return null;

        String msg = c.getMessage() == null ? "" : c.getMessage()
                .replaceAll("Merge branch '[^']+' into '[^']+'\n+", "")
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
