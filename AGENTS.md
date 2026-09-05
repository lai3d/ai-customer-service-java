# Repository Guidelines

## Project Structure & Module Organization

This is a Java 21 Spring Boot 3.5 application built with Maven. Production code lives under `src/main/java/dev/merlionos/customerservice`, organized by feature: `chat`, `config`, `cost`, `observability`, `rag`, and `tools`. Runtime configuration is in `src/main/resources/application.yml`; the demo UI and bilingual FAQ corpus are in `static/index.html` and `faq/faq.json`. Tests mirror the production packages under `src/test/java`, with test-only configuration in `src/test/resources/application-test.yml`. Operational material lives in `docker/`, `k8s/`, and `docs/`.

## Build, Test, and Development Commands

- `./mvnw verify` compiles the project and runs the normal test suite. Docker must be available because integration tests use Testcontainers with pgvector.
- `./mvnw test -Dtest=FaqDocumentReaderTest` runs one test class; use `-Dtest='ClassName#methodName'` for one method.
- `docker compose up -d postgres` starts the development database. Then load `.env` and run `./mvnw spring-boot:run`.
- `docker compose up -d` starts Postgres, Jaeger, and the application stack.
- `./mvnw test -Dexcluded.test.groups= -Dtest='VirtualThreadBenchmark*'` explicitly runs the otherwise excluded benchmark.

Use `./mvnw clean verify` after deleting or renaming test resources to avoid stale files in `target/test-classes`.

## Coding Style & Naming Conventions

Use four-space indentation and standard Java naming: `PascalCase` types, `camelCase` methods and fields, and lowercase package names. Keep code grouped by feature and favor focused classes with constructor-injected dependencies. Match nearby Spring annotations and record usage. No formatter is configured, so preserve the existing layout and imports.

Keep the application on Spring MVC with virtual threads; do not introduce WebFlux beyond Reactor types used for SSE. Route model calls through `ChatClient`. Advisor ordering is a correctness constraint guarded by `AdvisorChainOrderTest`.

## Testing Guidelines

Tests use JUnit 5, Spring Boot Test, Reactor Test, and Testcontainers. Name unit tests `*Test` and database/application-level tests `*IntegrationTest`. Put test overrides in `application-test.yml` and activate the `test` profile. Add `@AutoConfigureObservability` when asserting metrics. New behavior and regressions should include focused tests; no external AI API key should be required.

## Commit & Pull Request Guidelines

Recent commits use concise, imperative, sentence-style subjects, such as `Trace every chat turn over OTLP`. Keep each commit scoped to one logical change. Pull requests should explain motivation and behavior, list verification commands, link relevant issues, and update `docs/` when measured behavior or architecture changes. Include screenshots for changes to the demo UI.

## Security & Configuration

Copy `.env.example` to `.env` for local secrets and never commit provider keys. Keep Kubernetes secrets out of source control; use `k8s/examples/secret.yaml` only as a template.
