package com.example.gitlabcommits;

import org.gitlab4j.api.*;
import org.gitlab4j.api.models.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class GitLabService {

    private final GitLabApi api;
    private final String segment;
    private final String since;
    private final String until;
    private final BiConsumer<Integer, Integer> progressCallback;

    private final AtomicInteger done  = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    public GitLabService(String host, String token, String segment,
                         String since, String until,
                         BiConsumer<Integer, Integer> progressCallback) {
        this.api = new GitLabApi(host, token);
        this.segment = segment;
        this.since = since;
        this.until = until;
        this.progressCallback = progressCallback;
    }

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

                Map<String, CommitDetail> unique = new ConcurrentHashMap<>();

                Date sinceDate = Date.from(Instant.parse(since));
                Date untilDate = Date.from(Instant.parse(until));

                CommitsApi commitsApi = api.getCommitsApi();

                List<Commit> directCommits = commitsApi.getCommits(
                        (Object) projectId, null, sinceDate, untilDate, null,
                        Boolean.TRUE, Boolean.TRUE, Boolean.FALSE
                );

                total.addAndGet(directCommits.size());
                fireProgress();

                for (Commit c : directCommits) {
                    CommitDetail cd = toDetail(c, segment, projectName);
                    if (cd != null) unique.put(cd.id(), cd);
                    done.incrementAndGet();
                    fireProgress();
                }

                List<MergeRequest> mrs = api.getMergeRequestApi()
                        .getMergeRequests(projectId, Constants.MergeRequestState.ALL, 1, 100);

                List<String> mrShas = new ArrayList<>();
                for (MergeRequest mr : mrs) {
                    List<Commit> mrCommits = api.getMergeRequestApi()
                            .getCommits(projectId, mr.getIid(), 1, 100);
                    for (Commit c : mrCommits) mrShas.add(c.getId());
                }

                total.addAndGet(mrShas.size());
                fireProgress();

                List<CompletableFuture<Void>> detailFutures = new ArrayList<>();
                for (String sha : mrShas) {
                    detailFutures.add(CompletableFuture.runAsync(() -> {
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
                    }));
                }

                CompletableFuture.allOf(detailFutures.toArray(new CompletableFuture[0])).join();

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

        String authorEmail = c.getAuthorEmail() != null ? c.getAuthorEmail() : "";

        return new CommitDetail(
                c.getId(), dateStr, msg,
                authorEmail,
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
