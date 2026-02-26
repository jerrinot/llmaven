package jerrinot.info.llmaven.model;

import java.util.Objects;

public final class TestFailure {

    public enum Kind { FAILURE, ERROR }

    private final Kind kind;
    private final String className;
    private final String methodName;
    private final String message;
    private final String stackTrace;

    public TestFailure(Kind kind, String className, String methodName, String message, String stackTrace) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.className = className != null ? className : "";
        this.methodName = methodName != null ? methodName : "";
        this.message = message;
        this.stackTrace = stackTrace;
    }

    public Kind getKind() { return kind; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getMessage() { return message; }
    public String getStackTrace() { return stackTrace; }

    @Override
    public String toString() {
        return kind + " " + className + "#" + methodName + ": " + message;
    }
}
