package com.example.gitlabcommits;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class CommitDetail {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy;HH:mm:ss").withZone(ZoneId.systemDefault());
    private final String id;
    private final String committedDate;
    private final String message;
    private final String authorEmail;
    private final int additions;
    private final int deletions;
    private final String segment;
    private final String projectName;
    private final String branch;

    public CommitDetail(
            String id,
            String committedDate,
            String message,
            String authorEmail,
            int additions,
            int deletions,
            String segment,
            String projectName,
            String branch
    ) {
        this.id = id;
        this.committedDate = committedDate;
        this.message = message;
        this.authorEmail = authorEmail;
        this.additions = additions;
        this.deletions = deletions;
        this.segment = segment;
        this.projectName = projectName;
        this.branch = branch;
    }

    public String toCsvRow() {
        String formattedDate;
        try {
            formattedDate = FORMATTER.format(Instant.parse(committedDate));
        } catch (Exception ignored) {
            formattedDate = committedDate;
        }
        String safeMsg = message.replace(";", ",").replace("\n", " ").trim();
        return String.join(";",
                id, segment, projectName, branch, formattedDate, safeMsg, authorEmail,
                String.valueOf(additions), String.valueOf(deletions));
    }

    public String id()            { return id; }
    public String committedDate() { return committedDate; }
    public String message()       { return message; }
    public String authorEmail()   { return authorEmail; }
    public int    additions()     { return additions; }
    public int    deletions()     { return deletions; }
    public String segment()       { return segment; }
    public String projectName()   { return projectName; }
    public String branch()        { return branch; }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        CommitDetail that = (CommitDetail) obj;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommitDetail[id=" + id + ", project=" + projectName + ", branch=" + branch + "]";
    }
}
