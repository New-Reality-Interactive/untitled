---
title: 'Spotless formatting check and .editorconfig'
type: 'chore'
created: '2026-09-25'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'NO_VCS'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-reactive-spring-boot-scaffold.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Nothing enforces a code style, so formatting drifts by editor and the later deferred items would be written in whatever style their author's IDE produces.

**Approach:** Add `spotless-maven-plugin` with its `check` goal bound to `verify`, formatting Java with google-java-format and applying whitespace hygiene to the POM, YAML, Markdown and dotfiles. Add a matching `.editorconfig`. Reformat the existing sources once.

## Boundaries & Constraints

**Decisions (user, 2026-09-25):** Java formatter is **google-java-format** (Google style: 2-space indent, 100 columns). Scope is **Java + POM + misc**. The misc files (`pom.xml`, `src/**/*.yaml`, root `*.md`, `.editorconfig`, `.gitattributes`, `.gitignore`, `.mvn/**/*.properties`) get trailing whitespace trimmed, a final newline and no leading tabs. `docs/openapi.json` is excluded because it is generated and compared byte for byte.

**Always:** Pin the plugin (3.10.3) and the formatter (1.36.1, latest on Central 2026-09-25) in `<properties>`. The check runs in a normal `./mvnw verify`, including under `-DskipTests`/`-DskipITs`, because formatting does not depend on tests. Every rule from the scaffold spec's Implementation Notes and Review Triage Log still holds. The reformat changes whitespace, wrapping and import order only; it does not change behavior.

**Never:** Change code semantics while reformatting. No IDE-specific config files (`.vscode/` is gitignored). No `.mvn/jvm.config` or other JVM flags just to hide the Spotless warning. No sortPom or reordering of POM elements.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean tree | all files formatted | `verify` passes; Spotless reports files clean | N/A |
| Misformatted Java | e.g. 4-space indent in a `.java` file | `verify` fails at `spotless:check` with a diff and "Run 'mvn spotless:apply'" | developer runs `./mvnw spotless:apply` |
| Misc whitespace | trailing space or missing final newline in `application.yaml` / `pom.xml` / `README.md` | `verify` fails | `spotless:apply` fixes it |
| Generated spec | `docs/openapi.json` | not checked by Spotless; drift check unchanged | N/A |

</frozen-after-approval>

## Code Map

- `pom.xml` -- plugin versions sit in `<properties>` under "Build plugins" (alphabetical). The `<build><plugins>` order matters: JaCoCo after spring-boot-maven-plugin. Add Spotless after the antrun drift check, before `maven-site-plugin`. Spotless is not managed by Boot 4.1.1.
- `src/main/java/**`, `src/test/java/**` (17 files) -- currently 4-space indent with 8-space continuation; `spotless:apply` reformats all of them. Prototype (scratch copy): GJF 1.36.1 runs in-process on JDK 25 without `--add-exports`, and apply followed by check passes.
- `.gitattributes` -- LF for everything, CRLF for `*.cmd`; `.editorconfig` must agree.
- `README.md` -- add a Formatting section and a `verify` step, and update the "Dependency and plugin versions" note (the formatter version is plugin configuration, which `versions:display-plugin-updates` does not report).
- Do not touch `_bmad/` or `_bmad-output/` (except this spec and `deferred-work.md`), `docs/openapi.json`, `mvnw`, `mvnw.cmd`.

## Tasks & Acceptance

**Execution:**
- [x] `pom.xml` -- add `spotless-maven-plugin.version` 3.10.3 and `google-java-format.version` 1.36.1 properties. Add the plugin with a `check` execution (default phase `verify`), `<java>` with `googleJavaFormat`, `removeUnusedImports`, `formatAnnotations`, and a generic `<format>` for the misc includes: `trimTrailingWhitespace`, `endWithNewline`, `indent` (spaces, 4 per tab).
- [x] `.editorconfig` -- `root = true`; `[*]` utf-8, lf, final newline, trim trailing whitespace, space indent. Java: indent 2, `max_line_length = 100`. XML/POM: indent 4. YAML/JSON: indent 2. `*.cmd`: crlf. No `*.md` exception: Spotless trims Markdown too.
- [x] `src/**/*.java` -- run `./mvnw spotless:apply` once. Review the diff for whitespace, wrapping and import changes only.
- [x] `README.md` -- add the verify step, a Formatting section (`./mvnw spotless:apply`, the `.editorconfig` note, `-Dspotless.check.skip=true` as the escape hatch) and the formatter-version note.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- remove the Spotless entry.

