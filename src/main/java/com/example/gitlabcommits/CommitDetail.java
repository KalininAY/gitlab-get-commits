package com.example.gitlabcommits;

import java.time.*;
import java.time.format.DateTimeFormatter;

public record CommitDetail(
        String id,
        String committedDate,
        String message,
        String authorEmail,
        int additions,
        int deletions,
        String segment,
        String projectName
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy;HH:mm:ss").withZone(ZoneId.systemDefault());

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
