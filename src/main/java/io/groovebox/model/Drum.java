package io.groovebox.model;

public enum Drum implements Voice {
    KICK,
    SNARE,
    CLOSED_HAT,
    OPEN_HAT,
    CLAP;

    public int gmPercussionNote() {
        return switch (this) {
            case KICK -> 36;
            case SNARE -> 38;
            case CLAP -> 39;
            case CLOSED_HAT -> 42;
            case OPEN_HAT -> 46;
        };
    }
}
