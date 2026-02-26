package jerrinot.info.llmaven;

import jerrinot.info.llmaven.model.TestFailure;
import jerrinot.info.llmaven.model.TestSummary;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BuildStateTest {

    @Test
    void initialState() {
        BuildState state = new BuildState(5, 1000L);
        assertEquals(5, state.getTotalModules());
        assertEquals(0, state.getSucceededModules());
        assertEquals(0, state.getFailedModules());
        assertEquals(0, state.getTestTotal());
        assertFalse(state.isBuildFailed());
    }

    @Test
    void initialStateAllTestCountersAreZero() {
        BuildState state = new BuildState(1, 0L);
        assertEquals(0, state.getTestTotal());
        assertEquals(0, state.getTestPassed());
        assertEquals(0, state.getTestFailed());
        assertEquals(0, state.getTestErrors());
        assertEquals(0, state.getTestSkipped());
    }

    @Test
    void moduleCounters() {
        BuildState state = new BuildState(3);
        state.moduleSucceeded();
        state.moduleSucceeded();
        state.moduleFailed();
        assertEquals(2, state.getSucceededModules());
        assertEquals(1, state.getFailedModules());
        assertTrue(state.isBuildFailed());
    }

    @Test
    void accumulateTests() {
        BuildState state = new BuildState(2);
        TestSummary s1 = new TestSummary(10, 1, 0, 2, Collections.<TestFailure>emptyList());
        TestSummary s2 = new TestSummary(5, 0, 1, 0, Collections.<TestFailure>emptyList());
        state.accumulateTests(s1);
        state.accumulateTests(s2);
        assertEquals(15, state.getTestTotal());
        assertEquals(11, state.getTestPassed()); // 10-1-0-2 + 5-0-1-0
        assertEquals(1, state.getTestFailed());
        assertEquals(1, state.getTestErrors());
        assertEquals(2, state.getTestSkipped());
    }

    @Test
    void elapsedTime() {
        long now = System.currentTimeMillis();
        BuildState state = new BuildState(1, now - 5000);
        // Store the result in a variable to avoid TOCTOU between the two assertions
        long elapsed = state.getElapsedSeconds();
        assertTrue(elapsed >= 4 && elapsed <= 6,
                "Expected elapsed between 4 and 6 but was: " + elapsed);
    }

    @Test
    void buildNotFailedByDefault() {
        BuildState state = new BuildState(1);
        assertFalse(state.isBuildFailed());
        state.moduleSucceeded();
        assertFalse(state.isBuildFailed());
    }

    @Test
    void setBuildFailedMarksFailureWithoutIncrementingModuleCounter() {
        BuildState state = new BuildState(2, 0L);
        assertFalse(state.isBuildFailed());
        state.setBuildFailed();
        assertTrue(state.isBuildFailed());
        assertEquals(0, state.getFailedModules(), "setBuildFailed should not increment failedModules");
        assertEquals(0, state.getSucceededModules());
    }

    @Test
    void moduleFailedSetsFailedFlag() {
        BuildState state = new BuildState(1, 0L);
        state.moduleFailed();
        assertTrue(state.isBuildFailed());
        assertEquals(1, state.getFailedModules());
    }

    @Test
    void getStartTimeMillisReturnsConstructorValue() {
        BuildState state = new BuildState(1, 42_000L);
        assertEquals(42_000L, state.getStartTimeMillis());
    }

    @Test
    void elapsedTimeZeroWhenJustCreated() {
        long now = System.currentTimeMillis();
        BuildState state = new BuildState(1, now);
        assertTrue(state.getElapsedSeconds() <= 1, "Elapsed should be 0 or 1 second");
    }

    @Test
    void zeroModules() {
        BuildState state = new BuildState(0, 0L);
        assertEquals(0, state.getTotalModules());
        assertFalse(state.isBuildFailed());
    }

    @Test
    void accumulateEmptySummary() {
        BuildState state = new BuildState(1, 0L);
        state.accumulateTests(TestSummary.EMPTY);
        assertEquals(0, state.getTestTotal());
        assertEquals(0, state.getTestPassed());
        assertEquals(0, state.getTestFailed());
        assertEquals(0, state.getTestErrors());
        assertEquals(0, state.getTestSkipped());
    }

    @Test
    void concurrentAccumulateTestsIsThreadSafe() throws Exception {
        BuildState state = new BuildState(10, 0L);
        int threadCount = 8;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        TestSummary summary = new TestSummary(10, 1, 1, 1,
                                Collections.<TestFailure>emptyList());
                        state.accumulateTests(summary);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();

        int expectedTotal = threadCount * iterationsPerThread * 10;
        int expectedFailed = threadCount * iterationsPerThread * 1;
        int expectedErrors = threadCount * iterationsPerThread * 1;
        int expectedSkipped = threadCount * iterationsPerThread * 1;
        // passed per summary = 10 - 1 - 1 - 1 = 7
        int expectedPassed = threadCount * iterationsPerThread * 7;

        assertEquals(expectedTotal, state.getTestTotal());
        assertEquals(expectedFailed, state.getTestFailed());
        assertEquals(expectedErrors, state.getTestErrors());
        assertEquals(expectedSkipped, state.getTestSkipped());
        assertEquals(expectedPassed, state.getTestPassed());
    }

    @Test
    void concurrentModuleSucceededAndFailedIsThreadSafe() throws Exception {
        int threadCount = 8;
        int iterationsPerThread = 1000;
        BuildState state = new BuildState(threadCount * iterationsPerThread * 2, 0L);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        state.moduleSucceeded();
                        state.moduleFailed();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();

        assertEquals(threadCount * iterationsPerThread, state.getSucceededModules());
        assertEquals(threadCount * iterationsPerThread, state.getFailedModules());
        assertTrue(state.isBuildFailed());
    }

    @Test
    void multipleSetBuildFailedCallsAreIdempotent() {
        BuildState state = new BuildState(1, 0L);
        state.setBuildFailed();
        state.setBuildFailed();
        state.setBuildFailed();
        assertTrue(state.isBuildFailed());
        assertEquals(0, state.getFailedModules());
    }

    @Test
    void getTestPassedClampedToZeroWhenSumExceedsTotal() {
        BuildState state = new BuildState(1, 0L);
        // total=5, failures=3, errors=3, skipped=3 => sum(9) > total(5)
        // derived passed = 5 - 3 - 3 - 3 = -4, should be clamped to 0
        TestSummary inconsistent = new TestSummary(5, 3, 3, 3,
                Collections.<TestFailure>emptyList());
        state.accumulateTests(inconsistent);
        assertEquals(5, state.getTestTotal());
        assertEquals(3, state.getTestFailed());
        assertEquals(3, state.getTestErrors());
        assertEquals(3, state.getTestSkipped());
        assertEquals(0, state.getTestPassed(),
                "getTestPassed should return 0 (clamped) when failures+errors+skipped > total");
    }

    @Test
    void elapsedSecondsClampedToZeroForFutureStartTime() {
        BuildState state = new BuildState(1, System.currentTimeMillis() + 60000);
        assertEquals(0, state.getElapsedSeconds(),
                "getElapsedSeconds should be clamped to 0 for future start time");
    }

    @Test
    void compilerErrorsInitiallyZero() {
        BuildState state = new BuildState(1, 0L);
        assertEquals(0, state.getCompilerErrors());
    }

    @Test
    void addCompilerErrors() {
        BuildState state = new BuildState(1, 0L);
        state.addCompilerErrors(3);
        assertEquals(3, state.getCompilerErrors());
        state.addCompilerErrors(2);
        assertEquals(5, state.getCompilerErrors());
    }

    @Test
    void concurrentAddCompilerErrorsIsThreadSafe() throws Exception {
        BuildState state = new BuildState(1, 0L);
        int threadCount = 8;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        state.addCompilerErrors(1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();

        assertEquals(threadCount * iterationsPerThread, state.getCompilerErrors());
    }

    @Test
    void accumulateMultipleSummariesAddsUp() {
        BuildState state = new BuildState(3, 0L);
        TestSummary s1 = new TestSummary(5, 1, 0, 1, Collections.<TestFailure>emptyList());
        TestSummary s2 = new TestSummary(3, 0, 0, 0, Collections.<TestFailure>emptyList());
        TestSummary s3 = new TestSummary(7, 0, 2, 1, Collections.<TestFailure>emptyList());

        state.accumulateTests(s1);
        state.accumulateTests(s2);
        state.accumulateTests(s3);

        assertEquals(15, state.getTestTotal());
        assertEquals(1, state.getTestFailed());
        assertEquals(2, state.getTestErrors());
        assertEquals(2, state.getTestSkipped());
        // passed = (5-1-0-1) + (3-0-0-0) + (7-0-2-1) = 3 + 3 + 4 = 10
        assertEquals(10, state.getTestPassed());
    }
}
