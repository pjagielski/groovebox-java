package io.groovebox.livecode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The part of Tidal-style mini-notation this project understands, describing one cycle:
 *
 * <ul>
 *   <li>{@code bd sd} - steps separated by spaces share the cycle equally; {@code ~} is a rest,</li>
 *   <li>{@code [bd sd]} - a group is a single step, subdivided again,</li>
 *   <li>{@code [bd, hh hh]} - a comma layers sequences over the same span,</li>
 *   <li>{@code hh*4} - a step played that many times within its own slot,</li>
 *   <li>{@code bd(3,8)} and {@code bd(3,8,2)} - k hits spread as evenly as possible over n slots
 *       (Bjorklund), rotated r slots to the left, as in Tidal.</li>
 * </ul>
 */
public final class MiniNotation {

    /** Where in the cycle (0 to 1) something sounds, for how long, and what. */
    public record Event(double start, double duration, String value) {
    }

    private static final double EPSILON = 1e-9;
    private static final int MAX_SUBDIVISION = 128;

    private final List<Event> events;

    private MiniNotation(Node root) {
        List<Event> collected = new ArrayList<>();
        collect(root, 0.0, 1.0, collected);
        collected.sort(Comparator.comparingDouble(Event::start));
        this.events = List.copyOf(collected);
    }

    public static MiniNotation parse(String text) {
        return parse(text, 0);
    }

    /** {@code offset} is where {@code text} starts inside a larger source, so errors point into that. */
    static MiniNotation parse(String text, int offset) {
        return new MiniNotation(new Parser(text, offset).parse());
    }

    public List<Event> events() {
        return events;
    }

    /** The value of the event sounding at {@code position} in the cycle, or null during a rest. */
    public String valueAt(double position) {
        for (Event event : events) {
            if (position >= event.start() - EPSILON && position < event.start() + event.duration() - EPSILON) {
                return event.value();
            }
        }
        return null;
    }

    static boolean[] euclid(int hits, int steps, int rotation) {
        boolean[] pattern = bjorklund(hits, steps);
        boolean[] rotated = new boolean[steps];
        for (int i = 0; i < steps; i++) {
            rotated[i] = pattern[(i + rotation) % steps];
        }
        return rotated;
    }

    /** The same pairing-up of groups Tidal's Bjorklund uses, so (5,8) comes out as x.xx.xx. */
    private static boolean[] bjorklund(int hits, int steps) {
        boolean[] result = new boolean[steps];
        if (hits >= steps) {
            Arrays.fill(result, true);
            return result;
        }
        if (hits <= 0) {
            return result;
        }
        List<List<Boolean>> front = new ArrayList<>();
        List<List<Boolean>> back = new ArrayList<>();
        for (int i = 0; i < hits; i++) {
            front.add(new ArrayList<>(List.of(true)));
        }
        for (int i = 0; i < steps - hits; i++) {
            back.add(new ArrayList<>(List.of(false)));
        }
        while (Math.min(front.size(), back.size()) > 1) {
            if (front.size() > back.size()) {
                List<List<Boolean>> paired = new ArrayList<>(front.subList(0, back.size()));
                List<List<Boolean>> leftOver = new ArrayList<>(front.subList(back.size(), front.size()));
                for (int i = 0; i < paired.size(); i++) {
                    paired.get(i).addAll(back.get(i));
                }
                front = paired;
                back = leftOver;
            } else {
                for (int i = 0; i < front.size(); i++) {
                    front.get(i).addAll(back.get(i));
                }
                back = new ArrayList<>(back.subList(front.size(), back.size()));
            }
        }
        int index = 0;
        for (List<Boolean> group : front) {
            for (boolean hit : group) {
                result[index++] = hit;
            }
        }
        for (List<Boolean> group : back) {
            for (boolean hit : group) {
                result[index++] = hit;
            }
        }
        return result;
    }

