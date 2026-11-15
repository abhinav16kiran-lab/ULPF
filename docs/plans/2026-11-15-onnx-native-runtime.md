# Plan: Onboarding fails in the container because the ONNX runtime cannot load

- **Date:** 2026-11-15
- **Status:** Done (2026-11-15)
- **Approved by / on:** Adarsh (team lead), 2026-11-15, in chat
- **File name:** `docs/plans/2026-11-15-onnx-native-runtime.md`
- **Found by:** the first container run of the item 13 performance kit (`docs/plans/2026-11-13-ingestion-throughput.md`). It blocks Phase 0, because no test source can be onboarded.

## 1. Goal

Onboarding a log source works in the container stack. A native library that cannot load makes layer 4 unavailable; it does not fail the request.

## 2. What I verified

Reproduced on 2026-11-15, on a fresh install (`./startup/start.sh`, Podman 3.4.4):
- `POST /v1/onboard/{username}` returns HTTP 500.
- The core engine logs `java.lang.UnsatisfiedLinkError: /tmp/onnxruntime-java.../libonnxruntime.so: Error loading shared library libstdc++.so.6: No such file or directory`. The stack goes from `ai.onnxruntime.OrtEnvironment.<clinit>`, through `ModelLifecycleManager.ensureLoaded` (`mapping/service/ModelLifecycleManager.java:109`), to the onboarding request.

Root cause, in 2 parts:
1. **The runtime image cannot run the ONNX runtime.**
   - `core-engine/Containerfile` uses `eclipse-temurin:21-jre-alpine`, which is musl-based and has no `libstdc++`.
   - `onnxruntime-1.20.0.jar` ships `libonnxruntime.so` built for glibc.
   - The model is tracked in git (`core-engine/models/all-MiniLM-L6-v2/model.onnx`), and `compose.yaml` mounts it into the container, so layer 4 tries to load the model on every new install.
2. **The layer fallback misses `Error`s.**
   - `MappingEngineOrchestrator.safelyRun` treats a failing layer as "no candidates", but catches only `Exception`.
   - `ModelLifecycleManager.ensureLoaded`, `EmbeddingClient.getEmbedding` and `EmbeddingMatchingService.matchWithFallback` also catch only `Exception`.
   - `UnsatisfiedLinkError` is a `LinkageError`, an `Error`, so it passes every one of these catches. `OnboardingService.generateProposalsFromSample` catches `Exception` too, so the request fails.

Why nobody saw it: the earlier end-to-end checks ran the jar on the host, which has glibc.

Other facts:
- The failed request leaves no data. `submitRequest` is `@Transactional`, and Spring rolls back on an `Error`.
- `eclipse-temurin:21-jre` (checked 2026-11-15) is Ubuntu 26.04.1 LTS with Java 21.0.13. It has `libstdc++.so.6`, `wget` (the compose health checks), `curl` and `pidof` (the perf kit). The image is 333 MB; the Alpine image is 210 MB.
- Layer 4 adds no matches today, because `mapping_embeddings` is never populated (backlog item 10). So when layer 4 is unavailable, the proposals do not change.

## 3. Proposed approach

1. **Runtime image:** `core-engine/Containerfile` stage 2 `FROM docker.io/eclipse-temurin:21-jre`. The build stage stays as it is, because the build only runs Maven.
2. **Fail safe:** `ModelLifecycleManager.ensureLoaded` also catches `LinkageError`.
   - It throws an `IllegalStateException` ("ONNX native runtime unavailable"), which every caller already handles as a layer failure.
   - It remembers the failure, so later calls fail at once without loading again, and it logs the error only once. After a failed class initialisation, every later call would throw `NoClassDefFoundError` anyway, and log a full stack trace each time.
   - The `OrtEnvironment` lookup moves behind a package-private supplier, so a test can simulate the failure.
3. **Docs:** a row in `docs/ARCH/ARCHITECTURE.md` with the reason and the rejected alternatives. `README.md` already says Java 21; I will check whether it names Alpine.

Rejected:
- **Add `libstdc++` to the Alpine image (`apk add libstdc++`).** The library is built for glibc. On musl it needs a compatibility layer (`gcompat`), which ONNX Runtime does not support. I did not test it.
- **Catch `Error` or `Throwable` in `safelyRun`.** It would also hide `OutOfMemoryError` and `StackOverflowError`. `LinkageError` is the one case that means "this layer cannot run here".
- **Remove the model mount.** Layer 4 would then fail with "model file not found" (an `Exception`, which is handled), but it hides the image problem, and item 10 may still use the runtime.

## 4. Test plan (written first)

| Test | Class | Expected to fail before because… |
|---|---|---|
| The environment supplier throws `UnsatisfiedLinkError` → `ensureLoaded` throws `IllegalStateException`, not an `Error` | `ModelLifecycleManagerTest` | the `Error` passes the `catch (Exception)` |
| After that failure, a second call throws at once, and the supplier is called only once | `ModelLifecycleManagerTest` | no failure latch |
| With a model manager whose runtime cannot load, the orchestrator still returns proposals from layers 1-3, for a field that reaches layer 4 | `MappingEngineOrchestratorTest` (or the nearest existing test) | the `Error` escapes `safelyRun` |
| Container check (manual, recorded in the Outcome): on the rebuilt stack, onboarding returns 201, and the log shows no `UnsatisfiedLinkError` | — | the image has no `libstdc++` |

## 5. Files to change

- `core-engine/Containerfile` — the runtime base image.
- `core-engine/src/main/java/com/ulpf/mapping/service/ModelLifecycleManager.java` — catch and remember `LinkageError`; the environment supplier; the Javadoc.
- Tests: `ModelLifecycleManagerTest` and an orchestrator test.
- `docs/ARCH/ARCHITECTURE.md` — the decision row.

## 6. Steps

- [x] 1. Write the tests, confirm red. All 3 failed with the escaping `UnsatisfiedLinkError`, after a test seam (a package-private constructor that takes the `OrtEnvironment` supplier) made them compile.
- [x] 2. Fix `ModelLifecycleManager`. Green.
- [x] 3. Change the runtime image. Full backend suite: 403 tests, 0 failures (400 + 3).
- [x] 4. Rebuild the stack, onboard in the container, and record the result (Outcome).
- [x] 5. Docs. PR.

## 7. Risks

- **The image is 123 MB larger.** That is accepted, because the alternative is a stack that cannot onboard.
- **glibc and musl can perform differently.** The item 13 baseline is measured on the new image, which is the image users run.
- **`eclipse-temurin:21-jre` is a moving tag,** as `21-jre-alpine` was. Pinning the base images is a separate topic.

## 8. Outcome

- **Container check (2026-11-15, Podman 3.4.4):** after the rebuild, the core engine runs on Ubuntu 26.04.1 LTS with `libstdc++.so.6`. ONNX Runtime loaded the model in 248 ms. All 5 test sources of the item 13 kit were onboarded (201), approved and given API keys. The log has no `UnsatisfiedLinkError`.
- **Image size:** core-engine 472 MB (was 348 MB).
- **Test seam:** the package-private constructor `ModelLifecycleManager(String, Supplier<OrtEnvironment>)` was not in the plan.
- **Doc fix found in passing:** `ARCHITECTURE.md` §4 said Spring Boot 3.4; the build uses 4.1.0. Corrected.
- **Not done:** the orchestrator test covers layer 4 through `EmbeddingClient` and `EmbeddingMatchingService`. `OnboardingService` itself has no test with a broken runtime, because its tests use the 8-argument constructor without layer 4.

