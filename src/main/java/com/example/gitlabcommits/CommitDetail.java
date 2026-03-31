package com.example.gitlabcommits;

import java.time.*;
import java.time.format.DateTimeFormatter;

public class CommitDetail {

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

    public CommitDetail(String id, String committedDate, String message,
                        String authorEmail, int additions, int deletions,
                        String segment, String projectName) {
        this.id            = id;
        this.committedDate = committedDate;
        this.message       = message;
        this.authorEmail   = authorEmail;
        this.additions     = additions;
        this.deletions     = deletions;
        this.segment       = segment;
        this.projectName   = projectName;
    }

    public String id()            { return id; }
    public String committedDate() { return committedDate; }
    public String message()       { return message; }
    public String authorEmail()   { return authorEmail; }
    public int    additions()     { return additions; }
    public int    deletions()     { return deletions; }
    public String segment()       { return segment; }
    public String projectName()   { return projectName; }

    public String toCsvRow() {
        String formattedDate;
        try {
            formattedDate = FORMATTER.format(Instant.parse(committedDate));
        } catch (Exception ignored) {
            formattedDate = committedDate;
        }
        String safeMsg = message.replace(";", ",").replace("\n", " ").trim();
        return String.join(";",
                id, segment, projectName, formattedDate, safeMsg, authorEmail,
                String.valueOf(additions), String.valueOf(deletions));
    }
}
