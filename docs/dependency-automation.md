# Dependency Automation

The workspace-wide operating policy, activation steps, cost boundary, and
failure triage are owned by
[orchestration's dependency automation guide](../../orchestration/docs/dependency-automation.md).
This document records only the `currency-service` integration and review checks.

## Update discovery

`renovate.json` extends the shared Budget Analyzer preset. Renovate's native
Gradle, Gradle Wrapper, Dockerfile, and GitHub Actions managers discover this
repository's version catalog, build script, wrapper distribution, base images,
and workflow actions. The Gradle catalog extraction includes the declared
`serviceCommon` version used by `spring-cloud-platform`, `service-core`, and
`service-web`.

`service-common` is hosted in GitHub Packages. Extraction does not prove that
Renovate can look up that private Maven coordinate. Before activation, configure
the Mend Renovate Community App's supported encrypted Maven credentials and
verify an authenticated lookup; never put a package token in this repository or
the shared preset. The app must keep direct, maintained-line, and later-major
proposals visible where each datasource supports them.

The Phase 4 local extraction found 57 dependency occurrences across eight files:
37 Gradle occurrences in three files, one wrapper occurrence, three Dockerfile
occurrences, and 16 GitHub Actions occurrences in three workflows. Public
Gradle, wrapper, and image lookups produced candidate updates. GitHub Actions
lookups and the three `org.budgetanalyzer` Maven coordinates remain
authenticated hosted checks; the local runner had no GitHub or package-read
token, so those checks are pending rather than passed.

Renovate proposes changes only to direct declarations. Versions inherited from
the Spring Boot, Spring Cloud, and Spring Modulith BOMs remain represented by
the resolved dependency graph described below. A direct Spring Boot or
`serviceCommon` proposal is not proof that every inherited vulnerability is
fixed.

## Resolved dependency graph

`.github/workflows/dependency-submission.yml` preserves graph submission on
trusted `main` pushes, weekly runs, and `main` dispatches. It also accepts the
exact `dependency-automation-trial` ref. Trial runs first perform the complete
authenticated build and then use the official Gradle generation-only graph path
until both the protected trial ref is the current default and the trial graph
submission variable is enabled. It resolves all projects and all resolvable
configurations so application, runtime, build, and test dependency trees are
included. Do not add configuration filters without proving equivalent coverage.

Remote resolution uses `SERVICE_COMMON_PACKAGES_USERNAME` and
`SERVICE_COMMON_PACKAGES_READ_TOKEN`, exposed to Gradle as `GITHUB_ACTOR` and
`GITHUB_TOKEN`. These package-read credentials are distinct from
`${{ github.token }}`, which the action uses to submit the graph with the job's
only elevated permission, `contents: write`. The snapshot is submitted directly
and is not retained as an artifact or published as a Build Scan.

The workflow must fail when package credentials are missing, the pinned
`service-common` artifacts cannot be resolved, graph generation is incomplete,
or GitHub rejects submission. Do not substitute Maven Local, omit the internal
dependency, filter failed configurations, or treat graph-generation failure as
a clean security result. A hosted run on `main` is the final proof that GitHub
accepted the submission and can produce Dependabot alerts from it.

## Phase 12 branch measurement controls

`build.yml` accepts trial-branch pushes and pull requests based on either `main`
or the exact trial branch. Trial builds measure the application JAR, test
results, and build failure log with optional caches and uploads initially off.
The graph workflow measures its complete generated report and resolution log.
Trial schedule, cache, upload, and submission expansion follows the variables in
the
[orchestration trial workflow policy](../../orchestration/docs/dependency-automation.md#trial-workflow-controls).
Any enabled trial upload is one sealed archive retained for one day and must fit
beneath the 25 MiB cap. Production `main` uploads and graph submission are
unchanged.

## Bot pull request checks

Every Renovate pull request remains non-automerged. Review the resolved
dependency diff, release notes, Java 25 and Spring release-train compatibility,
and whether the proposed direct dependency actually remediates any inherited
alert. Major updates remain visible in the Dependency Dashboard and require
approval.

Run the required repository validation in order:

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

## Phase 12 authenticated evidence handoff

Phase 4 intentionally used no GitHub, GitHub Packages, or Mend credentials.
The following checks for `currency-service` remain pending Phase 12 and must be
performed by the operator in the trusted hosted environments named below:

1. Publish the orchestration shared preset, install the Mend Renovate Community
   App for this repository, grant its required alert-read access, and configure
   its supported encrypted Maven host rule for GitHub Packages. Run the hosted
   Renovate job and retain its log or Dependency Dashboard URL. The expected
   proof is successful resolution of the published preset, GitHub Actions
   references, and the `org.budgetanalyzer` declarations in
   `gradle/libs.versions.toml`, with no authentication, configuration, timeout,
   uncaught-exception, or lookup failure. The credential-free Phase 4 run
   extracted all three internal declarations and 57 total occurrences, but
   reported that a GitHub token was required for Actions lookups and could not
   authenticate private Maven lookups. The final revalidation returned 45
   public candidate branches (an earlier attempt returned 47); both are only
   partial, time-sensitive evidence.
2. From the trusted `main` branch, dispatch or observe
   `.github/workflows/dependency-submission.yml`. The operator must leave
   `SERVICE_COMMON_PACKAGES_USERNAME` and
   `SERVICE_COMMON_PACKAGES_READ_TOKEN` in the repository's secret store and
   must not expose their values to an agent. Retain the successful workflow URL
   and GitHub dependency-graph snapshot as proof that all three preflight POM
   requests succeeded, Gradle remotely resolved the application, runtime, and
   test configurations, and GitHub accepted the submission. The credential-free
   Phase 4 snapshot contained only 87 build/tool coordinates and zero
   `org.budgetanalyzer`, Spring Security, Tomcat, Netty, or PostgreSQL
   coordinates because `serviceCommon` 0.0.16 was unavailable remotely. That
   partial snapshot is a recorded limitation, not submission proof. Historical
   build run `34027551741` used the same internal version successfully, but it
   predates this workflow and is also not submission proof.
3. On an actual Renovate pull request, retain the `build.yml` run that executes
   `./gradlew build` with the existing package-read secrets. The expected proof
   is either a successful build or a specific dependency compatibility failure;
   missing-secret or package-authentication output is not dependency validation.
   Do not move this check to `pull_request_target` or disclose credentials to an
   untrusted fork.
4. In GitHub administration, confirm the dependency graph and Dependabot alerts
   are enabled while overlapping version-update and security-update pull
   requests remain disabled. After the hosted submission, retain the accepted
   snapshot and resulting alert URLs, including inherited Spring Framework,
   Spring Security, Jackson, Tomcat, and Netty findings where applicable. An
   absent alert is meaningful only after the complete graph has been accepted.

The orchestration coverage report owns the final cross-repository acceptance
record. Phase 12 must copy these URLs and any explained misses there; no Phase 4
local result activates automation or establishes vulnerability-review parity.
