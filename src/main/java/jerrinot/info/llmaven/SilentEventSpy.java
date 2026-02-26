package jerrinot.info.llmaven;

import jerrinot.info.llmaven.model.CompilerError;
import jerrinot.info.llmaven.model.TestSummary;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Named("silent-spy")
@Singleton
public class SilentEventSpy extends AbstractEventSpy {

    private static final Set<String> TEST_PLUGINS = Set.of(
            "maven-surefire-plugin", "maven-failsafe-plugin");
    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    private static final String REDIRECT_TEST_OUTPUT_PROP = "maven.test.redirectTestOutputToFile";

    private final ArtifactParser artifactParser = new ArtifactParser();
    private final OutputFormatter formatter;

    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean loggingSuppressed = new AtomicBoolean();
    private final AtomicBoolean redirectFailed = new AtomicBoolean();
    private volatile BuildState buildState;
    private final Set<String> parsedModules = ConcurrentHashMap.newKeySet();
    private final Set<File> reportsDirs = ConcurrentHashMap.newKeySet();
    private volatile String previousLogLevel;
    private volatile String previousRedirectTestOutput;
    private volatile MavenSession session;
    private volatile PrintStream originalOut;
    private volatile PrintStream originalErr;
    private volatile File buildLogFile;
    private volatile PrintStream fileStream;

    public SilentEventSpy() {
        this(System.out);
    }

    SilentEventSpy(PrintStream out) {
        this.formatter = new OutputFormatter(out);
    }

    @Override
    public void init(Context context) throws Exception {
        boolean activated = isActivated();
        active.set(activated);
        if (activated) {
            try {
                suppressMavenLogging();
            } catch (Exception e) {
                active.set(false);
                formatter.emitPassthrough("init failed: " + e.getMessage());
            }
        }
    }

    private void suppressConsoleOutput() {
        originalOut = System.out;
        originalErr = System.err;
        PrintStream noOp = new PrintStream(OutputStream.nullOutputStream());
        System.setOut(noOp);
        System.setErr(noOp);
    }

    private void redirectConsoleToFile(File topLevelBaseDir) {
        if (topLevelBaseDir == null) return;
        suppressConsoleOutput();
        buildLogFile = new File(topLevelBaseDir, "target/mse-build.log");
        OutputStream lazy = new OutputStream() {
            private FileOutputStream delegate;

            private synchronized FileOutputStream open() throws java.io.IOException {
                if (delegate == null) {
                    buildLogFile.getParentFile().mkdirs();
                    delegate = new FileOutputStream(buildLogFile);
                }
                return delegate;
            }

            @Override
            public void write(int b) throws java.io.IOException {
                try {
                    open().write(b);
                } catch (java.io.IOException e) {
                    onRedirectFailure(e);
                    throw e;
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                try {
                    open().write(b, off, len);
                } catch (java.io.IOException e) {
                    onRedirectFailure(e);
                    throw e;
                }
            }

            @Override
            public synchronized void flush() throws java.io.IOException {
                if (delegate != null) {
                    try {
                        delegate.flush();
                    } catch (java.io.IOException e) {
                        onRedirectFailure(e);
                        throw e;
                    }
                }
            }

            @Override
            public synchronized void close() throws java.io.IOException {
                if (delegate != null) delegate.close();
            }
        };
        PrintStream ps = new PrintStream(lazy, true);
        this.fileStream = ps;
        System.setOut(ps);
        System.setErr(ps);
    }

    private void onRedirectFailure(java.io.IOException e) {
        if (!redirectFailed.compareAndSet(false, true)) return;
        restoreConsoleOutput();
        if (fileStream != null) {
            fileStream.close();
        }
        buildLogFile = null;
        formatter.emitPassthrough("build-log redirect failed: " + e.getMessage()
                + ". Continuing with console output."
                + " Set MSE_REDIRECT_STDIO=false to disable redirection.");
    }

    private void restoreConsoleOutput() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        if (originalErr != null) {
            System.setErr(originalErr);
        }
    }

