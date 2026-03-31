package com.example.gitlabcommits;

import java.time.*;
import java.time.format.DateTimeFormatter;

public record CommitDetail(
        String id,
        String committedDate,
        String message,
        String committerName,
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
            Instant instant = Instant.parse(committedDate);
            formattedDate = FORMATTER.format(instant);
        } catch (Exception ignored) {
            formattedDate = committedDate;
        }
        String safeMessage = message.replace(";", ",").replace("\n", " ");
        return String.join(";",
                id, segment, projectName, formattedDate, safeMessage, committerName,
                String.valueOf(additions), String.valueOf(deletions));
    }
}
