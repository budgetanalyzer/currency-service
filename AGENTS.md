# Currency Service Agent Instructions

## Tree Position

**Archetype:** service  
**Scope:** Budget Analyzer ecosystem  
**Role:** Manages currencies and exchange rates with external provider
integration.

### Relationships and Boundaries

- Consume shared Spring patterns from `../service-common/`.
- Treat `../orchestration/` as the owner of ecosystem coordination and runtime
  infrastructure.
- Discover peer services instead of maintaining a static list:

  ```bash
  find .. -mindepth 1 -maxdepth 1 -type d -name '*-service' -print | sort
  ```

- Read this repository, `../service-common/`, and `../orchestration/docs/` as
  needed. Read `../ai-session-handler/docs/plan-format.md` only when producing
  an AI Session Handler plan.
- Write only within this repository. Do not modify sibling repositories.

## Operating Rules

- Do not use agent or subagent tools for code exploration. Inspect the workspace
  directly with `rg`, `find`, and file reads.
- Never run Git write commands, including `commit`, `push`, `checkout`, `reset`,
  or branch manipulation, unless the user explicitly requests them. The user
  controls Git operations.
- Keep implementations simple. Choose the most direct design that handles
  realistic inputs, states, and failure modes without weakening security, data
  integrity, or required behavior.
- Put request shape and syntax validation at the API boundary. Put business
  invariants, ownership, persistence state, and cross-entity rules in services.
- Do not duplicate validation across layers unless another caller can bypass the
  validated boundary or the service owns the rule.
- Add defensive branches only for states that can plausibly arise and have a
  useful response. Handle failures at external and asynchronous boundaries
  explicitly.
- Never commit credentials or replace missing infrastructure, credentials, or
  shared artifacts with hard-coded workarounds.

## Discovery

Use discovery commands instead of relying on copied inventories:

```bash
# Repository structure and build entry points
find . -maxdepth 2 -type f -not -path './.git/*' | sort
./gradlew tasks

# Java packages, controllers, and routes
find src/main/java/org/budgetanalyzer/currency -type d | sort
rg -n '@(Request|Get|Post|Put|Patch|Delete)Mapping' src/main/java

# Providers, domain events, and distributed patterns
find src/main/java -type f -name '*Provider.java' -o -path '*/domain/event/*.java'
rg -n '@Scheduled|@SchedulerLock|@Cacheable|@CacheEvict|@ApplicationModuleListener' src/main/java

# Runtime configuration and environment variables
rg -n '^currency-service:|fred:|shedlock|cache|rabbit|redis' src/main/resources/application.yml
rg -n '\$\{[^}]+}' src/main/resources/application.yml
```

## Sources of Truth

- Use [README.md](README.md) for the repository purpose, human entry points, and
  documentation index.
- Read [docs/local-development.md](docs/local-development.md) before changing
  prerequisites, bootstrap behavior, local run commands, TLS setup, or developer
  API access.
- Use [src/main/resources/application.yml](src/main/resources/application.yml)
  for active runtime defaults and property names. Read
  [docs/configuration.md](docs/configuration.md) when changing environment
  variables, infrastructure integration, caching, schedules, messaging, or
  security configuration.
- Read [docs/api/README.md](docs/api/README.md) and inspect the controllers before
  adding, removing, or reshaping a public endpoint. Treat the runtime OpenAPI
  document as the generated API contract.
- Read [docs/domain-model.md](docs/domain-model.md) before changing entities,
  relationships, domain events, or business rules.
- Read [docs/fred-integration.md](docs/fred-integration.md) before changing the
  FRED client, provider behavior, import schedule, or rate-limit handling.
- Read [docs/advanced-patterns-usage.md](docs/advanced-patterns-usage.md) before
  changing providers, distributed locks, caching, domain-event delivery, or
  messaging. Consult
  [service-common advanced patterns](../service-common/docs/advanced-patterns.md)
  when changing the underlying cross-service pattern.
- Use [build.gradle.kts](build.gradle.kts),
  [gradle/libs.versions.toml](gradle/libs.versions.toml), and the checked-in
  Gradle wrapper as the authority for plugins, dependencies, toolchains,
  coverage gates, and build behavior.
- Read [docs/dependency-automation.md](docs/dependency-automation.md) before
  changing Renovate configuration, dependency graph submission, build workflow
  triggers, trial evidence measurement, caches, or artifact uploads.
- Read
  [service-common Spring Boot conventions](../service-common/docs/spring-boot-conventions.md)
  before changing architecture layers, dependency injection, entities, or HTTP
  response patterns. Read
  [service-common error handling](../service-common/docs/error-handling.md) before
  adding exception flows or changing API error responses.