    /**
     * Suppress Maven's SLF4J-based logging by setting the default level to ERROR
     * and reinitializing the logging backend. This uses Maven's internal friend
     * classes (MavenSlf4jFriend, MavenSlf4jSimpleFriend) which are on the classpath
     * for core extensions. Falls back gracefully if unavailable.
     */
    private void suppressMavenLogging() {
        previousLogLevel = System.getProperty("org.slf4j.simpleLogger.defaultLogLevel");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        resetSlf4j();
        loggingSuppressed.set(true);
    }

    private void restoreMavenLogging() {
        if (!loggingSuppressed.compareAndSet(true, false)) return;
        if (previousLogLevel != null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", previousLogLevel);
        } else {
            System.clearProperty("org.slf4j.simpleLogger.defaultLogLevel");
        }
        resetSlf4j();
    }

    private void resetSlf4j() {
        try {
            Class<?> friendClass = Class.forName("org.slf4j.MavenSlf4jFriend");
            Method reset = friendClass.getMethod("reset");
            reset.invoke(null);

            Class<?> simpleFriend = Class.forName("org.slf4j.impl.MavenSlf4jSimpleFriend");
            Method init = simpleFriend.getMethod("init");
            init.invoke(null);
        } catch (Exception e) {
            // Not fatal — logging suppression is best-effort.
            // On unsupported Maven versions, [INFO] lines will leak through.
        }
    }

    @Override
    public void close() throws Exception {
        try {
            restoreConsoleOutput();
            if (fileStream != null) {
                fileStream.close();
            }
            restoreMavenLogging();
            restoreTestOutput();
        } catch (Exception e) {
            // Best-effort: never let logging restoration break Maven's shutdown
        }
        ArtifactParser.clearThreadLocal();
    }

    boolean isActivated() {
        return "true".equalsIgnoreCase(System.getenv("MSE_ACTIVE"));
    }

    private boolean isRedirectEnabled() {
        return !"false".equalsIgnoreCase(System.getenv("MSE_REDIRECT_STDIO"));
    }

    @Override
    public void onEvent(Object event) throws Exception {
        if (!active.get()) return;
        if (!(event instanceof ExecutionEvent)) return;

        ExecutionEvent ee = (ExecutionEvent) event;
        try {
            dispatch(ee);
        } catch (Exception e) {
            formatter.emitPassthrough(e.getClass().getSimpleName() + ": " + e.getMessage());
            active.set(false);
            restoreConsoleOutput();
            if (fileStream != null) {
                fileStream.close();
            }
            restoreMavenLogging();
            restoreTestOutput();
        }
    }

    private void dispatch(ExecutionEvent ee) {
        if (ee.getType() != ExecutionEvent.Type.SessionStarted && buildState == null) {
            return;
        }
        switch (ee.getType()) {
            case SessionStarted:
                handleSessionStarted(ee);
                break;
            case MojoSucceeded:
                handleMojoSucceeded(ee);
                break;
            case MojoFailed:
                handleMojoFailed(ee);
                break;
            case ProjectSucceeded:
                buildState.moduleSucceeded();
                break;
            case ProjectFailed:
                buildState.moduleFailed();
                break;
            case SessionEnded:
                handleSessionEnded();
                break;
            default:
                // Suppress all other lifecycle noise
                break;
        }
    }

    private void handleSessionStarted(ExecutionEvent ee) {
        session = ee.getSession();
        suppressTestOutput();
        List<MavenProject> projects = session.getProjects();
        int moduleCount = projects != null ? projects.size() : 0;
        if (isRedirectEnabled() && projects != null && !projects.isEmpty()) {
            redirectConsoleToFile(projects.get(0).getBasedir());
        }
        List<String> goals = session.getGoals();
        buildState = new BuildState(moduleCount);
        parsedModules.clear();
        formatter.emitSessionStart(moduleCount, goals);
    }

    private void suppressTestOutput() {
        java.util.Properties userProps = session.getUserProperties();
        if (userProps == null) return;
        previousRedirectTestOutput = userProps.getProperty(REDIRECT_TEST_OUTPUT_PROP);
        userProps.setProperty(REDIRECT_TEST_OUTPUT_PROP, "true");
    }