**Acceptance Criteria:**
- Given ports 8080/8081 held by listeners, when `./mvnw -B clean verify` runs, then it passes with coverage >= 80% line and branch, the OpenAPI drift check passes, and Spotless reports every file clean.
- Given a deliberately misformatted Java file and a YAML file with trailing whitespace (in a scratch copy), when `./mvnw -B verify -DskipTests` runs, then the build fails at `spotless:check`.
- Given the POM, when `./mvnw -B versions:display-plugin-updates` runs, then it reports no plugin updates.

## Implementation Notes

- `google-java-format.version` sits in its own "Formatting" group in `<properties>` rather than under "Build plugins", since it is plugin configuration, not a plugin.
- `spotless:apply` changed only the 17 Java files; the misc includes (8 files) were already clean. A whitespace-stripped comparison (imports compared as sets) matched for 13 files; the other 4 differ only in Javadoc/`//` comment reflow. One reflowed comment in `OpenApiConfig` (a lone `// from.`) was hand-rewrapped, same words.
- Spotless stops at the first failing format block (misc before Java), so the negative test was run once per file type: YAML trailing whitespace and a 4-space-indented `Application.java` each failed `verify -DskipTests` at `spotless:check`.
- The spec originally had `.editorconfig` keep trailing whitespace in `*.md` while Spotless trims it, so editor output could fail `verify`. The `[*.md]` override was removed and the task corrected: two-space Markdown line breaks are not used.

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Route | Evidence |
|---|-------|---------|---------|-------|----------|
| 1 | blind, edge | Editor saving `docs/openapi.json` (final newline/trim) breaks the drift check | false | reject | The committed file has no trailing spaces. Scaffold triage row 12 showed an appended final newline passes `filesmatch textfile`. `indent_size` does not reindent existing lines. |
| 2 | blind, edge | `src/**/*.yml` not checked by Spotless | low | patch | No `.yml` files today, but `.editorconfig` treats them the same way; adding the include is a one-line direct fix. |
| 3 | blind | Generic `indent` uses 4 spaces per tab for YAML while `.editorconfig` says 2 | low | reject | Tabs are illegal in YAML indentation, so such a file already fails to parse; a separate format block adds config for a case users will not reach. |
| 4 | blind, edge | Trimming Markdown trailing whitespace removes two-space hard breaks | low | reject | Deliberate: the editor and the build now agree (see Implementation Notes). README has no hard breaks, and `check` fails loudly instead of changing content silently. |
| 5 | blind | Spotless file set narrower than `.editorconfig` (`_bmad-output/**`, `docs/**`, `mvnw*`) | low | reject | Those are tooling output, the generated spec and the vendored wrapper, which are excluded on purpose. `.yml` is handled by row 2. |
| 6 | blind | No `.git-blame-ignore-revs` for the reformat | false | reject | The project is not a git repo (the user said no `git init`), so there is no commit to list. |
| 7 | blind | Check bound to `verify` fails late; bind to `validate` | false | reject | The approved intent says "`check` goal bound to `verify`". |
| 8 | blind | README omits `formatAnnotations` | low | patch | Direct doc fix. |
| 9 | blind | `.gitattributes` CRLF rules not shown | false | reject | `.gitattributes` already has `* text=auto` and `*.cmd text eol=crlf` (from the scaffold). |
| 10 | blind | `[{*.xml,pom.xml}]` redundant | low | patch | Direct fix: `[*.xml]`. |
| 11 | blind | No evidence the check fails on bad formatting | false | reject | The implementation and verification-gap layers each broke files in scratch copies, and `verify -DskipTests` failed at `spotless:check` for both YAML and Java. |
| 12 | blind | Deleted `deferred-work.md` entry has no pointer to where the work landed | false | reject | The workflow says to remove the entry; this spec records the work. |
| 13 | verification-gap | Spotless `ModuleHelper` uses `sun.misc.Unsafe`; a future JDK could break it | low | reject | Already recorded in Design Notes; the build passes on JDK 25, and the fix belongs upstream. |

## Design Notes

On JDK 25, Spotless's `ModuleHelper` prints `sun.misc.Unsafe::staticFieldBase` warnings. They come from the plugin, and the build still passes. Hiding them would take a JVM-wide flag for Maven, so they are left visible.

## Verification

**Commands:**
- `nc -lk 8080 & nc -lk 8081 & lsof -iTCP:8080 -iTCP:8081 -sTCP:LISTEN; ./mvnw -B clean verify` -- expected: BUILD SUCCESS, test counts, JaCoCo "All coverage checks have been met", Spotless clean
- `./mvnw -B versions:display-plugin-updates` -- expected: "All plugins with a version specified are using the latest versions"
- Scratch-copy negative test -- expected: BUILD FAILURE at `spotless:check`
