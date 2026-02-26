package jerrinot.info.llmaven;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SilentEventSpyTest {

    private ByteArrayOutputStream baos;
    private SilentEventSpy spy;

    @BeforeEach
    void setUp() throws Exception {
        baos = new ByteArrayOutputStream();
        PrintStream testOut = new PrintStream(baos, true);

        spy = new TestableSilentEventSpy(testOut);
        spy.init(null);
    }

    private String output() {
        return baos.toString();
    }

    @Test
    void inactiveWhenEnvNotSet() throws Exception {
        ByteArrayOutputStream inactiveBaos = new ByteArrayOutputStream();
        PrintStream inactiveOut = new PrintStream(inactiveBaos, true);
        SilentEventSpy inactiveSpy = new InactiveTestSpy(inactiveOut);
        inactiveSpy.init(null);
        ExecutionEvent event = mockSessionStarted(1, Arrays.asList("clean", "test"));
        inactiveSpy.onEvent(event);
        // No output since it's inactive — just verifying no exception and no output
        assertEquals("", inactiveBaos.toString(), "Inactive spy should produce no output");
    }

    @Test
    void sessionStartEmitsLine() throws Exception {
        ExecutionEvent event = mockSessionStarted(3, Arrays.asList("clean", "verify"));
        spy.onEvent(event);
        assertTrue(output().contains("MSE:SESSION_START modules=3 goals=clean,verify"));
    }

    @Test
    void sessionEndEmitsOkWhenNoFailures() throws Exception {
        // Start session
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        // Succeed a project
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));

        // End session
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:SESSION_START"));
        assertTrue(result.contains("MSE:OK modules=1"));
    }

    @Test
    void sessionEndEmitsBuildFailedAfterFailure() throws Exception {
        spy.onEvent(mockSessionStarted(2, Collections.singletonList("test")));
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectFailed));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:BUILD_FAILED failed=1 modules=2"));
        assertFalse(result.contains("MSE:OK"));
    }

    @Test
    void mojoFailedEmitsFailLine() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mockMojoEvent(ExecutionEvent.Type.MojoFailed, mojo, "my-app");
        spy.onEvent(event);

        assertTrue(output().contains("MSE:FAIL maven-compiler-plugin:compile @ my-app"));
    }

    @Test
    void surefireFailedParsesTestReports(@TempDir Path tempDir) throws Exception {
        // Set up surefire-reports in tempDir
        Path reportsDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);
        Files.write(reportsDir.resolve("TEST-com.example.FooTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.FooTest\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk\" classname=\"com.example.FooTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testBad\" classname=\"com.example.FooTest\" time=\"0.02\">\n"
                        + "    <failure message=\"expected true\">junit.framework.AssertionFailedError: expected true\n"
                        + "\tat com.example.FooTest.testBad(FooTest.java:10)</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-surefire-plugin:test @ my-app"));
        assertTrue(result.contains("MSE:TESTS total=2 passed=1 failed=1 errors=0 skipped=0"));
        assertTrue(result.contains("MSE:TEST_FAIL com.example.FooTest#testBad"));
    }

    @Test
    void duplicateModuleNotParsedTwice(@TempDir Path tempDir) throws Exception {
        Path reportsDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);
        Files.write(reportsDir.resolve("TEST-com.example.FooTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.FooTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk\" classname=\"com.example.FooTest\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event);
        spy.onEvent(event); // second time for same module

        // End session, check test count is only accumulated once
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("tests=1/1"));
    }

    @Test
    void nonTestPluginSuccessProducesNoOutput() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");

        ExecutionEvent event = mockMojoEvent(ExecutionEvent.Type.MojoSucceeded, mojo, "my-app");
        spy.onEvent(event);

        // Only SESSION_START should be in the output
        assertEquals(1, output().trim().split("\n").length);
        assertTrue(output().contains("MSE:SESSION_START"));
    }

    @Test
    void failSafeOnException() throws Exception {
        // First, a normal session start
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        // Now send a SessionStarted with null session to trigger NPE in handleSessionStarted
        ExecutionEvent badEvent = mock(ExecutionEvent.class);
        when(badEvent.getType()).thenReturn(ExecutionEvent.Type.SessionStarted);
        when(badEvent.getSession()).thenReturn(null);

        // This should not throw — it should emit PASSTHROUGH and deactivate
        spy.onEvent(badEvent);

        // After failsafe, further events should be ignored
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        String result = output();
        assertTrue(result.contains("MSE:PASSTHROUGH"));
        // Should have one SESSION_START (the first), then PASSTHROUGH, and no second SESSION_START
        long sessionStartCount = Arrays.stream(result.split("\n"))
                .filter(l -> l.contains("MSE:SESSION_START")).count();
        assertEquals(1, sessionStartCount);
    }

    @Test
    void distinctExecutionIdsParsedSeparately(@TempDir Path tempDir) throws Exception {
        Path reportsDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);
        Files.write(reportsDir.resolve("TEST-com.example.FooTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.FooTest\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk\" classname=\"com.example.FooTest\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        // First execution
        MojoExecution mojo1 = mock(MojoExecution.class);
        when(mojo1.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo1.getGoal()).thenReturn("test");
        when(mojo1.getExecutionId()).thenReturn("exec-1");

        ExecutionEvent event1 = mock(ExecutionEvent.class);
        when(event1.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event1.getMojoExecution()).thenReturn(mojo1);
        when(event1.getProject()).thenReturn(project);

        // Second execution with different executionId
        MojoExecution mojo2 = mock(MojoExecution.class);
        when(mojo2.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo2.getGoal()).thenReturn("test");
        when(mojo2.getExecutionId()).thenReturn("exec-2");

        ExecutionEvent event2 = mock(ExecutionEvent.class);
        when(event2.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event2.getMojoExecution()).thenReturn(mojo2);
        when(event2.getProject()).thenReturn(project);

        spy.onEvent(event1);
        spy.onEvent(event2);

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("tests=2/2"), "Both executions should be parsed: " + result);
    }

    @Test
    void failsafePluginUsesFailsafeReportsDir(@TempDir Path tempDir) throws Exception {
        Path reportsDir = tempDir.resolve("target/failsafe-reports");
        Files.createDirectories(reportsDir);
        Files.write(reportsDir.resolve("TEST-com.example.IntegrationIT.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.IntegrationIT\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk\" classname=\"com.example.IntegrationIT\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testOk2\" classname=\"com.example.IntegrationIT\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testBad\" classname=\"com.example.IntegrationIT\" time=\"0.01\">\n"
                        + "    <failure message=\"integration failed\">stack</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Collections.singletonList("verify")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-failsafe-plugin");
        when(mojo.getGoal()).thenReturn("verify");
        when(mojo.getExecutionId()).thenReturn("default-verify");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-failsafe-plugin:verify @ my-app"));
        assertTrue(result.contains("MSE:TESTS total=3 passed=2 failed=1 errors=0 skipped=0"));
        assertTrue(result.contains("MSE:TEST_FAIL com.example.IntegrationIT#testBad"));
    }

    @Test
    void nullBasedirSkipsTestParsing(@TempDir Path tempDir) throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(null); // null basedir
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event); // should not throw

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        // Should still get OK with zero tests since basedir was null
        assertTrue(result.contains("MSE:OK modules=1 tests=0/0"));
    }

    @Test
    void nullMojoOnMojoSucceededIsIgnored() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(null); // null mojo

        spy.onEvent(event); // should not throw

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:OK"));
    }

    @Test
    void nullProjectOnMojoSucceededIsIgnored() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(null); // null project

        spy.onEvent(event); // should not throw

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:OK"));
    }

    @Test
    void nullMojoOnMojoFailedStillMarksBuildFailed() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(null); // null mojo

        spy.onEvent(event); // should not throw — sets buildFailed, then early return

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        // A MojoFailed event means the build failed, even with null mojo metadata
        assertTrue(result.contains("MSE:BUILD_FAILED"), "MojoFailed with null mojo should still mark build as failed");
        assertFalse(result.contains("MSE:FAIL"), "No MSE:FAIL line since mojo metadata is unavailable");
    }

    @Test
    void mojoFailedWithNullProjectUsesUnknownModuleId() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(null); // null project

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-compiler-plugin:compile @ unknown"));
    }

    @Test
    void nonExecutionEventIsIgnored() throws Exception {
        // Pass a non-ExecutionEvent object
        spy.onEvent("not an execution event");
        spy.onEvent(42);
        spy.onEvent(null);

        // Should not throw or produce any output
        String result = output();
        assertEquals("", result, "Non-ExecutionEvent objects should be silently ignored");
    }

    @Test
    void sessionEndedBeforeSessionStartedDoesNotThrow() throws Exception {
        // End session without ever starting one (buildState will be null)
        spy.onEvent(mockSessionEnded());
        // No output expected since buildState is null
        String result = output();
        assertFalse(result.contains("MSE:OK"));
        assertFalse(result.contains("MSE:BUILD_FAILED"));
    }

    @Test
    void projectSucceededBeforeSessionStartedDoesNotThrow() throws Exception {
        // ProjectSucceeded without SessionStarted (buildState is null)
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        // Should not throw
    }

    @Test
    void projectFailedBeforeSessionStartedDoesNotThrow() throws Exception {
        // ProjectFailed without SessionStarted (buildState is null)
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectFailed));
        // Should not throw
    }

    @Test
    void closeCanBeCalledMultipleTimes() throws Exception {
        spy.close();
        spy.close(); // second close should be safe (idempotent restoreMavenLogging)
    }

    @Test
    void closeBeforeInit() throws Exception {
        // Create a fresh spy that has not been initialized
        SilentEventSpy freshSpy = new SilentEventSpy();
        freshSpy.close(); // should not throw
    }

    @Test
    void mojoFailedSetsBuildFailed() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mockMojoEvent(ExecutionEvent.Type.MojoFailed, mojo, "my-app");
        spy.onEvent(event);

        // End session
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:BUILD_FAILED"), "Build should be marked failed after MojoFailed");
    }

    @Test
    void surefireSucceededParsesTestReports(@TempDir Path tempDir) throws Exception {
        Path reportsDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);
        Files.write(reportsDir.resolve("TEST-com.example.FooTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.FooTest\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk\" classname=\"com.example.FooTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testOk2\" classname=\"com.example.FooTest\" time=\"0.02\"/>\n"
                        + "  <testcase name=\"testOk3\" classname=\"com.example.FooTest\" time=\"0.03\"/>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event);

        // All tests passed, so no MSE:TESTS line (only emitted when hasFailures)
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertFalse(result.contains("MSE:TESTS"), "No test results output for all-passing tests");
        assertTrue(result.contains("MSE:OK modules=1 tests=3/3"));
    }

    @Test
    void surefireSucceededWithNoReportsDir(@TempDir Path tempDir) throws Exception {
        // No surefire-reports directory exists under tempDir
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(project);

        spy.onEvent(event);

        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("MSE:OK modules=1 tests=0/0"));
    }

    @Test
    void sessionStartedWithNullProjects() throws Exception {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.SessionStarted);
        MavenSession session = mock(MavenSession.class);
        when(event.getSession()).thenReturn(session);
        when(session.getProjects()).thenReturn(null);
        when(session.getGoals()).thenReturn(Collections.singletonList("test"));

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:SESSION_START modules=0"));
    }

    @Test
    void failSafeDeactivatesAndNoFurtherEventsProcessed() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        // Trigger failsafe by sending a SessionStarted with null session
        ExecutionEvent badEvent = mock(ExecutionEvent.class);
        when(badEvent.getType()).thenReturn(ExecutionEvent.Type.SessionStarted);
        when(badEvent.getSession()).thenReturn(null);
        spy.onEvent(badEvent);

        assertTrue(output().contains("MSE:PASSTHROUGH"));

        // Clear output to check nothing more is emitted
        baos.reset();

        // All subsequent events should be ignored
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));
        spy.onEvent(mockSessionEnded());

        assertEquals("", output(), "No output after failsafe deactivation");
    }

    @Test
    void defaultEventTypesAreSuppressed() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        // Send various event types that should be silently ignored
        for (ExecutionEvent.Type type : new ExecutionEvent.Type[]{
                ExecutionEvent.Type.ProjectStarted,
                ExecutionEvent.Type.MojoStarted,
                ExecutionEvent.Type.ProjectDiscoveryStarted,
                ExecutionEvent.Type.ForkedProjectStarted,
                ExecutionEvent.Type.ForkedProjectSucceeded}) {
            ExecutionEvent event = mock(ExecutionEvent.class);
            when(event.getType()).thenReturn(type);
            spy.onEvent(event);
        }

        // Only SESSION_START should be in the output
        String[] lines = output().trim().split("\n");
        assertEquals(1, lines.length);
        assertTrue(lines[0].contains("MSE:SESSION_START"));
    }

    @Test
    void mojoFailedForTestPluginWithNullProjectDoesNotParseTests() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("test")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        when(event.getProject()).thenReturn(null); // null project

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-surefire-plugin:test @ unknown"));
        // Should not contain MSE:TESTS since project was null and test parsing was skipped
        assertFalse(result.contains("MSE:TESTS"));
    }

    @Test
    void mojoFailedBeforeSessionStartIsIgnored() throws Exception {
        // Do NOT send SessionStarted — buildState will be null
        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mockMojoEvent(ExecutionEvent.Type.MojoFailed, mojo, "my-app");
        spy.onEvent(event);

        // The centralized buildState null guard in dispatch() should prevent any processing
        String result = output();
        assertEquals("", result, "MojoFailed before SessionStarted should produce no output");
    }

    @Test
    void mojoSucceededBeforeSessionStartIsIgnored() throws Exception {
        // Do NOT send SessionStarted — buildState will be null
        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojo.getGoal()).thenReturn("test");
        when(mojo.getExecutionId()).thenReturn("default-test");

        ExecutionEvent event = mockMojoEvent(ExecutionEvent.Type.MojoSucceeded, mojo, "my-app");
        spy.onEvent(event);

        // The centralized buildState null guard in dispatch() should prevent any processing
        String result = output();
        assertEquals("", result, "MojoSucceeded before SessionStarted should produce no output");
    }

    @Test
    void multiModuleBuildAccumulatesTestCounts(@TempDir Path tempDir) throws Exception {
        // 3-module build: module-a has 4 tests, module-b has 6 tests, module-c has no tests
        Path modulaA = tempDir.resolve("module-a");
        Path moduleB = tempDir.resolve("module-b");
        Path moduleC = tempDir.resolve("module-c");

        // Module A: 4 tests, all pass
        Path reportsA = modulaA.resolve("target/surefire-reports");
        Files.createDirectories(reportsA);
        Files.write(reportsA.resolve("TEST-com.example.ATest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.ATest\" tests=\"4\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"test1\" classname=\"com.example.ATest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test2\" classname=\"com.example.ATest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test3\" classname=\"com.example.ATest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test4\" classname=\"com.example.ATest\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());

        // Module B: 6 tests, all pass
        Path reportsB = moduleB.resolve("target/surefire-reports");
        Files.createDirectories(reportsB);
        Files.write(reportsB.resolve("TEST-com.example.BTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.BTest\" tests=\"6\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"test1\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test2\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test3\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test4\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test5\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test6\" classname=\"com.example.BTest\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());

        // Module C: no surefire-reports directory at all
        Files.createDirectories(moduleC.resolve("target"));

        // Start session with 3 modules
        spy.onEvent(mockSessionStarted(3, Collections.singletonList("test")));

        // Module A: surefire succeeds + project succeeds
        MojoExecution mojoA = mock(MojoExecution.class);
        when(mojoA.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojoA.getGoal()).thenReturn("test");
        when(mojoA.getExecutionId()).thenReturn("default-test");

        MavenProject projectA = mock(MavenProject.class);
        when(projectA.getBasedir()).thenReturn(modulaA.toFile());
        when(projectA.getArtifactId()).thenReturn("module-a");
        when(projectA.getGroupId()).thenReturn("com.example");

        ExecutionEvent mojoSucceededA = mock(ExecutionEvent.class);
        when(mojoSucceededA.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(mojoSucceededA.getMojoExecution()).thenReturn(mojoA);
        when(mojoSucceededA.getProject()).thenReturn(projectA);
        spy.onEvent(mojoSucceededA);
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));

        // Module B: surefire succeeds + project succeeds
        MojoExecution mojoB = mock(MojoExecution.class);
        when(mojoB.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(mojoB.getGoal()).thenReturn("test");
        when(mojoB.getExecutionId()).thenReturn("default-test");

        MavenProject projectB = mock(MavenProject.class);
        when(projectB.getBasedir()).thenReturn(moduleB.toFile());
        when(projectB.getArtifactId()).thenReturn("module-b");
        when(projectB.getGroupId()).thenReturn("com.example");

        ExecutionEvent mojoSucceededB = mock(ExecutionEvent.class);
        when(mojoSucceededB.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(mojoSucceededB.getMojoExecution()).thenReturn(mojoB);
        when(mojoSucceededB.getProject()).thenReturn(projectB);
        spy.onEvent(mojoSucceededB);
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));

        // Module C: no surefire mojo at all, just project succeeds
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectSucceeded));

        // End session
        spy.onEvent(mockSessionEnded());

        String result = output();
        // 4 + 6 = 10 total tests, all passed
        assertTrue(result.contains("MSE:OK modules=3 tests=10/10"),
                "Should accumulate tests from all modules: " + result);
    }

    @Test
    void surefireAndFailsafeInSameModule(@TempDir Path tempDir) throws Exception {
        // Single module with both surefire (2 pass) and failsafe (3 tests, 1 failure)
        Path surefireReports = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireReports);
        Files.write(surefireReports.resolve("TEST-com.example.UnitTest.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.UnitTest\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"test1\" classname=\"com.example.UnitTest\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"test2\" classname=\"com.example.UnitTest\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());

        Path failsafeReports = tempDir.resolve("target/failsafe-reports");
        Files.createDirectories(failsafeReports);
        Files.write(failsafeReports.resolve("TEST-com.example.IntegrationIT.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"com.example.IntegrationIT\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testOk1\" classname=\"com.example.IntegrationIT\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testOk2\" classname=\"com.example.IntegrationIT\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testBad\" classname=\"com.example.IntegrationIT\" time=\"0.01\">\n"
                        + "    <failure message=\"integration failed\">stack trace</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());

        spy.onEvent(mockSessionStarted(1, Arrays.asList("verify")));

        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(tempDir.toFile());
        when(project.getArtifactId()).thenReturn("my-app");
        when(project.getGroupId()).thenReturn("com.example");

        // Surefire succeeds (2 unit tests pass)
        MojoExecution surefireMojo = mock(MojoExecution.class);
        when(surefireMojo.getArtifactId()).thenReturn("maven-surefire-plugin");
        when(surefireMojo.getGoal()).thenReturn("test");
        when(surefireMojo.getExecutionId()).thenReturn("default-test");

        ExecutionEvent surefireEvent = mock(ExecutionEvent.class);
        when(surefireEvent.getType()).thenReturn(ExecutionEvent.Type.MojoSucceeded);
        when(surefireEvent.getMojoExecution()).thenReturn(surefireMojo);
        when(surefireEvent.getProject()).thenReturn(project);
        spy.onEvent(surefireEvent);

        // Failsafe fails (3 integration tests, 1 failure)
        MojoExecution failsafeMojo = mock(MojoExecution.class);
        when(failsafeMojo.getArtifactId()).thenReturn("maven-failsafe-plugin");
        when(failsafeMojo.getGoal()).thenReturn("verify");
        when(failsafeMojo.getExecutionId()).thenReturn("default-verify");

        ExecutionEvent failsafeEvent = mock(ExecutionEvent.class);
        when(failsafeEvent.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(failsafeEvent.getMojoExecution()).thenReturn(failsafeMojo);
        when(failsafeEvent.getProject()).thenReturn(project);
        spy.onEvent(failsafeEvent);

        // Project fails, session ends
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectFailed));
        spy.onEvent(mockSessionEnded());

        String result = output();
        // Both plugins should be parsed: surefire (2) + failsafe (3) = 5 total
        // The failsafe failure should be reported
        assertTrue(result.contains("MSE:FAIL maven-failsafe-plugin:verify @ my-app"),
                "Failsafe failure should be reported: " + result);
        assertTrue(result.contains("MSE:TESTS total=3 passed=2 failed=1 errors=0 skipped=0"),
                "Failsafe test results should be emitted: " + result);
        assertTrue(result.contains("MSE:TEST_FAIL com.example.IntegrationIT#testBad"),
                "Failsafe test failure detail should be reported: " + result);
        // Total accumulated: 2 (surefire) + 3 (failsafe) = 5, passed = 4
        assertTrue(result.contains("tests=4/5"),
                "Total accumulated tests should be 5 with 4 passed: " + result);
    }

    @Test
    void compilerFailureEmitsErrors() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn("my-app");
        when(event.getProject()).thenReturn(project);

        // Use FakeMojoFailureException to expose getLongMessage() via reflection
        String compilerOutput = "[ERROR] /src/main/java/com/example/App.java:[10,15] cannot find symbol\n"
                + "[ERROR] /src/main/java/com/example/App.java:[20,1] ';' expected";
        when(event.getException()).thenReturn(new FakeMojoFailureException(compilerOutput));

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-compiler-plugin:compile @ my-app"));
        assertTrue(result.contains("MSE:ERR /src/main/java/com/example/App.java:10:15 cannot find symbol"));
        assertTrue(result.contains("MSE:ERR /src/main/java/com/example/App.java:20:1 ';' expected"));
    }

    @Test
    void compilerFailureWithNoErrorsInExceptionEmitsNoErrors() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn("my-app");
        when(event.getProject()).thenReturn(project);
        when(event.getException()).thenReturn(new RuntimeException("Compilation failure"));

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-compiler-plugin:compile @ my-app"));
        assertFalse(result.contains("MSE:ERR "), "Should not emit MSE:ERR for non-parseable exception: " + result);
    }

    @Test
    void compilerFailureWithNullExceptionEmitsNoErrors() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn("my-app");
        when(event.getProject()).thenReturn(project);
        when(event.getException()).thenReturn(null);

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:FAIL maven-compiler-plugin:compile @ my-app"));
        assertFalse(result.contains("MSE:ERR "));
    }

    @Test
    void compilerErrorsIncludedInBuildFailed() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn("my-app");
        when(event.getProject()).thenReturn(project);

        String compilerOutput = "[ERROR] /src/App.java:[10,5] cannot find symbol\n"
                + "[ERROR] /src/App.java:[20,1] ';' expected";
        when(event.getException()).thenReturn(new FakeMojoFailureException(compilerOutput));

        spy.onEvent(event);
        spy.onEvent(mockProjectEvent(ExecutionEvent.Type.ProjectFailed));
        spy.onEvent(mockSessionEnded());

        String result = output();
        assertTrue(result.contains("compiler_errors=2"), "BUILD_FAILED should include compiler_errors: " + result);
    }

    @Test
    void compilerFailureWithCauseChainFindsLongMessage() throws Exception {
        spy.onEvent(mockSessionStarted(1, Collections.singletonList("compile")));

        MojoExecution mojo = mock(MojoExecution.class);
        when(mojo.getArtifactId()).thenReturn("maven-compiler-plugin");
        when(mojo.getGoal()).thenReturn("compile");
        when(mojo.getExecutionId()).thenReturn("default-compile");

        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.MojoFailed);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn("my-app");
        when(event.getProject()).thenReturn(project);

        // Wrap FakeMojoFailureException inside a RuntimeException
        String compilerOutput = "[ERROR] /src/App.java:[5,1] error: cannot find symbol";
        RuntimeException wrapper = new RuntimeException("Compilation failure",
                new FakeMojoFailureException(compilerOutput));
        when(event.getException()).thenReturn(wrapper);

        spy.onEvent(event);

        String result = output();
        assertTrue(result.contains("MSE:ERR /src/App.java:5:1 error: cannot find symbol"),
                "Should find compiler errors in cause chain: " + result);
    }

    @Test
    void extractCompilerOutputWithNullException() {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getException()).thenReturn(null);
        String result = SilentEventSpy.extractCompilerOutput(event);
        assertNull(result);
    }

    @Test
    void extractCompilerOutputDepthLimit() {
        // Chain of 6 RuntimeExceptions (depth > 5), then a FakeMojoFailureException
        String compilerOutput = "[ERROR] /src/App.java:[1,1] deep error";
        Exception inner = new FakeMojoFailureException(compilerOutput);
        RuntimeException current = new RuntimeException("wrapper 0", inner);
        for (int i = 1; i < 6; i++) {
            current = new RuntimeException("wrapper " + i, current);
        }
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getException()).thenReturn(current);

        String result = SilentEventSpy.extractCompilerOutput(event);
        // Depth limit is 5, so the fake at depth 7 shouldn't be reached
        // Should fall back to getMessage() of the original exception
        assertEquals("wrapper 5", result);
    }

    // --- Helper methods ---

    /**
     * Fake exception class that exposes getLongMessage() via reflection,
     * simulating MojoFailureException's behavior.
     */
    private static class FakeMojoFailureException extends Exception {
        private final String longMessage;

        FakeMojoFailureException(String longMessage) {
            super("Compilation failure");
            this.longMessage = longMessage;
        }

        @SuppressWarnings("unused") // called via reflection
        public String getLongMessage() {
            return longMessage;
        }
    }

    private ExecutionEvent mockSessionStarted(int moduleCount, java.util.List<String> goals) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.SessionStarted);
        MavenSession session = mock(MavenSession.class);
        when(event.getSession()).thenReturn(session);
        when(session.getGoals()).thenReturn(goals);

        java.util.List<MavenProject> projects = new java.util.ArrayList<MavenProject>();
        for (int i = 0; i < moduleCount; i++) {
            projects.add(mock(MavenProject.class));
        }
        when(session.getProjects()).thenReturn(projects);
        return event;
    }

    private ExecutionEvent mockSessionEnded() {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(ExecutionEvent.Type.SessionEnded);
        return event;
    }

    private ExecutionEvent mockProjectEvent(ExecutionEvent.Type type) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(type);
        return event;
    }

    private ExecutionEvent mockMojoEvent(ExecutionEvent.Type type, MojoExecution mojo, String artifactId) {
        ExecutionEvent event = mock(ExecutionEvent.class);
        when(event.getType()).thenReturn(type);
        when(event.getMojoExecution()).thenReturn(mojo);
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifactId()).thenReturn(artifactId);
        when(event.getProject()).thenReturn(project);
        return event;
    }

    /**
     * Test subclass that overrides activation to always be true.
     */
    private static class TestableSilentEventSpy extends SilentEventSpy {
        TestableSilentEventSpy(PrintStream testOut) {
            super(testOut);
        }

        @Override
        boolean isActivated() {
            return true;
        }
    }

    /**
     * Test subclass that overrides activation to always be false,
     * avoiding dependency on the external MSE_ACTIVE environment variable.
     */
    private static class InactiveTestSpy extends SilentEventSpy {
        InactiveTestSpy(PrintStream testOut) {
            super(testOut);
        }

        @Override
        boolean isActivated() {
            return false;
        }
    }
}