    private void restoreTestOutput() {
        if (session == null) return;
        java.util.Properties userProps = session.getUserProperties();
        if (userProps == null) return;
        if (previousRedirectTestOutput != null) {
            userProps.setProperty(REDIRECT_TEST_OUTPUT_PROP, previousRedirectTestOutput);
        } else {
            userProps.remove(REDIRECT_TEST_OUTPUT_PROP);
        }
    }

    private void handleMojoSucceeded(ExecutionEvent ee) {
        MojoExecution mojo = ee.getMojoExecution();
        if (mojo == null || !isTestPlugin(mojo)) return;

        MavenProject project = ee.getProject();
        if (project == null) return;

        parseAndAccumulateTests(project, mojo);
    }

    private void handleMojoFailed(ExecutionEvent ee) {
        buildState.setBuildFailed();

        MojoExecution mojo = ee.getMojoExecution();
        if (mojo == null) return;

        MavenProject project = ee.getProject();
        String moduleId = project != null ? project.getArtifactId() : "unknown";

        formatter.emitFail(
                mojo.getArtifactId(),
                mojo.getGoal(),
                mojo.getExecutionId(),
                moduleId);

        if (isTestPlugin(mojo) && project != null) {
            parseAndAccumulateTests(project, mojo);
        } else if (isCompilerPlugin(mojo)) {
            parseAndEmitCompilerErrors(ee);
        }
    }

    private void parseAndAccumulateTests(MavenProject project, MojoExecution mojo) {
        File baseDir = project.getBasedir();
        if (baseDir == null) return;

        String reportsSubdir = "maven-failsafe-plugin".equals(mojo.getArtifactId())
                ? "target/failsafe-reports" : "target/surefire-reports";
        String parseKey = project.getGroupId() + ":" + project.getArtifactId() + ":" + mojo.getExecutionId() + ":" + reportsSubdir;
        if (!parsedModules.add(parseKey)) return;

        File reportsDir = new File(baseDir, reportsSubdir);
        reportsDirs.add(reportsDir);
        TestSummary summary = artifactParser.parseReportsDir(reportsDir,
                formatter::emitPassthrough);
        buildState.accumulateTests(summary);
        if (summary.hasFailures()) {
            formatter.emitTestResults(summary);
        }
    }

    private void parseAndEmitCompilerErrors(ExecutionEvent ee) {
        String output = extractCompilerOutput(ee);
        if (output == null || output.isEmpty()) return;
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        if (!errors.isEmpty()) {
            formatter.emitCompilerErrors(errors);
            buildState.addCompilerErrors(errors.size());
        }
    }

    static String extractCompilerOutput(ExecutionEvent ee) {
        Throwable cause = ee.getException();
        for (int depth = 0; cause != null && depth < 5; depth++) {
            String longMsg = getLongMessage(cause);
            if (longMsg != null && !longMsg.isEmpty()) {
                return longMsg;
            }
            cause = cause.getCause();
        }
        // Fall back to getMessage() on the original exception
        Throwable original = ee.getException();
        return original != null ? original.getMessage() : null;
    }

    private static String getLongMessage(Throwable t) {
        try {
            Method m = t.getClass().getMethod("getLongMessage");
            Object result = m.invoke(t);
            return result instanceof String ? (String) result : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleSessionEnded() {
        formatter.emitTestOutputPaths(reportsDirs);
        if (buildLogFile != null && buildLogFile.exists()) {
            formatter.emitBuildLog(buildLogFile);
        }
        if (buildState.isBuildFailed()) {
            formatter.emitBuildFailed(buildState);
        } else {
            formatter.emitOk(buildState);
        }
    }

    private static boolean isTestPlugin(MojoExecution mojo) {
        return TEST_PLUGINS.contains(mojo.getArtifactId());
    }

    private static boolean isCompilerPlugin(MojoExecution mojo) {
        return COMPILER_PLUGIN.equals(mojo.getArtifactId());
    }
}
