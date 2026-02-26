package jerrinot.info.mse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SilentEventSpySessionStateTest {

    private ByteArrayOutputStream baos;
    private SilentEventSpy spy;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        spy = new SilentEventSpy(new PrintStream(baos, true));
    }

    @Test
    void resetSessionStateClearsSessionScopedFields() throws Exception {
        Set<String> parsedModules = getField("parsedModules");
        parsedModules.add("module-a:module-a:default-test:target/surefire-reports");

        Set<File> reportsDirs = getField("reportsDirs");
        reportsDirs.add(new File("target/surefire-reports"));

        AtomicBoolean redirectFailed = getField("redirectFailed");
        redirectFailed.set(true);

        setField("buildLogFile", new File("target/mse-build.log"));
        setField("buildState", new BuildState(2));
        setField("fileStream", new PrintStream(new ByteArrayOutputStream(), true));
        setField("originalOut", System.out);
        setField("originalErr", System.err);

        invokePrivate("resetSessionState");

        assertTrue(parsedModules.isEmpty());
        assertTrue(reportsDirs.isEmpty());
        assertFalse(redirectFailed.get());
        assertNull(getField("buildLogFile"));
        assertNull(getField("buildState"));
        assertNull(getField("session"));
        assertNull(getField("previousRedirectTestOutput"));
        assertNull(getField("fileStream"));
        assertNull(getField("originalOut"));
        assertNull(getField("originalErr"));
    }

    @Test
    void sessionEndedEmitsAndThenCleansSessionState() throws Exception {
        File reportsDir = new File("target/surefire-reports");
        Set<File> reportsDirs = getField("reportsDirs");
        reportsDirs.add(reportsDir);

        Set<String> parsedModules = getField("parsedModules");
        parsedModules.add("module-a:module-a:default-test:target/surefire-reports");

        AtomicBoolean redirectFailed = getField("redirectFailed");
        redirectFailed.set(true);

        setField("buildState", new BuildState(1));

        invokePrivate("handleSessionEnded");

        String result = baos.toString();
        assertTrue(result.contains("MSE:TEST_OUTPUT " + reportsDir.getAbsolutePath()));
        assertTrue(result.contains("MSE:OK modules=1 passed=0 failed=0 errors=0 skipped=0"));

        assertTrue(parsedModules.isEmpty());
        assertTrue(reportsDirs.isEmpty());
        assertFalse(redirectFailed.get());
        assertNull(getField("buildState"));
        assertNull(getField("buildLogFile"));
        assertNull(getField("session"));
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String name) throws Exception {
        Field f = SilentEventSpy.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(spy);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SilentEventSpy.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(spy, value);
    }

    private void invokePrivate(String methodName) throws Exception {
        Method m = SilentEventSpy.class.getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(spy);
    }
}