- Read
  [service-common artifact resolution](../orchestration/docs/development/service-common-artifact-resolution.md)
  when local or CI builds cannot resolve shared artifacts. Do not modify or
  publish from `../service-common/` from this repository context.

## Architecture and Security Rules

- Keep controllers thin and delegate application behavior to services.
  Controllers must never import repositories.
- Keep consumers thin and delegate to services. Consumers must never import
  repositories.
- Keep transaction boundaries in the service layer and persistence behind
  repositories.
- Use Jakarta Persistence APIs only. Never import `org.hibernate.*` or depend on
  Hibernate-specific behavior.
- Extend the shared auditable or soft-deletable base entity that matches the
  domain lifecycle; consult the shared Spring Boot conventions before creating
  an entity.
- Access external exchange-rate systems through `ExchangeRateProvider`. The
  service layer must never reference FRED-specific clients or types.
- Publish domain events from services. Do not inject message publishers into
  services; bridge domain events to external messages in listeners.
- Apply `@SchedulerLock` to scheduled work that must execute once across pods.
- Cache read paths only where the documented cache strategy calls for it, and
  evict affected cache entries after imports or mutations.
- Preserve claims-header-based authorization and enforce endpoint permissions
  with `@PreAuthorize`. Do not trust client-supplied identity or permission
  headers outside the platform's validated gateway path.
- Treat role definitions and atomic permission ownership as permission-service
  concerns. Do not redefine the ecosystem RBAC model in this repository.

## Java and Test Standards

Before writing or modifying Java, read and apply
[code-quality-standards.md](../service-common/docs/code-quality-standards.md).
Do not skip this step. In particular, use `var` where the standard requires it,
use descriptive names, avoid wildcard imports, and end the first sentence of
Javadoc with a period.

Before writing or changing tests, read the relevant section of
[testing-patterns.md](../service-common/docs/testing-patterns.md):

- Read the unit-testing guidance for isolated service or utility tests.
- Read the integration and Testcontainers guidance for persistence, messaging,
  cache, or application-context tests.
- Test correct behavior and edge cases. Fix defects instead of testing around
  them.
- Do not weaken, delete, or disable existing tests to make a change pass.
- Keep reusable test values in the repository's test fixtures or constants
  instead of scattering magic values.

## Prerequisites and Development Workflow

Before implementing a plan or feature:

1. Search the relevant owner documentation for explicit prerequisites.
2. Verify required tools, infrastructure, credentials, and shared artifacts are
   available.
3. If a prerequisite is missing, stop and inform the user. Do not invent a
   workaround or bypass the dependency.
4. Satisfy the prerequisite in its owning repository or workflow before
   returning to the original task. Do not write outside this repository without
   separate authorization.

Use [docs/local-development.md](docs/local-development.md) for the current local
setup and run flow. Use the checked-in Gradle wrapper for build tasks.

When creating an implementation or execution plan for AI Session Handler, read
and follow the
[AI Session Handler plan format](../ai-session-handler/docs/plan-format.md). Use
its canonical template, replace every placeholder, and retain the numbered
`## Phase N: Title` headings.

Run a specific plan from the repository root with:

```bash
ai-session-handler run \
  --plan docs/plans/PLAN.md \
  --max-phases 999 \
  --quiet \
  --agent-cmd "../ai-session-handler/.venv/bin/ai-session-handler-codex-high --model MODEL"
```

Omit `--model MODEL` from the quoted agent command to use the wrapper's
configured or default model.

## Validation

For Java or build changes, run the repository's required formatting and build
gates in sequence:

```bash
./gradlew clean spotlessApply
./gradlew clean build
```

- Run focused tests while developing, then run the full build before declaring
  Java work complete.
- For documentation-only changes, verify every changed relative link resolves
  and every documented command is syntactically plausible from the repository
  root.
- If a required verifier cannot run because a tool, credential, service, or
  infrastructure dependency is unavailable, report that explicitly. Do not
  claim full verification.

## Documentation Discipline

Keep the nearest affected documentation current in the same work:

- Update `AGENTS.md` when agent instructions, guardrails, workflows, or discovery
  commands change. Before editing it, read and apply the
  [AGENTS.md checkstyle](../orchestration/docs/agents-md-checkstyle.md).
- Update `README.md` when repository purpose, setup, usage, or human onboarding
  changes.
- Update `docs/` when architecture, configuration, APIs, behavior, operations,
  or design rationale changes.
- Keep detailed recurring information in one owner document and link to it
  instead of copying it into `AGENTS.md`.
- Do not leave required documentation updates as follow-up work.

## Honest Discourse

- Say directly when a proposal conflicts with repository constraints or lacks
  required detail.
- Distinguish novel findings from conclusions that are obvious in retrospect.
- Ask for concrete constraints when a claim is too vague to act on safely.
- Skip praise, canned validation, and unnecessary preambles.
