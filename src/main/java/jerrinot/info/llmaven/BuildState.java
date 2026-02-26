package jerrinot.info.llmaven;

import jerrinot.info.llmaven.model.TestSummary;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class BuildState {

    private final long startTimeMillis;
    private final int totalModules;
    private final AtomicInteger succeededModules = new AtomicInteger();
    private final AtomicInteger failedModules = new AtomicInteger();
    private final AtomicInteger testTotal = new AtomicInteger();
    private final AtomicInteger testFailed = new AtomicInteger();
    private final AtomicInteger testErrors = new AtomicInteger();
    private final AtomicInteger testSkipped = new AtomicInteger();
    private final AtomicInteger compilerErrors = new AtomicInteger();
    private final AtomicBoolean buildFailed = new AtomicBoolean(false);

    BuildState(int totalModules, long startTimeMillis) {
        this.totalModules = totalModules;
        this.startTimeMillis = startTimeMillis;
    }

    public BuildState(int totalModules) {
        this(totalModules, System.currentTimeMillis());
    }

    public void moduleSucceeded() {
        succeededModules.incrementAndGet();
    }

    public void moduleFailed() {
        failedModules.incrementAndGet();
        buildFailed.set(true);
    }

    public void setBuildFailed() {
        buildFailed.set(true);
    }

    public void addCompilerErrors(int count) {
        compilerErrors.addAndGet(count);
    }

    public void accumulateTests(TestSummary summary) {
        testTotal.addAndGet(summary.getTotal());
        testFailed.addAndGet(summary.getFailures());
        testErrors.addAndGet(summary.getErrors());
        testSkipped.addAndGet(summary.getSkipped());
    }

    public int getTotalModules() { return totalModules; }
    public int getSucceededModules() { return succeededModules.get(); }
    public int getFailedModules() { return failedModules.get(); }
    public int getTestTotal() { return testTotal.get(); }
    public int getTestPassed() { return Math.max(0, testTotal.get() - testFailed.get() - testErrors.get() - testSkipped.get()); }
    public int getTestFailed() { return testFailed.get(); }
    public int getTestErrors() { return testErrors.get(); }
    public int getTestSkipped() { return testSkipped.get(); }
    public int getCompilerErrors() { return compilerErrors.get(); }
    public boolean isBuildFailed() { return buildFailed.get(); }

    public long getElapsedSeconds() {
        return Math.max(0, (System.currentTimeMillis() - startTimeMillis) / 1000);
    }

    long getStartTimeMillis() {
        return startTimeMillis;
    }
}
