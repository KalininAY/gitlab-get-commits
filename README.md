# GitLab Get Commits

Java 17 + Swing desktop app that fetches GitLab commits for one or multiple projects within a date range and exports them as CSV.

## Features

- **gitlab4j-api** for type-safe GitLab REST calls
- Fetches commits from direct date range **and** from merge request commits, deduplicated by SHA
- Filters by `since`/`until` date bounds
- **Multiple projects** — enter several Project IDs separated by commas
- **Project name** is resolved automatically from GitLab
- **Indeterminate progress bar** with per-project status messages
- All fields are **combo boxes** with remembered history
- **Per-host config in JSON**: switching the Host combo reloads all other fields for that host
- Config stored at `~/.gitlab-get-commits.json`

## CSV Output Format

```
<sha>;<segment>;<project_name>;<DD.MM.YYYY>;<HH:mm:ss>;<message>;<committer>;<additions>;<deletions>
```

## Requirements

- Java 17+

## Build & Run

```bash
./gradlew run
# or fat JAR:
./gradlew jar
java -jar build/libs/gitlab-get-commits-1.0.0.jar
```
