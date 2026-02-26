package jerrinot.info.llmaven;

import jerrinot.info.llmaven.model.CompilerError;
import jerrinot.info.llmaven.model.TestFailure;
import jerrinot.info.llmaven.model.TestSummary;

import java.io.File;
import java.io.PrintStream;
import java.util.Collection;
import java.util.List;

class OutputFormatter {

    private static final int MAX_FAILURE_DETAILS = 10;
    private static final int MAX_COMPILER_ERRORS = 25;

    private final PrintStream out;

    public OutputFormatter(PrintStream out) {
        this.out = out;
    }

    public void emitSessionStart(int moduleCount, List<String> goals) {
        String goalStr = (goals == null || goals.isEmpty())
                ? "<none>"
                : String.join(",", goals);
        out.println("MSE:SESSION_START modules=" + moduleCount + " goals=" + goalStr);
    }

    public void emitOk(BuildState state) {
        StringBuilder sb = new StringBuilder("MSE:OK modules=");
        sb.append(state.getTotalModules());
        appendTestCounts(sb, state);
        sb.append(" time=").append(state.getElapsedSeconds()).append('s');
        out.println(sb);
    }

    public void emitFail(String pluginArtifactId, String goal,
                         String executionId, String moduleId) {
        StringBuilder sb = new StringBuilder("MSE:FAIL ");
        sb.append(pluginArtifactId != null ? pluginArtifactId : "unknown-plugin")
                .append(':')
                .append(goal != null ? goal : "unknown-goal");
        if (executionId != null && !executionId.isEmpty()
                && !executionId.startsWith("default-")) {
            sb.append(" (").append(executionId).append(')');
        }
        sb.append(" @ ").append(moduleId);
        out.println(sb);
    }

    public void emitTestResults(TestSummary summary) {
        // Build entire output as one string to prevent interleaving in parallel builds
        StringBuilder sb = new StringBuilder();
        sb.append("MSE:TESTS total=").append(summary.getTotal())
                .append(" passed=").append(summary.getPassed())
                .append(" failed=").append(summary.getFailures())
                .append(" errors=").append(summary.getErrors())
                .append(" skipped=").append(summary.getSkipped());

        List<TestFailure> details = summary.getFailureDetails();
        int shown = Math.min(details.size(), MAX_FAILURE_DETAILS);
        for (int i = 0; i < shown; i++) {
            TestFailure f = details.get(i);
            String prefix = f.getKind() == TestFailure.Kind.FAILURE
                    ? "MSE:TEST_FAIL " : "MSE:TEST_ERROR ";
            sb.append('\n').append(prefix).append(f.getClassName()).append('#').append(f.getMethodName());
            if (f.getMessage() != null && !f.getMessage().isEmpty()) {
                sb.append("\n  ").append(f.getMessage());
            }
            if (f.getStackTrace() != null && !f.getStackTrace().isEmpty()) {
                for (String line : f.getStackTrace().split("\\r?\\n")) {
                    sb.append("\n  ").append(line);
                }
            }
        }
        if (details.size() > MAX_FAILURE_DETAILS) {
            int remaining = details.size() - MAX_FAILURE_DETAILS;
            sb.append("\nMSE:TEST_TRUNCATED ").append(remaining)
                    .append(remaining == 1 ? " more failure not shown" : " more failures not shown");
        }
        out.println(sb);
    }

    public void emitCompilerErrors(List<CompilerError> errors) {
        if (errors.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(errors.size(), MAX_COMPILER_ERRORS);
        for (int i = 0; i < shown; i++) {
            CompilerError e = errors.get(i);
            if (i > 0) sb.append('\n');
            sb.append("MSE:ERR ").append(e.getFile())
                    .append(':').append(e.getLine())
                    .append(':').append(e.getColumn())
                    .append(' ').append(e.getMessage());
        }
        if (errors.size() > MAX_COMPILER_ERRORS) {
            int remaining = errors.size() - MAX_COMPILER_ERRORS;
            sb.append("\nMSE:ERR_TRUNCATED ").append(remaining)
                    .append(remaining == 1 ? " more error not shown" : " more errors not shown");
        }
        out.println(sb);
    }

    public void emitBuildFailed(BuildState state) {
        StringBuilder sb = new StringBuilder("MSE:BUILD_FAILED failed=");
        sb.append(state.getFailedModules())
                .append(" modules=").append(state.getTotalModules());
        appendTestCounts(sb, state);
        if (state.getCompilerErrors() > 0) {
            sb.append(" compiler_errors=").append(state.getCompilerErrors());
        }
        sb.append(" time=").append(state.getElapsedSeconds()).append('s');
        out.println(sb);
    }

    private void appendTestCounts(StringBuilder sb, BuildState state) {
        sb.append(" passed=").append(state.getTestPassed())
                .append(" failed=").append(state.getTestFailed())
                .append(" errors=").append(state.getTestErrors())
                .append(" skipped=").append(state.getTestSkipped());
    }

    public void emitTestOutputPaths(Collection<File> reportsDirs) {
        for (File dir : reportsDirs) {
            out.println("MSE:TEST_OUTPUT " + dir.getAbsolutePath());
        }
    }

    public void emitBuildLog(File logFile) {
        out.println("MSE:BUILD_LOG " + logFile.getAbsolutePath());
    }

    public void emitPassthrough(String reason) {
        out.println("MSE:PASSTHROUGH " + reason);
    }
}
