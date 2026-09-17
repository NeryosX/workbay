# Contributing to Workbay

Thanks for taking the time to contribute. This document covers how to file a
good bug report or feature request, and how to get a code change merged.

## Before you start

- Search [existing issues](../../issues) first - someone may have already
  reported it or asked for it.
- For small fixes (typos, docs, obvious bugs) feel free to open a pull request
  directly. For anything larger (new mechanics, breaking changes, new
  dependencies), open an issue first so we can agree on the approach before
  you spend time on it.

## Reporting bugs and requesting features

Use the issue templates - they ask for the information needed to act on a
report quickly (repro steps, environment, expected vs. actual behavior).
Issues opened without that context may be closed with a request for more
detail.

## Development setup

Requirements: Java 21, NeoForge 21.1.249+ (Minecraft 1.21.1).

```
./gradlew build          # builds the mod jar
./gradlew runClient      # launches a dev client
                          #   -Pworkbay.world=<save>   load a specific save
                          #   -Pworkbay.noJei          run without JEI
                          #   -Pworkbay.user=<name>    dev session username
./gradlew runGameTestServer  # runs the gametest suite
./gradlew check              # compiles every source set
```

Run `./gradlew build runGameTestServer` before opening a pull request. A change that touches
block behavior, networking (Connectors/FLOW), or rooms should come with a
gametest covering it.

## Submitting a pull request

1. Fork the repository (or create a branch, if you have write access) -
   `master` is protected and cannot be pushed to directly.
2. Create a branch off `master` named for what it does, e.g.
   `fix/connector-filter-crash` or `feature/room-preview`.
3. Keep the change focused. Unrelated cleanup belongs in its own PR.
4. Make sure `./gradlew build runGameTestServer` passes locally.
5. Open the pull request against `master` and fill in the PR template.
6. A maintainer will review, request changes if needed, and merge once it's
   green.

## Code style

- Match the formatting and naming already used in the surrounding file.
- Keep commit messages descriptive - explain what changed and why, not just
  what file.
- No unrelated reformatting in the same commit as a behavioral change.

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By
participating, you're expected to uphold it.

## Documentation

The wiki is written in `docs/wiki/`, one markdown file per page. Edit the file
there, open a pull request like any other change, and the page is copied across
once it is merged. GitHub keeps a wiki in its own repository with no pull
requests and no review, so this is how a documentation change gets a diff and a
second pair of eyes.
