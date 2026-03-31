# GitLab Get Commits

Java/Swing desktop application to fetch GitLab commits for a project within a date range.

## Features

- Fetches commits directly from a date range **and** from merge request commits
- Deduplicates commits by ID
- Filters by `since`/`until` date bounds
- Outputs CSV rows: `id;segment;project;DD.MM.YYYY;HH:mm:ss;message;committer;additions;deletions`
- Settings are persisted to `~/.gitlab-get-commits.properties`
- Copy result to clipboard with one click
- Fully asynchronous HTTP requests via `java.net.http.HttpClient` + `CompletableFuture`

## Requirements

- Java 17+

## Build & Run

```bash
./gradlew run
```

Or build a fat JAR:

```bash
./gradlew jar
java -jar build/libs/gitlab-get-commits-1.0.0.jar
```

## CSV Output Format

```
<commit_sha>;<segment>;<project_name>;<DD.MM.YYYY>;<HH:mm:ss>;<message>;<committer>;<additions>;<deletions>
```
