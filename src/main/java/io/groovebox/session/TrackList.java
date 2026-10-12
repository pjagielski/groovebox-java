package io.groovebox.session;

import io.groovebox.model.LoopTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * The studio's tracks in the order the panel shows them, and which of them is selected — the one
 * the editor below the list is showing. The drum track is always there: it can be moved, muted
 * and renamed, but not removed.
 */
public final class TrackList {

    /** The notes of a melody's source over a loop of {@code lengthBeats}. */
    @FunctionalInterface
    public interface Windows {
        List<Note> notes(MelodySource source, double lengthBeats);
    }

    private final List<StudioTrack> tracks = new ArrayList<>();
    private int selected;

    private TrackList() {
    }

    /** The permanent drum track, selected, with no melodies or audio loops yet. */
    public static TrackList drumsOnly() {
        TrackList list = new TrackList();
        list.tracks.add(new StudioTrack.Drums("Drums", 1.0f, false));
        return list;
    }

    /** What a jam config makes: the grid, then the one melody it names — with the grid selected. */
    public static TrackList startingWith(String melodyName, MelodySource melody, Instrument instrument) {
        TrackList list = drumsOnly();
        list.tracks.add(new StudioTrack.Melody(melodyName, 1.0f, false, melody, instrument));
        return list;
    }

    public static TrackList startingWith(String melodyName, MelodySource melody) {
        return startingWith(melodyName, melody, null);
    }

    public List<StudioTrack> tracks() {
        return List.copyOf(tracks);
    }

    /** Puts a whole list back, as an undo does; the tracks are the ones that were kept. */
    public void restore(List<StudioTrack> kept, int select) {
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("A jam has tracks");
        }
        tracks.clear();
        tracks.addAll(kept);
        selected = Math.max(0, Math.min(tracks.size() - 1, select));
    }

    public int size() {
        return tracks.size();
    }

    public StudioTrack get(int index) {
        return tracks.get(index);
    }

    public int selectedIndex() {
        return selected;
    }

    public StudioTrack selected() {
        return tracks.get(selected);
    }

    public void select(int index) {
        checkIndex(index);
        selected = index;
    }

    /** The first melody track, which is the one a jam config describes, if any is left. */
    public StudioTrack.Melody firstMelody() {
        for (StudioTrack track : tracks) {
            if (track instanceof StudioTrack.Melody melody) {
                return melody;
            }
        }
        return null;
    }

    /** Adds a track at the end and selects it. */
    public void add(StudioTrack track) {
        tracks.add(track);
        selected = tracks.size() - 1;
    }

    /** Puts a track in just below {@code index} and selects it: what duplicating one does. */
    public void insertAfter(int index, StudioTrack track) {
        checkIndex(index);
        tracks.add(index + 1, track);
        selected = index + 1;
    }

    /**
     * Whether a track can be copied. The same ones that can be taken out can be: the drum track
     * plays the grid, and there is one grid, so two of them could never come apart.
     */
    public boolean canDuplicate(int index) {
        return canRemove(index);
    }

    public boolean canRemove(int index) {
        return index >= 0 && index < tracks.size() && !(tracks.get(index) instanceof StudioTrack.Drums);
    }

    /** Takes a melody track out; the selection moves to the one that took its place, or the one above. */
    public void remove(int index) {
        if (!canRemove(index)) {
            throw new IllegalArgumentException("Track " + index + " cannot be removed");
        }
        tracks.remove(index);
        if (selected > index || selected == tracks.size()) {
            selected--;
        }
    }

    public boolean canMove(int index, int by) {
        int target = index + by;
        return index >= 0 && index < tracks.size() && target >= 0 && target < tracks.size();
    }

    /** Moves a track up (negative) or down (positive) the list; a selected track stays selected. */
    public void move(int index, int by) {
        if (!canMove(index, by)) {
            throw new IllegalArgumentException("Track " + index + " cannot move by " + by);
        }
        StudioTrack track = tracks.remove(index);
        tracks.add(index + by, track);
        if (selected == index) {
            selected = index + by;
        } else if (index < selected && selected <= index + by) {
            selected--;
        } else if (index + by <= selected && selected < index) {
            selected++;
        }
    }

    public void replace(int index, StudioTrack track) {
        checkIndex(index);
        if (track.getClass() != tracks.get(index).getClass()) {
            throw new IllegalArgumentException("A track keeps its kind");
        }
        tracks.set(index, track);
    }

    /**
     * The jam as the engine plays it: a track for each of the panel's, in its order, every one of
     * them as long as the loop. A melody track plays its window. The drum track plays the grid,
     * a track to a row, so that each row is heard through its own channel and at its own level
     * times the drum track's; as one track when the grid has no rows at all.
     */
    public Song song(double bpm, int beatsPerBar, double lengthBeats, List<GridRow> rows, Windows windows) {
        List<Track> songTracks = new ArrayList<>();
        // solo says something about the other tracks, so it is decided here, where they are all known
        boolean anySoloed = tracks.stream().anyMatch(StudioTrack::soloed);
        for (StudioTrack track : tracks) {
            if (track instanceof StudioTrack.Drums drums) {
                float gain = drums.audibleGain(anySoloed);
                if (rows.isEmpty()) {
                    songTracks.add(new MelodyTrack(List.of(), lengthBeats, gain));
                }
                for (GridRow row : rows) {
                    // the row's level as its track's gain, which the engine follows block by block
                    songTracks.add(new MelodyTrack(row.notes(beatsPerBar, lengthBeats), lengthBeats,
                            gain * Math.max(0.0f, Math.min(1.0f, row.gain())), row.channel()));
                }
                continue;
            }
            songTracks.add(switch (track) {
                case StudioTrack.Drums drums -> throw new AssertionError("played above");
                case StudioTrack.Melody melody -> new MelodyTrack(windows.notes(melody.source(), lengthBeats),
                        lengthBeats, melody.audibleGain(anySoloed));
                // audio rather than notes: how long the jam's loop is does not stretch or cut it
                case StudioTrack.Loop loop -> new LoopTrack(loop.audio(), loop.bars(),
                        loop.audibleGain(anySoloed),
                        loop.slicing().steps(loop.audio().frameCount()),
                        // the same channel every time the jam is published, so its tail carries over
                        loop.channel());
            });
        }
        return new Song(bpm, beatsPerBar, songTracks);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= tracks.size()) {
            throw new IndexOutOfBoundsException("No track " + index + " of " + tracks.size());
        }
    }
}
