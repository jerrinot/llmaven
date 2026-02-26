package jerrinot.info.mse;

import jerrinot.info.mse.model.CompilerError;
import jerrinot.info.mse.model.TestFailure;
import jerrinot.info.mse.model.TestSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutputFormatterTest {

    private ByteArrayOutputStream baos;
    private OutputFormatter formatter;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true);
        formatter = new OutputFormatter(out);
    }

    private String output() {
        return baos.toString();
    }

    @Test
    void sessionStart() {
        formatter.emitSessionStart(5, Arrays.asList("clean", "verify"));
        assertEquals("MSE:SESSION_START modules=5 goals=clean,verify\n", output());
    }

    @Test
    void sessionStartNullGoals() {
        formatter.emitSessionStart(1, null);
        assertEquals("MSE:SESSION_START modules=1 goals=<none>\n", output());
    }

    @Test
    void sessionStartEmptyGoals() {
        formatter.emitSessionStart(1, Collections.emptyList());
        assertEquals("MSE:SESSION_START modules=1 goals=<none>\n", output());
    }

    @Test
    void sessionStartSingleGoal() {
        formatter.emitSessionStart(1, Collections.singletonList("install"));
        assertEquals("MSE:SESSION_START modules=1 goals=install\n", output());
    }

    @Test
    void ok() {
        BuildState state = new BuildState(3, System.currentTimeMillis() - 10000);
        state.accumulateTests(new TestSummary(50, 0, 0, 2, Collections.<TestFailure>emptyList()));
        formatter.emitOk(state);
        String line = output().trim();
        assertTrue(line.startsWith("MSE:OK modules=3 passed=48 failed=0 errors=0 skipped=2 time="));
        assertTrue(line.endsWith("s"));
        // Verify the time value is within expected range (9-11 seconds)
        long time = extractTimeSeconds(line);
        assertTrue(time >= 9 && time <= 11,
                "Expected time between 9 and 11 but was: " + time);
    }

    @Test
    void failWithDefaultExecution() {
        formatter.emitFail("maven-compiler-plugin", "compile", "default-compile", "my-module");
        assertEquals("MSE:FAIL maven-compiler-plugin:compile @ my-module\n", output());
    }

    @Test
    void failWithCustomExecution() {
        formatter.emitFail("maven-surefire-plugin", "test", "integration-tests", "my-module");
        assertEquals("MSE:FAIL maven-surefire-plugin:test (integration-tests) @ my-module\n", output());
    }

    @Test
    void failWithNullExecution() {
        formatter.emitFail("maven-surefire-plugin", "test", null, "my-module");
        assertEquals("MSE:FAIL maven-surefire-plugin:test @ my-module\n", output());
    }

    @Test
    void testResultsAllPass() {
        TestSummary summary = new TestSummary(10, 0, 0, 0, Collections.<TestFailure>emptyList());
        formatter.emitTestResults(summary);
        assertEquals("MSE:TESTS total=10 passed=10 failed=0 errors=0 skipped=0\n", output());
    }

    @Test
    void testResultsWithFailures() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.AppTest", "testParse",
                "expected:<1> but was:<2>",
                "org.opentest4j.AssertionFailedError: expected:<1> but was:<2>\n\tat com.example.AppTest.testParse(AppTest.java:10)");
        TestFailure error = new TestFailure(
                TestFailure.Kind.ERROR,
                "com.example.AppTest", "testEdge",
                "NullPointerException",
                "java.lang.NullPointerException\n\tat com.example.App.run(App.java:5)");

        TestSummary summary = new TestSummary(5, 1, 1, 0, Arrays.asList(failure, error));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TESTS total=5 passed=3 failed=1 errors=1 skipped=0"));
        assertTrue(result.contains("MSE:TEST_FAIL com.example.AppTest#testParse"));
        assertTrue(result.contains("  expected:<1> but was:<2>"));
        assertTrue(result.contains("MSE:TEST_ERROR com.example.AppTest#testEdge"));
        assertTrue(result.contains("  NullPointerException"));
    }

    @Test
    void buildFailed() {
        BuildState state = new BuildState(5, System.currentTimeMillis() - 30000);
        state.accumulateTests(new TestSummary(100, 2, 1, 0, Collections.<TestFailure>emptyList()));
        state.moduleFailed();
        state.moduleSucceeded();
        state.moduleSucceeded();
        formatter.emitBuildFailed(state);
        String line = output().trim();
        assertTrue(line.startsWith("MSE:BUILD_FAILED failed=1 modules=5 passed=97 failed=2 errors=1 skipped=0 time="));
        assertTrue(line.endsWith("s"));
        // Verify the time value is within expected range (29-31 seconds)
        long time = extractTimeSeconds(line);
        assertTrue(time >= 29 && time <= 31,
                "Expected time between 29 and 31 but was: " + time);
    }

    @Test
    void passthrough() {
        formatter.emitPassthrough("internal error in MSE");
        assertEquals("MSE:PASSTHROUGH internal error in MSE\n", output());
    }

    @Test
    void failWithEmptyExecution() {
        formatter.emitFail("maven-compiler-plugin", "compile", "", "my-module");
        assertEquals("MSE:FAIL maven-compiler-plugin:compile @ my-module\n", output());
    }

    @Test
    void failWithNonDefaultExecution() {
        formatter.emitFail("maven-surefire-plugin", "test", "custom-exec", "core");
        assertEquals("MSE:FAIL maven-surefire-plugin:test (custom-exec) @ core\n", output());
    }

    @Test
    void failWithDefaultTestExecution() {
        // "default-test" starts with "default-" so should be suppressed
        formatter.emitFail("maven-surefire-plugin", "test", "default-test", "core");
        assertEquals("MSE:FAIL maven-surefire-plugin:test @ core\n", output());
    }

    @Test
    void failWithExactlyDefaultPrefix() {
        // "default-" alone starts with "default-" so should be suppressed
        formatter.emitFail("plugin", "goal", "default-", "mod");
        assertEquals("MSE:FAIL plugin:goal @ mod\n", output());
    }

    @Test
    void okWithZeroTests() {
        BuildState state = new BuildState(1, System.currentTimeMillis());
        formatter.emitOk(state);
        String line = output().trim();
        assertTrue(line.contains("passed=0 failed=0 errors=0 skipped=0"), "Should show all zero counts");
        assertTrue(line.startsWith("MSE:OK modules=1"));
    }

    @Test
    void buildFailedWithZeroTests() {
        BuildState state = new BuildState(2, System.currentTimeMillis());
        state.moduleFailed();
        formatter.emitBuildFailed(state);
        String line = output().trim();
        assertTrue(line.contains("passed=0 failed=0 errors=0 skipped=0"), "Should show all zero counts");
        assertTrue(line.startsWith("MSE:BUILD_FAILED failed=1 modules=2"));
    }

    @Test
    void testResultsWithFailureNullMessageAndStackTrace() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.Test", "testNull",
                null,
                null);
        TestSummary summary = new TestSummary(1, 1, 0, 0, Collections.singletonList(failure));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TEST_FAIL com.example.Test#testNull"));
        // null message should not add the "  " message line
        assertFalse(result.contains("  null"), "Should not print 'null' for null message");
    }

    @Test
    void testResultsWithFailureEmptyMessageAndStackTrace() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.Test", "testEmpty",
                "",
                "");
        TestSummary summary = new TestSummary(1, 1, 0, 0, Collections.singletonList(failure));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TEST_FAIL com.example.Test#testEmpty"));
        // Count lines: header + test_fail line only (no message or stack trace lines)
        String[] lines = result.trim().split("\n");
        assertEquals(2, lines.length, "Should be just the summary line and the TEST_FAIL line");
    }

    @Test
    void testResultsWithErrorKind() {
        TestFailure error = new TestFailure(
                TestFailure.Kind.ERROR,
                "com.example.ErrTest", "testBoom",
                "NullPointerException",
                "java.lang.NullPointerException\n\tat Foo.bar(Foo.java:1)");
        TestSummary summary = new TestSummary(1, 0, 1, 0, Collections.singletonList(error));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TEST_ERROR com.example.ErrTest#testBoom"));
        assertTrue(result.contains("  NullPointerException"));
        assertTrue(result.contains("  java.lang.NullPointerException"));
        assertTrue(result.contains("  \tat Foo.bar(Foo.java:1)"));
    }

    @Test
    void testResultsMultipleFailures() {
        TestFailure f1 = new TestFailure(TestFailure.Kind.FAILURE,
                "A", "m1", "msg1", "stack1");
        TestFailure f2 = new TestFailure(TestFailure.Kind.ERROR,
                "B", "m2", "msg2", "stack2");
        TestFailure f3 = new TestFailure(TestFailure.Kind.FAILURE,
                "C", "m3", "msg3", "stack3");
        TestSummary summary = new TestSummary(10, 2, 1, 0, Arrays.asList(f1, f2, f3));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TEST_FAIL A#m1"));
        assertTrue(result.contains("MSE:TEST_ERROR B#m2"));
        assertTrue(result.contains("MSE:TEST_FAIL C#m3"));
    }

    @Test
    void emitOkOutputFormat() {
        // Use fixed start time to get predictable elapsed time
        long fixedStart = System.currentTimeMillis() - 2000;
        BuildState state = new BuildState(3, fixedStart);
        state.accumulateTests(new TestSummary(10, 1, 0, 2, Collections.<TestFailure>emptyList()));
        formatter.emitOk(state);
        String line = output().trim();
        // Verify the exact format prefix
        assertTrue(line.startsWith("MSE:OK modules=3 passed=7 failed=1 errors=0 skipped=2 time="));
        assertTrue(line.endsWith("s"));
    }

    @Test
    void emitBuildFailedOutputFormat() {
        long fixedStart = System.currentTimeMillis() - 3000;
        BuildState state = new BuildState(4, fixedStart);
        state.moduleFailed();
        state.moduleFailed();
        state.accumulateTests(new TestSummary(20, 3, 2, 1, Collections.<TestFailure>emptyList()));
        formatter.emitBuildFailed(state);
        String line = output().trim();
        assertTrue(line.startsWith("MSE:BUILD_FAILED failed=2 modules=4 passed=14 failed=3 errors=2 skipped=1 time="));
        assertTrue(line.endsWith("s"));
    }

    @Test
    void sessionStartWithManyGoals() {
        formatter.emitSessionStart(10, Arrays.asList("clean", "compile", "test", "package", "verify"));
        assertEquals("MSE:SESSION_START modules=10 goals=clean,compile,test,package,verify\n", output());
    }

    /**
     * Extracts the numeric time value (in seconds) from an MSE output line
     * that contains "time=Ns" where N is the number of seconds.
     */
    private long extractTimeSeconds(String line) {
        int timeIdx = line.indexOf("time=");
        assertTrue(timeIdx >= 0, "Line should contain 'time=': " + line);
        String afterTime = line.substring(timeIdx + 5);
        // afterTime is like "10s" or "30s"
        String numStr = afterTime.replace("s", "").trim();
        return Long.parseLong(numStr);
    }

    @Test
    void testResultsWithMultiLineStackTrace() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.Test", "testFoo",
                "expected true",
                "line1\nline2\nline3");
        TestSummary summary = new TestSummary(1, 1, 0, 0, Collections.singletonList(failure));
        formatter.emitTestResults(summary);

        String result = output();
        // Each stack trace line should be indented with "  "
        assertTrue(result.contains("  line1"));
        assertTrue(result.contains("  line2"));
        assertTrue(result.contains("  line3"));
    }

    @Test
    void testResultsTruncatesAfterMaxFailures() {
        List<TestFailure> failures = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            failures.add(new TestFailure(
                    TestFailure.Kind.FAILURE,
                    "com.example.Test" + i, "testMethod" + i,
                    "msg" + i, "stack" + i));
        }
        TestSummary summary = new TestSummary(12, 12, 0, 0, failures);
        formatter.emitTestResults(summary);

        String result = output();
        // First 10 should be shown
        for (int i = 1; i <= 10; i++) {
            assertTrue(result.contains("MSE:TEST_FAIL com.example.Test" + i + "#testMethod" + i),
                    "Failure #" + i + " should be shown");
        }
        // 11th and 12th should NOT be shown
        assertFalse(result.contains("com.example.Test11#testMethod11"),
                "Failure #11 should be truncated");
        assertFalse(result.contains("com.example.Test12#testMethod12"),
                "Failure #12 should be truncated");
        // Truncation message
        assertTrue(result.contains("MSE:TEST_TRUNCATED 2 more failures not shown"));
    }

    @Test
    void testResultsExactlyMaxFailuresNoTruncation() {
        List<TestFailure> failures = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            failures.add(new TestFailure(
                    TestFailure.Kind.FAILURE,
                    "com.example.Test" + i, "testMethod" + i,
                    "msg" + i, "stack" + i));
        }
        TestSummary summary = new TestSummary(10, 10, 0, 0, failures);
        formatter.emitTestResults(summary);

        String result = output();
        // All 10 should be shown
        for (int i = 1; i <= 10; i++) {
            assertTrue(result.contains("MSE:TEST_FAIL com.example.Test" + i + "#testMethod" + i),
                    "Failure #" + i + " should be shown");
        }
        // No truncation message
        assertFalse(result.contains("MSE:TEST_TRUNCATED"),
                "Exactly 10 failures should not trigger truncation");
    }

    @Test
    void testResultsSingularTruncation() {
        List<TestFailure> failures = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            failures.add(new TestFailure(
                    TestFailure.Kind.FAILURE,
                    "com.example.Test" + i, "testMethod" + i,
                    "msg" + i, "stack" + i));
        }
        TestSummary summary = new TestSummary(11, 11, 0, 0, failures);
        formatter.emitTestResults(summary);

        String result = output();
        // First 10 should be shown
        for (int i = 1; i <= 10; i++) {
            assertTrue(result.contains("MSE:TEST_FAIL com.example.Test" + i + "#testMethod" + i),
                    "Failure #" + i + " should be shown");
        }
        // 11th should NOT be shown
        assertFalse(result.contains("com.example.Test11#testMethod11"),
                "Failure #11 should be truncated");
        // Singular truncation message
        assertTrue(result.contains("MSE:TEST_TRUNCATED 1 more failure not shown"),
                "Should use singular 'failure' for exactly 1 truncated: " + result);
    }

    @Test
    void emitFailWithNullArtifactIdAndGoal() {
        formatter.emitFail(null, null, null, "mod");
        assertEquals("MSE:FAIL unknown-plugin:unknown-goal @ mod\n", output());
    }

    @Test
    void emitFailWithNullGoalOnly() {
        formatter.emitFail("my-plugin", null, "default-compile", "mod");
        assertEquals("MSE:FAIL my-plugin:unknown-goal @ mod\n", output());
    }

    @Test
    void emitCompilerErrorsSingleError() {
        List<CompilerError> errors = Collections.singletonList(
                new CompilerError("/src/App.java", 10, 5, "cannot find symbol"));
        formatter.emitCompilerErrors(errors);
        assertEquals("MSE:ERR /src/App.java:10:5 cannot find symbol\n", output());
    }

    @Test
    void emitCompilerErrorsMultiple() {
        List<CompilerError> errors = Arrays.asList(
                new CompilerError("/src/App.java", 10, 5, "cannot find symbol"),
                new CompilerError("/src/Util.java", 3, 1, "';' expected"));
        formatter.emitCompilerErrors(errors);
        String result = output();
        assertTrue(result.contains("MSE:ERR /src/App.java:10:5 cannot find symbol"));
        assertTrue(result.contains("MSE:ERR /src/Util.java:3:1 ';' expected"));
    }

    @Test
    void emitCompilerErrorsEmptyListProducesNoOutput() {
        formatter.emitCompilerErrors(Collections.emptyList());
        assertEquals("", output());
    }

    @Test
    void emitCompilerErrorsTruncatesAfter25() {
        List<CompilerError> errors = new ArrayList<>();
        for (int i = 1; i <= 28; i++) {
            errors.add(new CompilerError("/src/App.java", i, 1, "error " + i));
        }
        formatter.emitCompilerErrors(errors);
        String result = output();
        // First 25 should be present
        assertTrue(result.contains("MSE:ERR /src/App.java:25:1 error 25"));
        // 26th should not
        assertFalse(result.contains("MSE:ERR /src/App.java:26:1 error 26"));
        // Truncation message
        assertTrue(result.contains("MSE:ERR_TRUNCATED 3 more errors not shown"));
    }

    @Test
    void parsedCompilerErrorsOver25EmitTruncationMarker() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("[ERROR] /src/App.java:[").append(i).append(",1] error ").append(i).append('\n');
        }

        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(sb.toString());
        assertEquals(30, errors.size());

        formatter.emitCompilerErrors(errors);
        String result = output();
        assertTrue(result.contains("MSE:ERR /src/App.java:25:1 error 25"));
        assertFalse(result.contains("MSE:ERR /src/App.java:26:1 error 26"));
        assertTrue(result.contains("MSE:ERR_TRUNCATED 5 more errors not shown"));
    }

    @Test
    void emitCompilerErrorsTruncationSingular() {
        List<CompilerError> errors = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            errors.add(new CompilerError("/src/App.java", i, 1, "error " + i));
        }
        formatter.emitCompilerErrors(errors);
        String result = output();
        assertTrue(result.contains("MSE:ERR_TRUNCATED 1 more error not shown"));
    }

    @Test
    void emitCompilerErrorsExactly25NoTruncation() {
        List<CompilerError> errors = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            errors.add(new CompilerError("/src/App.java", i, 1, "error " + i));
        }
        formatter.emitCompilerErrors(errors);
        String result = output();
        assertFalse(result.contains("MSE:ERR_TRUNCATED"));
        assertTrue(result.contains("MSE:ERR /src/App.java:25:1 error 25"));
    }

    @Test
    void buildFailedWithCompilerErrors() {
        BuildState state = new BuildState(2, System.currentTimeMillis());
        state.moduleFailed();
        state.addCompilerErrors(5);
        formatter.emitBuildFailed(state);
        String line = output().trim();
        assertTrue(line.contains("compiler_errors=5"), "Should include compiler_errors: " + line);
    }

    @Test
    void buildFailedWithoutCompilerErrorsOmitsField() {
        BuildState state = new BuildState(2, System.currentTimeMillis());
        state.moduleFailed();
        formatter.emitBuildFailed(state);
        String line = output().trim();
        assertFalse(line.contains("compiler_errors"), "Should not include compiler_errors when 0: " + line);
    }

    @Test
    void testResultsWithWindowsLineEndingsInStackTrace() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.WinTest", "testCRLF",
                "assertion failed",
                "java.lang.AssertionError\r\n\tat com.example.WinTest.testCRLF(WinTest.java:10)\r\n\tat sun.reflect.NativeMethodAccessorImpl.invoke(Unknown)");
        TestSummary summary = new TestSummary(1, 1, 0, 0, Collections.singletonList(failure));
        formatter.emitTestResults(summary);

        String result = output();
        assertTrue(result.contains("MSE:TEST_FAIL com.example.WinTest#testCRLF"));
        // Each line of the stack trace should be properly indented with "  "
        assertTrue(result.contains("  java.lang.AssertionError"),
                "First stack trace line should be indented");
        assertTrue(result.contains("  \tat com.example.WinTest.testCRLF(WinTest.java:10)"),
                "Second stack trace line should be indented");
        assertTrue(result.contains("  \tat sun.reflect.NativeMethodAccessorImpl.invoke(Unknown)"),
                "Third stack trace line should be indented");
        // Ensure no \r characters leaked into the output (they should be stripped by the \\r?\\n split)
        assertFalse(result.contains("\r"),
                "Carriage return characters should not appear in output");
    }
}
