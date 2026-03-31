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
    private final UserResolver userResolver = new UserResolver();

    private final AtomicInteger done  = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    public GitLabService(String host, String token, String segment,
                         String since, String until,
                         BiConsumer<Integer, Integer> progressCallback,
                         Consumer<String> phaseCallback) {
        this.api = new GitLabApi(host, token);
        this.cacheKey = host + "|" + token;
        this.segment = segment;
        this.since = since;
        this.until = until;
        this.progressCallback = progressCallback;
        this.phaseCallback = phaseCallback;
    }

    public CompletableFuture<List<CommitDetail>> fetchProjects(List<Integer> projectIds) {
        List<CompletableFuture<List<CommitDetail>>> futures = projectIds.stream()
                .map(this::fetchProject)
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, CommitDetail> unique = new LinkedHashMap<>();
                    for (CompletableFuture<List<CommitDetail>> f : futures) {
                        for (CommitDetail c : f.join()) {
                            unique.put(c.id(), c);
                        }
                    }
                    List<CommitDetail> result = new ArrayList<>(unique.values());
                    Collections.sort(result, Comparator.comparing(CommitDetail::committedDate));
                    return result;
                });
    }

    private CompletableFuture<List<CommitDetail>> fetchProject(final int projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Project project = api.getProjectApi().getProject(projectId);
                String projectName = project.getName();
                Map<String, CommitDetail> unique = new ConcurrentHashMap<>();
                CommitsApi commitsApi = api.getCommitsApi();
                Date sinceDate = Date.from(Instant.parse(since));
                Date untilDate = Date.from(Instant.parse(until));

                // ---- Phase 1: direct commits in date range ----
                // 5.x API: getCommits(Object, String refName, Date since, Date until, int page, int perPage)
                // Returns stats as part of each Commit when fetched individually;
                // list endpoint does NOT include stats, so we need a detail call per commit.
                phaseCallback.accept("[" + projectName + "] прямые коммиты...");
                List<Commit> directCommits = commitsApi.getCommits(
                        (Object) projectId, null, sinceDate, untilDate, 1, 1000
                );
                total.addAndGet(directCommits.size());
                fireProgress();

                List<CompletableFuture<Void>> directDetailFutures = directCommits.stream()
                        .map(c -> CompletableFuture.runAsync(() -> {
                            try {
                                // getCommit(Object, String) — includes stats
                                Commit detail = commitsApi.getCommit((Object) projectId, c.getId());
                                CommitDetail cd = toDetail(detail, segment, projectName);
                                if (cd != null) unique.put(cd.id(), cd);
                            } catch (Exception e) {
                                System.err.println("Error fetching commit " + c.getId() + ": " + e.getMessage());
                            } finally {
                                done.incrementAndGet();
                                fireProgress();
                            }
                        }))
                        .collect(Collectors.toList());
                CompletableFuture.allOf(directDetailFutures.toArray(new CompletableFuture[0])).join();

                // ---- Phase 2: MR commits ----
                phaseCallback.accept("[" + projectName + "] MR-списки...");
                // getMergeRequests(Object, MergeRequestState, int page, int perPage)
                List<MergeRequest> mrs = api.getMergeRequestApi()
                        .getMergeRequests((Object) projectId,
                                Constants.MergeRequestState.ALL, 1, 100);

                List<CompletableFuture<Void>> allMrFutures = new ArrayList<>();
                for (MergeRequest mr : mrs) {
                    final long mrIid = mr.getIid();
                    allMrFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            // getCommits(Object, Long, int page, int perPage)
                            List<Commit> mrCommits = api.getMergeRequestApi()
                                    .getCommits((Object) projectId, mrIid, 1, 100);
                            total.addAndGet(mrCommits.size());
                            fireProgress();

                            List<CompletableFuture<Void>> detailFutures = mrCommits.stream()
                                    .map(Commit::getId)
                                    .map(sha -> CompletableFuture.runAsync(() -> {
                                        try {
                                            Commit detail = commitsApi.getCommit((Object) projectId, sha);
                                            CommitDetail cd = toDetail(detail, segment, projectName);
                                            if (cd != null) unique.put(cd.id(), cd);
                                        } catch (Exception e) {
                                            System.err.println("Error fetching commit " + sha + ": " + e.getMessage());
                                        } finally {
                                            done.incrementAndGet();
                                            fireProgress();
                                        }
                                    }))
                                    .collect(Collectors.toList());
                            CompletableFuture.allOf(detailFutures.toArray(new CompletableFuture[0])).join();
                        } catch (Exception e) {
                            System.err.println("Error fetching MR " + mrIid + ": " + e.getMessage());
                        }
                    }));
                }

                phaseCallback.accept("[" + projectName + "] детали коммитов...");
                CompletableFuture.allOf(allMrFutures.toArray(new CompletableFuture[0])).join();

                return new ArrayList<>(unique.values());

            } catch (Exception e) {
                System.err.println("Project " + projectId + " error: " + e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private void fireProgress() {
        progressCallback.accept(done.get(), total.get());
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

        String username = userResolver.resolve(api, cacheKey, c.getAuthorEmail());

        return new CommitDetail(
                c.getId(), dateStr, msg,
                username,
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
