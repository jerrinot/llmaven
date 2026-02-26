package jerrinot.info.llmaven.model;

import java.util.Objects;

public final class CompilerError {

    private final String file;
    private final int line;
    private final int column;
    private final String message;

    public CompilerError(String file, int line, int column, String message) {
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.column = column;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getFile() { return file; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return file + ":[" + line + "," + column + "] " + message;
    }
}
