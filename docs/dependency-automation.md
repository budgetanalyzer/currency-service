# Dependency Automation

The workspace-wide operating policy, shared Renovate preset, and failure triage
are owned by
[orchestration's dependency automation guide](../../orchestration/docs/dependency-automation.md).
This document records only the `currency-service` integration and review checks.

## Update discovery

`renovate.json` extends
`github>budgetanalyzer/orchestration//renovate-presets/default`. The reference
has no branch suffix, so Renovate inherits the production preset from the
orchestration repository's default branch. Renovate's native Gradle, Gradle
Wrapper, Dockerfile, and GitHub Actions managers discover this repository's
version catalog, build script, wrapper distribution, base images, and workflow
actions. The Gradle catalog extraction includes the declared `serviceCommon`
version used by `spring-cloud-platform`, `service-core`, and `service-web`.

`service-common` is hosted in GitHub Packages. Configure the Mend Renovate
Community App's supported encrypted Maven credentials for authenticated lookup;
never put a package token in this repository or the shared preset. A successful
lookup must cover all `org.budgetanalyzer` declarations as well as public
dependencies and workflow actions.

Renovate proposes changes only to direct declarations. Versions inherited from
the Spring Boot, Spring Cloud, and Spring Modulith BOMs remain represented by
the resolved dependency graph described below. A direct Spring Boot or
`serviceCommon` proposal is not proof that every inherited vulnerability is
fixed.

## Authenticated dependency graph

`.github/workflows/dependency-submission.yml` submits the resolved Gradle graph
on trusted `main` pushes, weekly runs, and manual dispatches. The workflow grants
only `contents: write` at job scope, checks out without persisted credentials,
and uses the official `gradle/actions/dependency-submission` action with the
open-source `basic` cache provider. It generates and submits the graph directly
and does not retain the snapshot as an artifact or publish a Build Scan.

Before graph generation, the workflow verifies that the configured package-read
credentials can retrieve the pinned `spring-cloud-platform`, `service-core`, and
`service-web` POMs. Gradle receives
`SERVICE_COMMON_PACKAGES_USERNAME` and
`SERVICE_COMMON_PACKAGES_READ_TOKEN` as `GITHUB_ACTOR` and `GITHUB_TOKEN` for
package resolution. The action separately receives `${{ github.token }}` for
graph submission. Do not turn the package-read credential into a submission
credential or expose either credential to untrusted workflows.

The action's default resolution task visits all projects and all resolvable
configurations, including application, runtime, build, and test dependency
trees. The workflow must fail when package credentials are missing, a pinned
`service-common` artifact cannot be resolved, graph generation is incomplete,
or GitHub rejects submission. Do not substitute Maven Local, omit the internal
dependency, or add filters without proving equivalent coverage. A successful
hosted workflow run is the proof that GitHub accepted the complete graph and can
supply Dependabot alerts.

## Production build artifacts

`.github/workflows/build.yml` runs a normal cached Gradle build for `main`
pushes, pull requests targeting `main`, and manual dispatches. Regular CI does
not upload the application JAR. If the Gradle build fails, the workflow uploads
available JUnit XML as `test-results` for one day; successful builds upload no
artifact.

The Maven repository routing in `build.gradle.kts` is part of the dependency
integrity contract. Local builds prefer `mavenLocal()`, remote internal
artifacts come only from the authenticated `service-common` GitHub Packages
repository, and Maven Central explicitly excludes `org.budgetanalyzer`. Keep
the matching setup guidance in [local development](local-development.md)
current when changing repository resolution.

## Bot pull request checks

For every Renovate pull request:

1. Keep the shared preset's no-automerge and dashboard-approval behavior.
2. Review the resolved dependency diff, release notes, Java 25 and Spring
   release-train compatibility, and whether the proposed direct dependency
   actually remediates any inherited alert.
3. Run the repository-required validation in order:

   ```bash
   ./gradlew clean spotlessApply
   ./gradlew clean build
   ```

Bot pull requests use the existing `build.yml` pull-request workflow. Forked or
otherwise untrusted pull requests do not receive package-read secrets, so a
failure to resolve `service-common` there is an unavailable-secret condition,
not evidence that the dependency is absent. Do not add
`pull_request_target` or expose package credentials to untrusted dependency
branches to bypass that boundary.

Graph generation failures are failures, not clean security results. Preserve
dependency-resolution and submission errors for triage; do not omit
configurations or the internal dependency to make the workflow pass.
