package io.groovebox.livecode;

import io.groovebox.model.Drum;
import io.groovebox.model.Envelope;
import io.groovebox.model.Note;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A small live-coding language in the style of Strudel - this project's own implementation of a
 * subset, not Strudel itself:
 *
 * <pre>{@code
 * $: stack(
 *     s("bd(3,8,5)"),
 *     s("hh*16").gain("[0.2 0.1]*8"),
 *     s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
 *   )
 * }</pre>
 *
 * <p>{@code s("...")} (or {@code sound}) is a {@link MiniNotation} pattern of drum names - bd, sd,
 * hh, oh and cp. {@code stack(...)} plays patterns together, and so do several {@code $:} blocks.
 * {@code .gain} takes a number or a pattern of numbers; {@code .dec}/{@code .decay} and
 * {@code .rel}/{@code .release} take seconds. {@code //} comments out the rest of a line. One cycle
 * is one bar.
 */
public final class LiveCode {

    private static final Map<String, Drum> DRUMS = Arrays.stream(Drum.values())
            .collect(Collectors.toUnmodifiableMap(LiveCode::soundName, drum -> drum));

    public static String soundName(Drum drum) {
        return switch (drum) {
            case KICK -> "bd";
            case SNARE -> "sd";
            case CLOSED_HAT -> "hh";
            case OPEN_HAT -> "oh";
            case CLAP -> "cp";
        };
    }

    /**
     * One hit in one cycle: which layer of the code it comes from, the drum, where in the cycle it
     * starts and how long its step lasts (both 0 to 1), how loud it is and how it is shaped.
     */
    public record Hit(int layer, Drum drum, double start, double duration, float velocity, Envelope envelope) {
    }

    private final List<Layer> layers;

    private LiveCode(List<Layer> layers) {
        this.layers = List.copyOf(layers);
    }

    public static LiveCode parse(String source) {
        return new Parser(source).program();
    }

    /** Every audible hit of one cycle, layer by layer. Gain above 1 is capped at 1, as loud as a {@link Note} goes. */
    public List<Hit> hits() {
        List<Hit> hits = new ArrayList<>();
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            for (MiniNotation.Event event : layer.sound().events()) {
                float velocity = (float) Math.max(0.0, Math.min(1.0, layer.gainAt(event.start())));
                if (velocity > 0.0f) {
                    hits.add(new Hit(index, DRUMS.get(event.value()), event.start(), event.duration(),
                            velocity, layer.envelope()));
                }
            }
        }
        return List.copyOf(hits);
    }

    /**
     * Every hit as a note over {@code lengthBeats}, one cycle every {@code beatsPerCycle} beats, cut
     * off where the length ends - a loop shorter than a cycle plays only the cycle's start.
     */
    public List<Note> notes(double beatsPerCycle, double lengthBeats) {
        List<Hit> cycle = hits();
        List<Note> notes = new ArrayList<>();
        int cycles = (int) Math.ceil(lengthBeats / beatsPerCycle);
        for (int index = 0; index < cycles; index++) {
            for (Hit hit : cycle) {
                double beat = (index + hit.start()) * beatsPerCycle;
                if (beat < lengthBeats) {
                    notes.add(new Note(beat, hit.drum(), hit.duration() * beatsPerCycle, hit.velocity(), hit.envelope()));
                }
            }
        }
        notes.sort(Comparator.comparingDouble(Note::beat));
        return List.copyOf(notes);
    }

    private record Layer(MiniNotation sound, double gain, MiniNotation gainPattern, Envelope envelope) {

        Layer(MiniNotation sound) {
            this(sound, 1.0, null, Envelope.NONE);
        }

        double gainAt(double position) {
            if (gainPattern == null) {
                return gain;
            }
            String value = gainPattern.valueAt(position);
            return value == null ? 0.0 : Double.parseDouble(value);
        }

        Layer withGain(double newGain, MiniNotation newGainPattern) {
            return new Layer(sound, newGain, newGainPattern, envelope);
        }

        Layer with(Envelope newEnvelope) {
            return new Layer(sound, gain, gainPattern, newEnvelope);
        }
    }

    private static final class Parser {
        private final String source;
        private int at;

        private Parser(String source) {
            this.source = source;
        }

        private LiveCode program() {
            List<Layer> layers = new ArrayList<>();
            skip();
            if (at >= source.length()) {
                throw error("nothing to play");
            }
            if (source.startsWith("$:", at)) {
                while (source.startsWith("$:", at)) {
                    at += 2;
                    layers.addAll(expression());
                    skip();
                }
            } else {
                layers.addAll(expression());
                skip();
            }
            if (at < source.length()) {
                throw error("unexpected '" + source.charAt(at) + "'");
            }
            return new LiveCode(layers);
        }

        private List<Layer> expression() {
            skip();
            int nameAt = at;
            String name = identifier();
            List<Layer> layers = switch (name) {
                case "stack" -> stack();
                case "s", "sound" -> List.of(new Layer(sound()));
                default -> throw errorAt(nameAt, "unknown function '" + name + "', expected s(...) or stack(...)");
            };
            skip();
            while (at < source.length() && source.charAt(at) == '.') {
                at++;
                skip();
                int methodAt = at;
                String method = identifier();
                expect('(');
                layers = switch (method) {
                    case "gain" -> gain(layers);
                    case "dec", "decay" -> {
                        double seconds = seconds();
                        yield map(layers, layer -> layer.with(new Envelope(seconds, layer.envelope().releaseSeconds())));
                    }
                    case "rel", "release" -> {
                        double seconds = seconds();
                        yield map(layers, layer -> layer.with(new Envelope(layer.envelope().decaySeconds(), seconds)));
                    }
                    default -> throw errorAt(methodAt, "unknown method '." + method + "', expected .gain, .dec or .rel");
                };
                expect(')');
                skip();
            }
            return layers;
        }

        private List<Layer> stack() {
            expect('(');
            List<Layer> layers = new ArrayList<>();
            skip();
            while (at < source.length() && source.charAt(at) != ')') {
                layers.addAll(expression());
                skip();
                if (at < source.length() && source.charAt(at) == ',') {
                    at++;
                    skip();
                } else if (at >= source.length() || source.charAt(at) != ')') {
                    throw error("expected ',' or ')'");
                }
            }
            expect(')');
            return layers;
        }

        private MiniNotation sound() {
            expect('(');
            skip();
            int textAt = at + 1;
            MiniNotation pattern = MiniNotation.parse(string(), textAt);
            for (MiniNotation.Event event : pattern.events()) {
                if (!DRUMS.containsKey(event.value())) {
                    throw errorAt(textAt, "unknown sound '" + event.value() + "', expected one of "
                            + new TreeSet<>(DRUMS.keySet()));
                }
            }
            expect(')');
            return pattern;
        }

        private List<Layer> gain(List<Layer> layers) {
            skip();
            if (at < source.length() && (source.charAt(at) == '"' || source.charAt(at) == '\'')) {
                int textAt = at + 1;
                MiniNotation pattern = MiniNotation.parse(string(), textAt);
                for (MiniNotation.Event event : pattern.events()) {
                    if (!isNumber(event.value())) {
                        throw errorAt(textAt, "a gain pattern needs numbers, got '" + event.value() + "'");
                    }
                }
                return map(layers, layer -> layer.withGain(1.0, pattern));
            }
            double value = number();
            return map(layers, layer -> layer.withGain(value, null));
        }

        private double seconds() {
            int start = at;
            double seconds = number();
            if (seconds < 0.0) {
                throw errorAt(start, "expected zero or more seconds");
            }
            return seconds;
        }

        private double number() {
            skip();
            int start = at;
            if (at < source.length() && source.charAt(at) == '-') {
                at++;
            }
            while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
                at++;
            }
            try {
                return Double.parseDouble(source.substring(start, at));
            } catch (NumberFormatException exception) {
                throw errorAt(start, "expected a number");
            }
        }

        private String string() {
            skip();
            if (at >= source.length() || (source.charAt(at) != '"' && source.charAt(at) != '\'')) {
                throw error("expected a quoted pattern");
            }
            char quote = source.charAt(at);
            int start = ++at;
            while (at < source.length() && source.charAt(at) != quote) {
                at++;
            }
            if (at >= source.length()) {
                throw errorAt(start - 1, "unclosed string");
            }
            return source.substring(start, at++);
        }

        private String identifier() {
            int start = at;
            while (at < source.length() && (Character.isLetterOrDigit(source.charAt(at)) || source.charAt(at) == '_')) {
                at++;
            }
            if (start == at || Character.isDigit(source.charAt(start))) {
                throw errorAt(start, "expected a function name");
            }
            return source.substring(start, at);
        }

        private void expect(char expected) {
            skip();
            if (at >= source.length() || source.charAt(at) != expected) {
                throw error("expected '" + expected + "'");
            }
            at++;
        }

        private void skip() {
            while (at < source.length()) {
                if (Character.isWhitespace(source.charAt(at))) {
                    at++;
                } else if (source.startsWith("//", at)) {
                    while (at < source.length() && source.charAt(at) != '\n') {
                        at++;
                    }
                } else {
                    return;
                }
            }
        }

        private static boolean isNumber(String text) {
            try {
                Double.parseDouble(text);
                return true;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        private static List<Layer> map(List<Layer> layers, UnaryOperator<Layer> change) {
            return layers.stream().map(change).toList();
        }

        private LiveCodeException error(String message) {
            return errorAt(at, message);
        }

        private LiveCodeException errorAt(int position, String message) {
            return new LiveCodeException(message, position);
        }
    }
}
