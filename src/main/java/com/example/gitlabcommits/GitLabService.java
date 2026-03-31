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

    /** Items per page for all Pager calls. GitLab max is 100. */
    private static final int PAGE_SIZE = 100;

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

    // ------------------------------------------------------------------ //
    //  Public entry point
    // ------------------------------------------------------------------ //

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

    // ------------------------------------------------------------------ //
    //  Per-project fetch
    // ------------------------------------------------------------------ //

    private CompletableFuture<List<CommitDetail>> fetchProject(final int projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Project project = api.getProjectApi().getProject(projectId);
                String projectName = project.getName();
                Map<String, CommitDetail> unique = new ConcurrentHashMap<>();
                CommitsApi commitsApi = api.getCommitsApi();
                Date sinceDate = Date.from(Instant.parse(since));
                Date untilDate = Date.from(Instant.parse(until));

                // ---- Phase 1: direct commits in date range, ALL branches (ALL pages) ----
                //
                // Signature (gitlab4j 5.x):
                //   getCommits(Object projectIdOrPath,
                //              String  ref,          <- null = default branch only (unless all=true)
                //              Date    since,
                //              Date    until,
                //              String  path,         <- file path filter, null = no filter
                //              Boolean all,          <- TRUE = traverse all branches (like ?all=true in JS)
                //              Boolean withStats,    <- FALSE = stats NOT in list response anyway
                //              Boolean firstParent,
                //              int     itemsPerPage) -> Pager<Commit>
                phaseCallback.accept("[" + projectName + "] прямые коммиты...");
                List<Commit> directCommits = allPages(
                        commitsApi.getCommits(
                                (Object) projectId,
                                null,         // ref   – doesn't matter when all=true
                                sinceDate,
                                untilDate,
                                null,         // path
                                Boolean.TRUE, // all   – all branches (equivalent to ?all=true)
                                Boolean.FALSE,// withStats
                                Boolean.FALSE,// firstParent
                                PAGE_SIZE
                        )
                );
                total.addAndGet(directCommits.size());
                fireProgress();

                // Fetch full commit details (includes stats) in parallel
                List<CompletableFuture<Void>> directDetailFutures = directCommits.stream()
                        .map(c -> CompletableFuture.runAsync(() -> {
                            try {
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

                // ---- Phase 2: MR commits (ALL pages for MRs and for each MR's commits) ----
                phaseCallback.accept("[" + projectName + "] MR-списки...");
                List<MergeRequest> mrs = allPages(
                        api.getMergeRequestApi().getMergeRequests(
                                (Object) projectId,
                                Constants.MergeRequestState.ALL,
                                PAGE_SIZE
                        )
                );

                List<CompletableFuture<Void>> allMrFutures = new ArrayList<>();
                for (MergeRequest mr : mrs) {
                    final long mrIid = mr.getIid();
                    allMrFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            List<Commit> mrCommits = allPages(
                                    api.getMergeRequestApi().getCommits(
                                            (Object) projectId, mrIid, PAGE_SIZE
                                    )
                            );
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

    // ------------------------------------------------------------------ //
    //  Pager helper – collects all pages into a flat list (blocking)
    // ------------------------------------------------------------------ //

    private static <T> List<T> allPages(Pager<T> pager) throws GitLabApiException {
        List<T> result = new ArrayList<>();
        while (pager.hasNext()) {
            result.addAll(pager.next());
        }
        return result;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private void fireProgress() {
        progressCallback.accept(done.get(), total.get());
    }

    private CommitDetail toDetail(Commit c, String segment, String projectName) {
        if (c == null || c.getCommittedDate() == null) return null;
        String dateStr = c.getCommittedDate().toInstant().toString();
        // Secondary date guard: API filters by authored_date; we filter result by committed_date
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
