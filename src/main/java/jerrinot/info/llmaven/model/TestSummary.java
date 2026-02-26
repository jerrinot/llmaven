package jerrinot.info.llmaven.model;

import java.util.List;

public final class TestSummary {

    public static final TestSummary EMPTY = new TestSummary(0, 0, 0, 0, List.of());

    private final int total;
    private final int failures;
    private final int errors;
    private final int skipped;
    private final List<TestFailure> failureDetails;

    public TestSummary(int total, int failures, int errors, int skipped, List<TestFailure> failureDetails) {
        this.total = total;
        this.failures = failures;
        this.errors = errors;
        this.skipped = skipped;
        this.failureDetails = List.copyOf(failureDetails);
    }

    public int getTotal() { return total; }
    public int getFailures() { return failures; }
    public int getErrors() { return errors; }
    public int getSkipped() { return skipped; }
    public int getPassed() { return Math.max(0, total - failures - errors - skipped); }
    public boolean hasFailures() { return failures > 0 || errors > 0; }
    public List<TestFailure> getFailureDetails() { return failureDetails; }

    @Override
    public String toString() {
        return "TestSummary{total=" + total + ", passed=" + getPassed()
                + ", failures=" + failures + ", errors=" + errors
                + ", skipped=" + skipped + "}";
    }
}
