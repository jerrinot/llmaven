package jerrinot.info.llmaven.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompilerErrorTest {

    @Test
    void accessors() {
        CompilerError error = new CompilerError("/src/Main.java", 10, 5, "cannot find symbol");
        assertEquals("/src/Main.java", error.getFile());
        assertEquals(10, error.getLine());
        assertEquals(5, error.getColumn());
        assertEquals("cannot find symbol", error.getMessage());
    }

    @Test
    void nullFileThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new CompilerError(null, 1, 1, "msg"));
    }

    @Test
    void nullMessageThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new CompilerError("/src/Main.java", 1, 1, null));
    }

    @Test
    void toStringContainsAllFields() {
        CompilerError error = new CompilerError("/src/App.java", 42, 8, "';' expected");
        String s = error.toString();
        assertTrue(s.contains("/src/App.java"));
        assertTrue(s.contains("42"));
        assertTrue(s.contains("8"));
        assertTrue(s.contains("';' expected"));
    }

    @Test
    void toStringFormat() {
        CompilerError error = new CompilerError("/src/App.java", 10, 3, "error msg");
        assertEquals("/src/App.java:[10,3] error msg", error.toString());
    }

    @Test
    void zeroLineAndColumn() {
        CompilerError error = new CompilerError("/src/App.java", 0, 0, "msg");
        assertEquals(0, error.getLine());
        assertEquals(0, error.getColumn());
    }
}
