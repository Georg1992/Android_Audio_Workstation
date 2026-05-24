# clang-tidy native engine audit

Kotlin uses Detekt; native code uses **clang-tidy** in **audit-only** mode (no auto-fix, no CI gate).

## Prerequisites

- Android SDK + NDK (version must match `ndkVersion` in `app/build.gradle`, currently **25.2.9519653**)
- `local.properties` with `sdk.dir=...`
- A debug native build so AGP writes `compile_commands.json`:

```bash
./gradlew :app:assembleDebug
```

AGP places the compilation database at:

`app/.cxx/tools/debug/<abi>/compile_commands.json`  
(recommended ABI: **arm64-v8a**, matches primary device target)

## Run locally

**Gradle (Windows):**

```powershell
./gradlew :app:clangTidyAudit
```

**PowerShell script:**

```powershell
./app/src/main/cpp/run-clang-tidy-audit.ps1
./app/src/main/cpp/run-clang-tidy-audit.ps1 -IncludeTests
```

**Linux/macOS:**

```bash
chmod +x app/src/main/cpp/run-clang-tidy-audit.sh
./app/src/main/cpp/run-clang-tidy-audit.sh
INCLUDE_TESTS=1 ./app/src/main/cpp/run-clang-tidy-audit.sh
```

Report: `build/reports/clang-tidy/engine-audit.txt`

## Configuration

- Checks: `app/src/main/cpp/.clang-tidy`
- Scope: `engine/*.cpp` (optional `tests/*.cpp` with `-IncludeTests` / `INCLUDE_TESTS=1`)
- Header filter: project `engine/` headers only (Oboe/FetchContent excluded)

## Limits

- **clang-tidy does not prove** the RingBuffer / hot-join lifetime model is safe.
- Analyzer concurrency coverage is limited; prefer **Thread Sanitizer** stress tests for cross-thread issues.
- Passing clang-tidy is necessary for hygiene, not sufficient for realtime audio correctness.

## CI

Not wired in `.github/workflows` yet. Add a non-blocking job only after triaging the first report.
