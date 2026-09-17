Workbay builds with Gradle against NeoForge. You need **Java 21** and **NeoForge 21.1.249 or newer** for Minecraft 1.21.1.

## Commands

```
./gradlew build              # build the mod jar
./gradlew runClient          # launch a dev client
./gradlew check              # compile every source set, main, datagen and gametest
./gradlew runGameTestServer  # the 186 gametests
./gradlew runBenchmark       # the performance scenarios (minutes)
```

`build` plus `runGameTestServer` is the gate. `check` is wired to compile the datagen and gametest source sets as well, so a compile error there cannot pass the build and surface only when somebody runs the tests.

`runClient` takes a few useful flags:

| Flag | Effect |
| --- | --- |
| `-Pworkbay.world=<save>` | Load a specific save. |
| `-Pworkbay.noJei` | Run without JEI, to check the fallbacks. |
| `-Pworkbay.user=<name>` | Set the dev session username - handy for testing locking with two accounts. |

## Before you open a pull request

```
./gradlew build runGameTestServer
```

A change that touches block behaviour, networking (Connectors and FLOW) or Rooms should come with a gametest that covers it. The suite exists so that regressions in hosting and transfer are caught before a release, not after.

The performance scenarios in `PerfBench` are gated behind `runBenchmark` and pass trivially without it, so a number quoted from an ordinary gametest run is not a measurement.

## Workflow

`master` is protected: it takes no direct pushes, and changes arrive through a pull request.

1. Fork the repository, or branch it if you have write access.
2. Branch off `master` with a name that says what the change does - `fix/connector-filter-crash`, `feature/room-preview`.
3. Keep the change focused; unrelated cleanup belongs in its own pull request.
4. Open the pull request against `master` and fill in the template.

The full guide is in [CONTRIBUTING.md](https://github.com/neryosx/workbay/blob/master/CONTRIBUTING.md).

## Documentation

The wiki is written in `docs/wiki/`, one markdown file per page, and reaches the wiki through a pull request like any other change - see the same guide.

## Reporting instead of coding

Bugs and ideas are just as useful as patches:

- [Report a bug](https://github.com/neryosx/workbay/issues/new?template=bug_report.yml) - include the steps that reproduce it, and a screenshot or a clip if you can.
- [Request a feature](https://github.com/neryosx/workbay/issues/new?template=feature_request.yml) - describe the problem before the solution.
- Security issues go through [a private advisory](https://github.com/neryosx/workbay/security/advisories/new), never a public issue. See [SECURITY.md](https://github.com/neryosx/workbay/blob/master/SECURITY.md).

## Licence

MIT. Some patterns follow [EnderIO](https://github.com/Team-EnderIO/EnderIO), which is public domain under the Unlicense. Modpacks are welcome without asking.
