package jerrinot.info.llmaven.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSummaryTest {

    @Test
    void emptyConstantHasZeroCounts() {
        TestSummary empty = TestSummary.EMPTY;
        assertEquals(0, empty.getTotal());
        assertEquals(0, empty.getFailures());
        assertEquals(0, empty.getErrors());
        assertEquals(0, empty.getSkipped());
        assertEquals(0, empty.getPassed());
        assertFalse(empty.hasFailures());
        assertTrue(empty.getFailureDetails().isEmpty());
    }

    @Test
    void emptyConstantIsSingleton() {
        assertSame(TestSummary.EMPTY, TestSummary.EMPTY);
    }

    @Test
    void getPassedComputesCorrectly() {
        TestSummary summary = new TestSummary(10, 2, 1, 3, Collections.emptyList());
        assertEquals(4, summary.getPassed()); // 10 - 2 - 1 - 3
    }

    @Test
    void getPassedNeverReturnsNegative() {
        // When total < failures + errors + skipped, getPassed should return 0
        TestSummary summary = new TestSummary(5, 3, 3, 3, Collections.emptyList());
        assertEquals(0, summary.getPassed(), "getPassed should return 0 when computation would be negative");
    }

    @Test
    void getPassedReturnsZeroWhenAllFailed() {
        TestSummary summary = new TestSummary(3, 3, 0, 0, Collections.emptyList());
        assertEquals(0, summary.getPassed());
    }

    @Test
    void getPassedReturnsTotalWhenNoFailuresOrSkipped() {
        TestSummary summary = new TestSummary(10, 0, 0, 0, Collections.emptyList());
        assertEquals(10, summary.getPassed());
    }

    @Test
    void hasFailuresWithOnlyFailures() {
        TestSummary summary = new TestSummary(5, 2, 0, 0, Collections.emptyList());
        assertTrue(summary.hasFailures());
    }

    @Test
    void hasFailuresWithOnlyErrors() {
        TestSummary summary = new TestSummary(5, 0, 3, 0, Collections.emptyList());
        assertTrue(summary.hasFailures());
    }

    @Test
    void hasFailuresWithBothFailuresAndErrors() {
        TestSummary summary = new TestSummary(5, 1, 1, 0, Collections.emptyList());
        assertTrue(summary.hasFailures());
    }

    @Test
    void hasFailuresFalseWithOnlySkipped() {
        TestSummary summary = new TestSummary(5, 0, 0, 5, Collections.emptyList());
        assertFalse(summary.hasFailures());
    }

    @Test
    void failureDetailsIsUnmodifiable() {
        List<TestFailure> mutableList = new ArrayList<>();
        mutableList.add(new TestFailure(TestFailure.Kind.FAILURE, "A", "m", "msg", "stack"));
        TestSummary summary = new TestSummary(1, 1, 0, 0, mutableList);

        List<TestFailure> details = summary.getFailureDetails();
        assertThrows(UnsupportedOperationException.class, () -> details.add(
                new TestFailure(TestFailure.Kind.ERROR, "B", "n", "msg2", "stack2")));
    }

    @Test
    void failureDetailsNotAffectedByOriginalListMutation() {
        List<TestFailure> mutableList = new ArrayList<>();
        mutableList.add(new TestFailure(TestFailure.Kind.FAILURE, "A", "m", "msg", "stack"));
        TestSummary summary = new TestSummary(1, 1, 0, 0, mutableList);

        // Mutate the original list after construction
        mutableList.add(new TestFailure(TestFailure.Kind.ERROR, "B", "n", "msg2", "stack2"));

        // List.copyOf makes a defensive copy, so mutations to the original list
        // are NOT visible through the summary's getter. This is correct behavior.
        assertEquals(1, summary.getFailureDetails().size(),
                "List.copyOf should produce a defensive copy; original list mutation should not be visible");
    }

    @Test
    void emptyConstantFailureDetailsIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> TestSummary.EMPTY.getFailureDetails().add(
                        new TestFailure(TestFailure.Kind.FAILURE, "A", "m", "msg", "stack")));
    }

    @Test
    void toStringContainsCounts() {
        TestSummary summary = new TestSummary(10, 2, 1, 3, Collections.emptyList());
        String s = summary.toString();
        assertTrue(s.contains("total=10"));
        assertTrue(s.contains("passed=4"));
        assertTrue(s.contains("failures=2"));
        assertTrue(s.contains("errors=1"));
        assertTrue(s.contains("skipped=3"));
    }

    @Test
    void nullFailureDetailsThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new TestSummary(1, 0, 0, 0, null),
                "Constructor should throw NullPointerException when failureDetails is null");
    }

    @Test
    void constructorStoresAllValues() {
        TestFailure f = new TestFailure(TestFailure.Kind.FAILURE, "Cls", "meth", "msg", "trace");
        List<TestFailure> details = Collections.singletonList(f);
        TestSummary summary = new TestSummary(100, 5, 3, 7, details);

        assertEquals(100, summary.getTotal());
        assertEquals(5, summary.getFailures());
        assertEquals(3, summary.getErrors());
        assertEquals(7, summary.getSkipped());
        assertEquals(85, summary.getPassed()); // 100 - 5 - 3 - 7
        assertTrue(summary.hasFailures());
        assertEquals(1, summary.getFailureDetails().size());
        assertSame(f, summary.getFailureDetails().get(0));
    }
}
