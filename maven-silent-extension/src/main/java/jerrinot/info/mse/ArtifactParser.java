package jerrinot.info.mse;

import jerrinot.info.mse.model.CompilerError;
import jerrinot.info.mse.model.TestFailure;
import jerrinot.info.mse.model.TestSummary;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ArtifactParser {

    private static final int MAX_STACK_TRACE_LINES = 20;
    private static final Pattern COMPILER_ERROR_PATTERN = Pattern.compile(
            "^\\s*(?:\\[ERROR]\\s+)?(.+?\\.java):\\[(\\d+),(\\d+)]\\s+(.+)$",
            Pattern.MULTILINE);

    // ThreadLocal because DocumentBuilderFactory is not thread-safe; each thread gets its own instance.
    private static final ThreadLocal<DocumentBuilderFactory> DBF = ThreadLocal.withInitial(() -> {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            // XXE protection: reject DOCTYPE declarations and external entity references
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure XML parser", e);
        }
        return dbf;
    });

    static void clearThreadLocal() {
        DBF.remove();
    }

    static List<CompilerError> parseCompilerOutput(String output) {
        List<CompilerError> errors = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return errors;
        }
        Matcher m = COMPILER_ERROR_PATTERN.matcher(output);
        // Parse all matches; OutputFormatter owns display truncation and marker emission.
        while (m.find()) {
            String file = m.group(1);
            int line = Integer.parseInt(m.group(2));
            int column = Integer.parseInt(m.group(3));
            String message = m.group(4);
            errors.add(new CompilerError(file, line, column, message));
        }
        return errors;
    }

    TestSummary parseReportsDir(File reportsDir) {
        return parseReportsDir(reportsDir, msg -> {});
    }

    TestSummary parseReportsDir(File reportsDir, Consumer<String> diagnostics) {
        if (!reportsDir.isDirectory()) {
            return TestSummary.EMPTY;
        }
        File[] xmlFiles = reportsDir.listFiles(
                f -> f.isFile() && f.getName().startsWith("TEST-") && f.getName().endsWith(".xml"));
        if (xmlFiles == null || xmlFiles.length == 0) {
            return TestSummary.EMPTY;
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<TestFailure> failureDetails = new ArrayList<>();

        for (File xml : xmlFiles) {
            try {
                Document doc = parseXml(xml);
                Element root = doc.getDocumentElement();
                int t = intAttr(root, "tests");
                int f = intAttr(root, "failures");
                int e = intAttr(root, "errors");
                int s = intAttr(root, "skipped");
                total += t;
                failures += f;
                errors += e;
                skipped += s;
                extractFailureDetails(root, failureDetails);
            } catch (Exception e) {
                diagnostics.accept("skipping corrupt report " + xml.getName() + ": " + e.getMessage());
            }
        }

        return new TestSummary(total, failures, errors, skipped, failureDetails);
    }

    private void extractFailureDetails(Element root, List<TestFailure> results) {
        NodeList testcases = root.getElementsByTagName("testcase");

        for (int i = 0; i < testcases.getLength(); i++) {
            Element tc = (Element) testcases.item(i);
            String className = tc.getAttribute("classname");
            String methodName = tc.getAttribute("name");

            NodeList failNodes = tc.getElementsByTagName("failure");
            for (int j = 0; j < failNodes.getLength(); j++) {
                Element f = (Element) failNodes.item(j);
                results.add(new TestFailure(
                        TestFailure.Kind.FAILURE,
                        className,
                        methodName,
                        f.getAttribute("message"),
                        truncateStackTrace(f.getTextContent())));
            }

            NodeList errorNodes = tc.getElementsByTagName("error");
            for (int j = 0; j < errorNodes.getLength(); j++) {
                Element e = (Element) errorNodes.item(j);
                results.add(new TestFailure(
                        TestFailure.Kind.ERROR,
                        className,
                        methodName,
                        e.getAttribute("message"),
                        truncateStackTrace(e.getTextContent())));
            }
        }
    }

    private Document parseXml(File xml) throws Exception {
        DocumentBuilder db = DBF.get().newDocumentBuilder();
        db.setErrorHandler(new DefaultHandler());
        return db.parse(xml);
    }

    static String truncateStackTrace(String trace) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        String[] lines = trace.split("\\r?\\n");
        if (lines.length <= MAX_STACK_TRACE_LINES) {
            return String.join("\n", lines).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_STACK_TRACE_LINES; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        int remaining = lines.length - MAX_STACK_TRACE_LINES;
        sb.append("\n\t... ").append(remaining).append(remaining == 1 ? " more line" : " more lines");
        return sb.toString().trim();
    }

    private static int intAttr(Element el, String name) {
        String val = el.getAttribute(name);
        if (val.isEmpty()) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
