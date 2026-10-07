package io.groovebox.livecode;

/** A mistake in live code, with the character position it was found at. */
public final class LiveCodeException extends RuntimeException {

    private final int position;

    LiveCodeException(String message, int position) {
        super(message);
        this.position = position;
    }

    public int position() {
        return position;
    }

    /** "line 3, column 5: expected ',' or ')'", counted in {@code source}, the text the position points into. */
    public String describe(String source) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(position, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return "line " + line + ", column " + column + ": " + getMessage();
    }
}
