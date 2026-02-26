package jerrinot.info.llmaven.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestFailureTest {

    @Test
    void failureKindAccessors() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE,
                "com.example.AppTest",
                "testMethod",
                "expected 1 but was 2",
                "stack trace here");

        assertEquals(TestFailure.Kind.FAILURE, failure.getKind());
        assertEquals("com.example.AppTest", failure.getClassName());
        assertEquals("testMethod", failure.getMethodName());
        assertEquals("expected 1 but was 2", failure.getMessage());
        assertEquals("stack trace here", failure.getStackTrace());
    }

    @Test
    void errorKindAccessors() {
        TestFailure error = new TestFailure(
                TestFailure.Kind.ERROR,
                "com.example.AppTest",
                "testEdge",
                "NullPointerException",
                "java.lang.NPE at line 5");

        assertEquals(TestFailure.Kind.ERROR, error.getKind());
        assertEquals("com.example.AppTest", error.getClassName());
        assertEquals("testEdge", error.getMethodName());
        assertEquals("NullPointerException", error.getMessage());
        assertEquals("java.lang.NPE at line 5", error.getStackTrace());
    }

    @Test
    void nullKindThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new TestFailure(null, "cls", "m", "msg", "stack"));
    }

    @Test
    void nullClassAndMethodDefaultToEmpty() {
        TestFailure failure = new TestFailure(TestFailure.Kind.FAILURE, null, null, null, null);
        assertEquals("", failure.getClassName());
        assertEquals("", failure.getMethodName());
        assertNull(failure.getMessage());
        assertNull(failure.getStackTrace());
    }

    @Test
    void emptyStringFieldsAllowed() {
        TestFailure failure = new TestFailure(TestFailure.Kind.FAILURE, "", "", "", "");
        assertEquals("", failure.getClassName());
        assertEquals("", failure.getMethodName());
        assertEquals("", failure.getMessage());
        assertEquals("", failure.getStackTrace());
    }

    @Test
    void toStringContainsKeyFields() {
        TestFailure failure = new TestFailure(
                TestFailure.Kind.FAILURE, "com.example.App", "testFoo", "expected 1", "stack");
        String s = failure.toString();
        assertTrue(s.contains("FAILURE"));
        assertTrue(s.contains("com.example.App"));
        assertTrue(s.contains("testFoo"));
        assertTrue(s.contains("expected 1"));
    }

    @Test
    void toStringWithNullClassAndMethodProducesReadableOutput() {
        TestFailure failure = new TestFailure(TestFailure.Kind.FAILURE, null, null, null, null);
        String s = failure.toString();
        // className and methodName default to "" so toString should produce "FAILURE #: null"
        // not "FAILURE null#null: null"
        assertEquals("FAILURE #: null", s);
        assertFalse(s.contains("null#null"), "Should not contain 'null#null' for empty className/methodName");
    }

    @Test
    void toStringWithEmptyClassAndMethodProducesReadableOutput() {
        TestFailure failure = new TestFailure(TestFailure.Kind.ERROR, "", "", "some message", "stack");
        String s = failure.toString();
        assertEquals("ERROR #: some message", s);
    }

    @Test
    void kindEnumValues() {
        TestFailure.Kind[] values = TestFailure.Kind.values();
        assertEquals(2, values.length);
        assertEquals(TestFailure.Kind.FAILURE, TestFailure.Kind.valueOf("FAILURE"));
        assertEquals(TestFailure.Kind.ERROR, TestFailure.Kind.valueOf("ERROR"));
    }
}
