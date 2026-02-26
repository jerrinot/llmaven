# Maven Silent Extension (MSE) — Design Document v2

## 1. Problem Statement

Standard Maven output is optimized for human developers watching a terminal. A typical multi-module build generates thousands of lines of lifecycle headers, download progress bars, and success banners. For AI coding agents operating within a finite context window, this is actively harmful:

- A clean 12-module build can emit 2,000+ lines before any meaningful signal.
- A failed build buries the actionable error in hundreds of lines of ceremony.
- Token budgets are consumed by noise, degrading the agent's ability to reason about the actual problem.

**MSE transforms Maven's output into a dense, machine-actionable stream: silent on success, precise on failure.**

---

## 2. Design Principles

1. **Zero project modification.** The extension must never require changes to `pom.xml`, Surefire configuration, or any project file. It is injected externally by the agent.
2. **Fail-safe transparency.** If the extension encounters an unexpected state, it must fall back to passthrough mode, never swallow output silently.
3. **Phased complexity.** The architecture is layered so that each phase delivers standalone value. Later phases add coverage for edge cases without destabilizing earlier work.
4. **Forked-process awareness.** Many critical plugins (Surefire, Failsafe, exec-maven-plugin) fork JVMs or OS processes. `System.setOut()` cannot reach these. The design must account for this from the start, not as an afterthought.

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Maven Reactor                      │
│                                                      │
│  ┌──────────────┐   ┌──────────────┐                │
│  │ MojoExecution │   │ MojoExecution │  ...          │
│  │ (compiler)    │   │ (surefire)   │               │
│  └──────┬───────┘   └──────┬───────┘                │
│         │                   │                        │
│         ▼                   ▼                        │
│  ┌─────────────────────────────────────┐            │
│  │         SilentEventSpy              │            │
│  │                                     │            │
│  │  ┌─────────────┐ ┌───────────────┐  │            │
│  │  │ StreamCapture│ │ ArtifactParser│  │            │
│  │  │ (in-process) │ │ (post-mojo)  │  │            │
│  │  └─────────────┘ └───────────────┘  │            │
│  │                                     │            │
│  │  ┌─────────────────────────────────┐│            │
│  │  │       OutputFormatter           ││            │
│  │  │  (machine-actionable emitter)   ││            │
│  │  └─────────────────────────────────┘│            │
│  └─────────────────────────────────────┘            │
└─────────────────────────────────────────────────────┘
```

The extension has three cooperating subsystems:

- **StreamCapture** intercepts in-process stdout/stderr for plugins that write directly to the console.
- **ArtifactParser** reads structured output files (Surefire XML reports, compiler error output) after a mojo completes, regardless of whether that mojo forked a JVM.
- **OutputFormatter** emits the final, condensed output to the agent.

---

## 4. The Forked-Process Problem

This is the single most important architectural constraint and deserves dedicated treatment.

### Why `System.setOut()` Is Insufficient

Many Maven plugins do not write to the in-process stdout:

| Plugin | Forks? | Mechanism |
|---|---|---|
| `maven-surefire-plugin` | **Yes** (default `forkCount=1`) | Spawns separate JVM(s) for test execution. Test output goes to forked JVM's stdout and to XML/text report files in `target/surefire-reports/`. |
| `maven-failsafe-plugin` | **Yes** | Same forking behavior as Surefire. |
| `exec-maven-plugin` | **Yes** | Spawns arbitrary OS processes via `ProcessBuilder`. |
| `maven-compiler-plugin` | No (usually) | Runs in-process, but since Java 9+ may use the `javax.tools` API which writes to its own streams. |
| `frontend-maven-plugin` | **Yes** | Spawns Node.js / npm processes. |

Redirecting `System.out` in the Maven JVM captures none of the forked-process output. This is not an edge case — **Surefire is the most failure-prone plugin in a typical build, and its output is always out of reach of in-process stream capture.**

### The Solution: Post-Execution Artifact Parsing

Instead of trying to capture forked-process stdout in flight, MSE reads the structured artifacts that these plugins already produce:

- **Surefire/Failsafe:** Parse `target/surefire-reports/TEST-*.xml` (JUnit XML format). These contain test names, failure messages, stack traces, stdout/stderr captured per-test, and timing data. This is strictly more structured than raw console output.
- **Compiler:** Parse the output of `maven-compiler-plugin` which writes errors to the Maven log in a well-known `[ERROR] /path/File.java:[line,col] message` format. In-process stream capture works here since the compiler typically runs in the Maven JVM.
- **exec-maven-plugin:** Accept as a known gap. Document that forked arbitrary processes are not captured. The agent can be instructed to run such commands directly if their output is needed.

This approach is more reliable than stream hijacking because it reads finalized, structured data rather than racing against concurrent console writes.

---

## 5. Component Design

### 5.1 SilentEventSpy

The central coordinator. Implements `AbstractEventSpy` and manages the lifecycle.

```java
@Named("silent-spy")
@Singleton
public class SilentEventSpy extends AbstractEventSpy {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final MojoBufferManager bufferManager = new MojoBufferManager();
    private final ArtifactParser artifactParser = new ArtifactParser();
    private final OutputFormatter formatter = new OutputFormatter();

