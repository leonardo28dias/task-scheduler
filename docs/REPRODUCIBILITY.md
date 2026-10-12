# Reproducibility Checklist (Phase 4)

Every claim in this project's documentation is backed by a reproducible command or test. This checklist lets any engineer rebuild the evidence in a clean environment.

## 1. Reproduce the build and tests

```bash
# 1. Clone / refresh the repository (branch: feature/base-structure)
git clone <repo> task_scheduler && cd task_scheduler
git checkout feature/base-structure

# 2. Reproducible build
./mvnw -B -U clean verify
#   -> exit code 0 means source compiles, tests pass, jars/aggregates built

# 3. Unit + integration suite (embedded PostgreSQL + Redis)
./mvnw -B test
#   -> surefire reports under target/surefire-reports/ (see .gitignore)

# 4. Integration smoke (api + worker profiles, in containers)
docker compose up --build -d
./scripts/wait-for-health.sh        # optional readiness helper
curl -fsS http://localhost:8080/api/v1/health | jq .status
```

## 2. Environment assumptions

| Concern | Guarantee |
|---------|-----------|
| Java | 21 (Oracle/OpenJDK or Temurin; machine is Ubuntu 24.04 + JDK 21.0.12) |
| Build | Maven Wrapper (`mvnw` checked in) + `pom.xml` (no snapshot executors) |
| Databases | Flyway `db/migration/V1..V4` applied to embedded PostgreSQL (Zonky) or container Postgres 16 |
| Cache | none — all tests isolated per class; no system-wide PostgreSQL required |
| Redis | Embedded/Standalone 7 on database 15 (`application-test.yml`), tested in suite |

## 3. Evidence and artifacts

| Artifact | Where it lives | How to trust it |
|----------|----------------|-----------------|
| Test reports | `target/surefire-reports/*.txt` | XML manifests (`TEST-*.xml`) with timestamps + PASS/FAIL |
| Container logs | `docker compose logs` | Health checks + API response on port 8080 |
| Load benchmark | `JobLoadTestRunner` (CLI), e.g. `java -cp target/classes org.example... JobLoadTestRunner http://localhost:8080 1200` | Reproducible by re-running after `test` phase |
| Benchmark results | `benchmark-results/` (gitignored) | `benchmark-dashboard.py` to plot throughput |

## 4. Rebuild from scratch

```bash
cd task_scheduler
git status --porcelain   # expect only docs/ and new test classes
./mvnw -B clean
../scripts/reset-env.sh  # optional: wipe target/ + rebuild images
```

## 5. Known non-reproducible parts

None in the tested surface. If a benchmark or container run varies, record the machine profile (`java -version`, `docker compose version`) in the results file rather than averaging it out.

## 6. Phase 4 validation fix (2026-10-12)

`WorkerPipelineIntegrationTest` initially failed because a background `WorkerService`
daemon stole the messages pushed by the test before its own `takeNext` call. Two
defects were fixed:

1. **Test** — `crashRecoveryRestoresJobAndPreservesAttempts` used
   `takeNext(..., Duration.ZERO)`, which issues Redis `BRPOPLPUSH ... 0` (block
   *forever*) on an empty list and hung the suite (~8h). Replaced with the existing
   non-blocking `moveOneNow` (`RPOPLPUSH`).
2. **Production (`AppProperties`)** — the nested leaf classes (`Worker`, `Queue`, …)
   exposed record-style accessors + setters but no JavaBean getter, so Spring Boot's
   `@ConfigurationProperties` relaxed binder silently dropped a *lone* property such
   as `app.worker.autostart=false`. This also broke the documented "zero-autostart
   daemon loop" feature in production. Fixed by adding `getX()` / `isX()` getters to
   every leaf. Verified: a single `app.worker.autostart=false` override now binds and
   keeps the worker daemon off.

Evidence: `./mvnw -B test` → `Tests run: 62, Failures: 0, Errors: 0, Skipped: 0`.
