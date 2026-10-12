# AI Usage Policy (Phase 4)

This section documents how AI assistance was used to build, validate, and document the job queue engine, and the rules that apply to any future work in this repository.

## 1. Where AI was used

| Area | How AI was used |
|------|-----------------|
| Phase 1–3 implementation | Drafted integration tests, corrected schema/context issues, generated the architecture overview and project layout |
| Phase 4 validation | Designed and implemented `WorkerPipelineIntegrationTest`, interpreted surefire/Flyway failures, selected bug-fix and documentation patterns |
| Documentation | Wrote `docs/ARCHITECTURE.md`, `docs/PROJECT_STRUCTURE.md`, `docs/AI_POLICY.md`, `docs/REPRODUCIBILITY.md` |

No AI tool was used to:
- change production business rules;
- bypass review of generated code;
- replace the developer's understanding of the codebase (every generated file was read and manually validated);
- touch files outside this repository.

## 2. Workflow rules

1. **Read before generating.** The generated code must match the existing patterns and conventions of the file (package, imports, annotations, error handling).
2. **Generate in reviewable chunks.** Each edit is kept small enough to review line-by-line (editor tool limit ~6000 characters).
3. **Verify with the toolchain, not only by eye.** Compilation errors, surefire reports, and the running application are the source of truth.
4. **Fix root cause.** If the test tells us something is missing (e.g., Flyway disabled by the `worker` profile), the fix lives in the test configuration, not in a test-only workaround.
5. **Document the state.** Every new file in `docs/` is reviewed and committed with its change.

## 3. Generated artifacts

- `src/test/java/com/example/jobqueue/worker/WorkerPipelineIntegrationTest.java` — new in this phase.
- `docs/ARCHITECTURE.md`, `docs/PROJECT_STRUCTURE.md`, `docs/AI_POLICY.md`, `docs/REPRODUCIBILITY.md` — new documentation.

## 4. Attachments

- Repository: `https://github.com/open-telemetry/opentelemetry-collector-contrib` (upstream reference, this repo is a fork of the base structure).
- AI model: Solar (Upstage) — used as an assistant, not as a substitute for engineering judgment.
