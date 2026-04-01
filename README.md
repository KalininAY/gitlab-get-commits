# GitLab Get Commits

Java 21 + Swing desktop app that fetches GitLab commits for one or multiple projects within a date range and exports them as CSV.

## Features

- **gitlab4j-api** for type-safe GitLab REST calls
- Fetches commits from direct date range **and** from merge request commits, deduplicated by SHA
- Filters by `since`/`until` date bounds
- **Multiple projects** — enter several Project IDs separated by commas
- **Project name** resolved automatically from GitLab
- **Branch** resolved per commit: for direct commits via Commit Refs API (prefers feature branches over main/master), for MR commits via `sourceBranch` from the MR
- **Progress bar** with per-project phase messages
- All fields are **editable combo boxes** with remembered history
- **Per-token config in JSON**: switching the Token combo reloads Host and all other fields saved for that token
- **SSL verification disabled** globally — works with self-signed and corporate CA certificates
- Config stored at `~/.gitlab-get-commits.json`

## CSV Output Format

```
<sha>;<segment>;<project_name>;<branch>;<DD.MM.YYYY>;<HH:mm:ss>;<message>;<author>;<additions>;<deletions>
```

## Config Structure

```json
{
  "tokens": ["glpat-...", "xq7x..."],
  "tokenData": {
    "glpat-...": {
      "host":       ["https://gitlab.example.com"],
      "segment":    ["My Segment"],
      "projectIds": ["4139,4747,5549"],
      "since":      ["2025-10-01T00:00:01Z"],
      "until":      ["2025-11-01T00:00:01Z"]
    }
  }
}
```

Values are saved automatically on each "Получить коммиты" click. Switching the **Token** combo instantly restores all fields for that token.

## Requirements

- Java 21+

## Build & Run

```bash
./gradlew run
# or fat JAR:
./gradlew jar
java -jar build/libs/gitlab-get-commits-1.0.0.jar
```