    private static void collect(Node node, double start, double length, List<Event> out) {
        switch (node) {
            case Word word -> out.add(new Event(start, length, word.value()));
            case Rest rest -> {
            }
            case Sequence sequence -> {
                double step = length / sequence.steps().size();
                for (int i = 0; i < sequence.steps().size(); i++) {
                    collect(sequence.steps().get(i), start + i * step, step, out);
                }
            }
            case Layers layers -> layers.layers().forEach(layer -> collect(layer, start, length, out));
            case Repeat repeat -> {
                double slot = length / repeat.times();
                for (int i = 0; i < repeat.times(); i++) {
                    collect(repeat.node(), start + i * slot, slot, out);
                }
            }
            case Euclid euclid -> {
                boolean[] hits = euclid(euclid.hits(), euclid.steps(), euclid.rotation());
                double slot = length / hits.length;
                for (int i = 0; i < hits.length; i++) {
                    if (hits[i]) {
                        collect(euclid.node(), start + i * slot, slot, out);
                    }
                }
            }
        }
    }

    private sealed interface Node permits Word, Rest, Sequence, Layers, Repeat, Euclid {
    }

    private record Word(String value) implements Node {
    }

    private record Rest() implements Node {
    }

    private record Sequence(List<Node> steps) implements Node {
    }

    private record Layers(List<Node> layers) implements Node {
    }

    private record Repeat(Node node, int times) implements Node {
    }

    private record Euclid(Node node, int hits, int steps, int rotation) implements Node {
    }

    private static final class Parser {
        private final String text;
        private final int offset;
        private int at;

        private Parser(String text, int offset) {
            this.text = text;
            this.offset = offset;
        }

        private Node parse() {
            Node node = layers();
            skipSpaces();
            if (at < text.length()) {
                throw error("unexpected '" + text.charAt(at) + "'");
            }
            return node;
        }

        private Node layers() {
            List<Node> layers = new ArrayList<>();
            layers.add(sequence());
            while (at < text.length() && text.charAt(at) == ',') {
                at++;
                layers.add(sequence());
            }
            return layers.size() == 1 ? layers.get(0) : new Layers(layers);
        }

        private Node sequence() {
            List<Node> steps = new ArrayList<>();
            skipSpaces();
            while (at < text.length() && text.charAt(at) != ']' && text.charAt(at) != ',') {
                steps.add(step());
                skipSpaces();
            }
            if (steps.isEmpty()) {
                throw error("expected a sound, '~' or '['");
            }
            return steps.size() == 1 ? steps.get(0) : new Sequence(steps);
        }

        private Node step() {
            Node node = atom();
            while (at < text.length()) {
                char next = text.charAt(at);
                if (next == '*') {
                    at++;
                    node = new Repeat(node, number(1, "a whole number of repeats after '*'"));
                } else if (next == '(') {
                    at++;
                    int hits = number(0, "a number of hits");
                    expect(',');
                    int steps = number(1, "a number of steps");
                    int rotation = 0;
                    skipSpaces();
                    if (at < text.length() && text.charAt(at) == ',') {
                        at++;
                        rotation = number(0, "a rotation");
                    }
                    expect(')');
                    node = new Euclid(node, hits, steps, rotation);
                } else {
                    return node;
                }
            }
            return node;
        }

        private Node atom() {
            char first = text.charAt(at);
            if (first == '~') {
                at++;
                return new Rest();
            }
            if (first == '[') {
                at++;
                Node group = layers();
                expect(']');
                return group;
            }
            int start = at;
            while (at < text.length() && isWordCharacter(text.charAt(at))) {
                at++;
            }
            if (start == at) {
                throw error("unexpected '" + first + "'");
            }
            return new Word(text.substring(start, at));
        }

        private int number(int minimum, String what) {
            skipSpaces();
            int start = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (start == at) {
                throw error("expected " + what);
            }
            if (at - start > 3 || Integer.parseInt(text.substring(start, at)) > MAX_SUBDIVISION) {
                throw errorAt(start, "at most " + MAX_SUBDIVISION + " allowed here");
            }
            int value = Integer.parseInt(text.substring(start, at));
            if (value < minimum) {
                throw errorAt(start, "expected " + what);
            }
            return value;
        }

        private void expect(char expected) {
            skipSpaces();
            if (at >= text.length() || text.charAt(at) != expected) {
                throw error("expected '" + expected + "'");
            }
            at++;
        }

        private void skipSpaces() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private static boolean isWordCharacter(char character) {
            return Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_';
        }

        private LiveCodeException error(String message) {
            return errorAt(at, message);
        }

        private LiveCodeException errorAt(int position, String message) {
            return new LiveCodeException(message, offset + position);
        }
    }
}