    private volatile boolean active = false;

    @Override
    public void init(Context context) {
        active = "true".equalsIgnoreCase(System.getenv("MSE_ACTIVE"));
        if (active) {
            System.setOut(new MojoDispatchPrintStream(originalOut, bufferManager));
            System.setErr(new MojoDispatchPrintStream(originalErr, bufferManager));
        }
    }

    @Override
    public void onEvent(Object event) {
        if (!active || !(event instanceof ExecutionEvent)) return;

        ExecutionEvent ee = (ExecutionEvent) event;
        switch (ee.getType()) {
            case SessionStarted:
                formatter.emitSessionStart(ee, originalOut);
                break;
            case MojoStarted:
                bufferManager.beginCapture(mojoKey(ee));
                break;
            case MojoSucceeded:
                handleMojoSuccess(ee);
                break;
            case MojoFailed:
                handleMojoFailure(ee);
                break;
            case SessionEnded:
                formatter.emitSessionSummary(ee, originalOut);
                break;
            default:
                break; // Swallow all other lifecycle noise
        }
    }

    @Override
    public void close() {
        if (active) {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
```

### 5.2 MojoBufferManager

Manages per-mojo output buffers. This is the key to handling parallel builds (`mvn -T`).

**Thread attribution strategy:** When a mojo starts, the `EventSpy` registers the current thread with the active mojo key. The `MojoDispatchPrintStream` checks `Thread.currentThread()` against this registry to route output to the correct buffer.

```java
public class MojoBufferManager {
    // Thread -> MojoKey mapping for parallel build support
    private final ConcurrentHashMap<Long, String> threadToMojo = new ConcurrentHashMap<>();
    // MojoKey -> captured output
    private final ConcurrentHashMap<String, BoundedBuffer> mojoBuffers = new ConcurrentHashMap<>();

    public void beginCapture(String mojoKey) {
        long tid = Thread.currentThread().getId();
        threadToMojo.put(tid, mojoKey);
        mojoBuffers.put(mojoKey, new BoundedBuffer(128 * 1024)); // 128KB per mojo
    }

    public BoundedBuffer getBufferForCurrentThread() {
        String key = threadToMojo.get(Thread.currentThread().getId());
        return key != null ? mojoBuffers.get(key) : null;
    }

    public BoundedBuffer endCapture(String mojoKey) {
        // Remove thread mapping; return buffer for processing or discard
        threadToMojo.values().remove(mojoKey);
        return mojoBuffers.remove(mojoKey);
    }
}
```

**Buffer sizing:** 128KB per mojo, not a shared 50KB. Rationale: a single compiler error dump with full classpath context can exceed 50KB. Per-mojo isolation also prevents a noisy plugin from evicting another plugin's error context. With a typical parallel build running 4 mojos concurrently, peak memory is ~512KB — negligible.

**Truncation strategy:** `BoundedBuffer` keeps the first 48KB and the last 80KB, discarding the middle. Error output almost always has the root cause at the top (compiler error, assertion failure) and the build context at the bottom (reactor summary, "BUILD FAILURE" block). The middle is typically repetitive warnings or cascading errors.

### 5.3 ArtifactParser

Extracts structured information from plugin-generated files after mojo completion.

```java
public class ArtifactParser {

    /**
     * Parse Surefire/Failsafe XML reports for a given module.
     * Returns structured test results regardless of JVM forking.
     */
    public TestSummary parseSurefireReports(File baseDir) {
        File reportsDir = new File(baseDir, "target/surefire-reports");
        if (!reportsDir.isDirectory()) return TestSummary.EMPTY;

        int total = 0, failures = 0, errors = 0, skipped = 0;
        List<TestFailure> failureDetails = new ArrayList<>();

        for (File xml : reportsDir.listFiles((d, n) -> n.endsWith(".xml"))) {
            // Parse JUnit XML: <testsuite tests="X" failures="Y" errors="Z">
            // Extract <testcase> elements with <failure> or <error> children
            // Capture: class name, method name, failure message, first 20 lines of stack trace
            JUnitReport report = parseJUnitXml(xml);
            total += report.tests;
            failures += report.failures;
            errors += report.errors;
            skipped += report.skipped;
            failureDetails.addAll(report.failureDetails);
        }

        return new TestSummary(total, failures, errors, skipped, failureDetails);
    }

    /**
     * Extract compiler error locations from captured Maven log output.
     */
    public List<CompilerError> parseCompilerErrors(String capturedOutput) {
        // Match pattern: [ERROR] /absolute/path/File.java:[line,col] error: message
        // Return structured list of file, line, column, message
    }
}
```

**Why parse XML instead of Surefire console output?**

Surefire's console output is emitted inside the forked JVM and streamed back to Maven over a proprietary protocol. Even if we captured it, the format varies across Surefire versions and `reportFormat` settings. The XML reports are:
- Always generated (unless explicitly disabled, which is rare).
- Stable in schema across Surefire 2.x and 3.x.
- Richer than console output (they include per-test stdout/stderr capture, timing, and the full failure message).

### 5.4 OutputFormatter

Emits machine-actionable output to the original stdout.

**Success output (entire build):**
```
MSE:OK modules=12 tests=342/342 time=47s warnings=3
```

**Mojo failure output:**
```
MSE:FAIL maven-compiler-plugin:compile @ my-module
MSE:ERR src/main/java/com/example/App.java:42:18 incompatible types: String cannot be converted to int
MSE:ERR src/main/java/com/example/App.java:87:5 cannot find symbol: method frobnicate()
MSE:BUFFERED_OUTPUT_BEGIN
  [captured stderr from compiler, if any]
MSE:BUFFERED_OUTPUT_END
```

**Test failure output:**
```
MSE:FAIL maven-surefire-plugin:test @ my-module
MSE:TESTS total=342 passed=339 failed=2 errors=1 skipped=0
MSE:TEST_FAIL com.example.AppTest#testParseInput
  expected:<42> but was:<0>
  at com.example.AppTest.testParseInput(AppTest.java:27)
MSE:TEST_FAIL com.example.AppTest#testEdgeCase
  NullPointerException: Cannot invoke "String.length()" on null
  at com.example.App.process(App.java:55)
  at com.example.AppTest.testEdgeCase(AppTest.java:34)
MSE:TEST_ERROR com.example.ServiceTest#testConnection
  java.net.ConnectException: Connection refused
  at com.example.ServiceTest.testConnection(ServiceTest.java:18)
```

**Format rationale:**
- `MSE:` prefix on every line enables trivial grep/filtering if MSE output is mixed with other tool output.
- Structured `key=value` pairs on summary lines for reliable parsing.
- Stack traces are included but truncated to the first frame in user code (skip framework frames from JUnit, Surefire, reflection).
- No ANSI color codes, no box-drawing characters, no indentation art.

---

## 6. Activation and Injection

### Injection Without Project Changes

The agent injects MSE at invocation time:

```bash
# Set the environment flag and extension classpath
export MSE_ACTIVE=true
mvn -Dmaven.ext.class.path=/opt/agent-tools/maven-silent-extension.jar clean verify
```

No changes to `pom.xml`, `.mvn/extensions.xml`, or `settings.xml` are required.

### Fail-Safe Deactivation

If `MSE_ACTIVE` is not set, the extension's `init()` method sets `active = false` and all `onEvent()` calls are no-ops. The extension JAR is inert on the classpath. This allows agents to inject the JAR unconditionally and toggle behavior via environment variable.

### Compatibility Matrix

| Maven Version | Expected Compatibility | Notes |
|---|---|---|
| 3.6.x | Full | `AbstractEventSpy` stable since Maven 3.1 |
| 3.8.x | Full | |
| 3.9.x | Full | SLF4J 2.x migration does not affect EventSpy API |
| 4.0.x | **Needs validation** | Maven 4 restructures the API module. `EventSpy` is likely preserved but package may change. |

---

## 7. Edge Cases and Known Limitations

### 7.1 Parallel Builds (`-T`)

**Handled.** The `MojoBufferManager` uses thread-to-mojo mapping. However, there is a residual risk: if a plugin creates its own thread pool (e.g., `maven-shade-plugin` during dependency analysis), output from those worker threads will not be attributed to any mojo and will fall through to a shared "unattributed" buffer. This buffer is flushed only on build failure as a catch-all.

### 7.2 Surefire with `forkCount=0`

When Surefire runs tests in-process (no fork), the test output goes through the Maven JVM's stdout and is captured by `MojoDispatchPrintStream`. The XML reports are still generated. MSE should prefer the XML reports regardless of fork mode to maintain a single code path. The in-process captured output serves as a fallback if XML parsing fails.

### 7.3 Surefire with `forkCount > 1`

Multiple forked JVMs run tests in parallel. Each produces its own set of XML report files. The `ArtifactParser` handles this naturally since it scans all `TEST-*.xml` files in the reports directory. No special handling is needed.

### 7.4 Plugins Using SLF4J Directly

Some plugins obtain an SLF4J logger and write directly to the logging backend, bypassing `System.out`. For Maven 3.x, the backend is typically a simple SLF4J implementation that writes to `System.out` anyway, so the stream redirect catches it. For Maven 3.9+ (SLF4J 2.x), this remains true for the bundled provider. MSE does not attempt to reconfigure Logback or any other SLF4J backend — this is intentionally out of scope to avoid version-coupling fragility.

### 7.5 Interactive Plugins

Rare plugins that read from `System.in` (e.g., `versions-maven-plugin` in interactive mode) will break if stdin is not available. This is not an MSE-specific problem — AI agents should always pass `-B` (batch mode) to Maven. MSE should log a warning if `-B` is not detected.

### 7.6 Build Extensions vs. Core Extensions

MSE is designed as a **core extension** (injected via `-Dmaven.ext.class.path`), not a build extension (declared in `pom.xml`). Core extensions load earlier and have broader lifecycle visibility. The tradeoff is that core extensions cannot access project-model information (e.g., `<properties>`) at init time, but MSE does not need this.

---

## 8. Implementation Phases

### Phase 1: Event Suppression + Surefire Parsing (MVP)

**Goal:** Eliminate lifecycle noise, provide structured test failure output.

Scope:
- Implement `SilentEventSpy` with lifecycle event suppression.
- Implement `ArtifactParser` for Surefire XML reports.
- Emit `MSE:OK` / `MSE:FAIL` / `MSE:TESTS` / `MSE:TEST_FAIL` output.
- No `System.setOut()` redirection yet.

This phase alone eliminates the majority of noise (lifecycle banners, download logs, plugin headers) and handles the most common failure mode (test failures) via artifact parsing. It avoids all stream-hijacking complexity.

**Estimated effort:** 2–3 days.

### Phase 2: In-Process Stream Capture

**Goal:** Capture output from non-forking plugins (compiler errors, warning messages).

Scope:
- Implement `MojoDispatchPrintStream` with thread-to-mojo routing.
- Implement `MojoBufferManager` with per-mojo bounded buffers.
- Implement `BoundedBuffer` with head+tail truncation strategy.
- Add compiler error extraction from captured output.
- Add unattributed buffer for stray thread output.

**Estimated effort:** 3–4 days.

### Phase 3: Robustness and Polish

**Goal:** Handle edge cases, improve output quality.

Scope:
- Stack trace truncation (skip framework frames, keep user code).
- Warning aggregation (count warnings, emit summary, suppress individual lines).
- Detect missing `-B` flag and warn.
- Fallback to passthrough mode on unexpected exceptions within MSE itself.
- Integration tests using `maven-invoker-plugin` with synthetic projects that exercise parallel builds, fork modes, and compiler errors.

**Estimated effort:** 3–4 days.

---

## 9. Output Protocol Reference

All MSE output lines are prefixed with `MSE:` for unambiguous identification.

```
MSE:SESSION_START modules=<count> goals=<goal-list>
MSE:OK modules=<count> tests=<pass>/<total> time=<seconds>s warnings=<count>
MSE:BUILD_FAILED failed=<count> modules=<count> tests=<pass>/<total> time=<seconds>s
MSE:FAIL <plugin-id>:<goal> @ <module-id>
MSE:ERR <file>:<line>:<col> <message>
MSE:TESTS total=<n> passed=<n> failed=<n> errors=<n> skipped=<n>
MSE:TEST_FAIL <class>#<method>
  <failure-message>
  <truncated-stacktrace>
MSE:TEST_ERROR <class>#<method>
  <error-message>
  <truncated-stacktrace>
MSE:WARN_SUMMARY <count> warnings (use -Dmse.showWarnings=true to display)
MSE:BUFFERED_OUTPUT_BEGIN
  <raw captured output, only on failure>
MSE:BUFFERED_OUTPUT_END
MSE:PASSTHROUGH <reason>
  <fallback to unfiltered output if MSE encounters internal error>
```

---

## 10. Open Questions

1. **Compiler plugin error format across versions.** The `maven-compiler-plugin` 3.x and the forthcoming 4.x may differ in error output format. Should MSE maintain a set of regex patterns versioned by plugin, or is a single lenient pattern sufficient?

2. **Multi-release JAR builds.** Projects using `maven-compiler-plugin` with multiple `<execution>` blocks (e.g., compile Java 11 and Java 17 sources) will trigger multiple `MojoStarted`/`MojoSucceeded` events for the same plugin. The mojo key must include the execution ID, not just the plugin and goal.

3. **Agent feedback loop.** Should MSE expose a machine-readable "suggested fix" hint? For example, a missing dependency error could emit `MSE:HINT add dependency <groupId>:<artifactId>`. This is high-value but risks brittleness. Defer to Phase 4 if pursued.

4. **Kotlin/Scala compilers.** Projects using `kotlin-maven-plugin` or `scala-maven-plugin` emit errors in different formats. Phase 1 should treat these as opaque (flush raw output on failure). Format-specific parsing can be added incrementally.
