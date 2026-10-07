package io.groovebox.session;

import io.groovebox.audio.Sample;
import io.groovebox.model.Drum;
import io.groovebox.model.Envelope;
import io.groovebox.model.LoopTrack;
import io.groovebox.model.MelodyTrack;
import io.groovebox.model.Note;
import io.groovebox.model.Song;
import io.groovebox.model.Voice;
import io.groovebox.synth.EffectParams;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackListTest {

    private static final MidiWindow LEAD = new MidiWindow(Path.of("lead.mid"), 1, 4);
    private static final MidiWindow BASS = new MidiWindow(Path.of("bass.mid"), 2, 0);
    private static final GridRow KICK = new GridRow("bd", Drum.KICK, new float[] {1, 0, 0, 0}, 1.0f, 0.25,
            Envelope.NONE);
    private static final List<GridRow> DRUMS = List.of(KICK);

    /** Every source's notes: one note, pitched by the MIDI track it came from, so they can be told apart. */
    private static final TrackList.Windows WINDOWS = (source, lengthBeats) ->
            List.of(new Note(0, new Voice.Pitch(60 + ((MidiWindow) source).trackIndex()), lengthBeats, 0.9f));

    @Test
    void aJamConfigMakesTheSameSongAsTheStudioAlwaysDid() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(new Song(128, 4, List.of(
                new MelodyTrack(KICK.notes(4, 32), 32, 1.0f, KICK.channel()),
                new MelodyTrack(WINDOWS.notes(LEAD, 32), 32, 1.0f))), song);
        assertInstanceOf(StudioTrack.Drums.class, tracks.selected());
    }

    @Test
    void eachRowOfTheGridIsATrackOfItsOwnThroughItsOwnChannelAtTheDrumsLevel() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.replace(0, tracks.get(0).withGain(0.7f));
        GridRow snare = new GridRow("sd", Drum.SNARE, new float[] {0, 0, 1, 0}, 1.0f, 0.25, Envelope.NONE);

        Song song = tracks.song(120, 4, 4, List.of(KICK, snare), WINDOWS);

        assertEquals(3, song.tracks().size(), "two rows, then the lead");
        assertEquals(new MelodyTrack(KICK.notes(4, 4), 4, 0.7f, KICK.channel()), song.tracks().get(0));
        assertEquals(new MelodyTrack(snare.notes(4, 4), 4, 0.7f, snare.channel()), song.tracks().get(1));
        assertEquals(new MelodyTrack(WINDOWS.notes(LEAD, 4), 4, 1.0f), song.tracks().get(2));
    }

    @Test
    void aRowsLevelIsItsTracksGainTimesTheDrums() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.replace(0, tracks.get(0).withGain(0.8f));

        Song song = tracks.song(120, 4, 4, List.of(KICK.withGain(0.5f)), WINDOWS);

        assertEquals(0.4f, song.tracks().get(0).gain(), 1e-6f);
        assertEquals(KICK.notes(4, 4), ((MelodyTrack) song.tracks().get(0)).notes(), "and none of it in the notes");
    }

    @Test
    void aGridWithNoRowsIsStillATrackSoTheOthersKeepTheirPlaces() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        Song song = tracks.song(120, 4, 4, List.of(), WINDOWS);

        assertEquals(new MelodyTrack(List.of(), 4, 1.0f), song.tracks().get(0));
        assertEquals(2, song.tracks().size());
    }

    @Test
    void aLoopTrackIsHandedOverAsAudioOverItsOwnBars() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        Sample audio = Sample.mono(new float[1000]);
        tracks.add(new StudioTrack.Loop("Break", 0.8f, false, Path.of("100_16_break.wav"), 4, audio));

        // the jam loops over eight beats; the loop keeps its own four bars rather than being cut to them
        Song song = tracks.song(128, 4, 8, DRUMS, WINDOWS);

        // not the record whole: its channel is the track's own object, which no expected value has
        LoopTrack handed = (LoopTrack) song.tracks().get(2);
        assertEquals(new LoopTrack(audio, 4, 0.8f), new LoopTrack(handed.audio(), handed.bars(), handed.gain(),
                handed.steps()));
    }

    @Test
    void aMutedLoopIsHandedOverSilentLikeAnyOtherTrack() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Loop("Break", 0.8f, true, Path.of("break.wav"), 2, Sample.mono(new float[10])));

        Song song = tracks.song(128, 4, 8, DRUMS, WINDOWS);

        assertEquals(0.0f, ((LoopTrack) song.tracks().get(2)).gain());
    }

    @Test
    void aLoopKeepsTheOneChannelThroughEverythingDoneToIt() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        var loop = new StudioTrack.Loop("Break", 0.8f, false, Path.of("break.wav"), 2,
                Sample.mono(new float[10]));
        tracks.add(loop);
        loop.channel().setParams(EffectParams.DEFAULT.withDelayMix(0.4f));

        // faded, muted, soloed, renamed, cut up, published: the channel must come through all of it,
        // because the engine keeps one bus per channel and a new one each time would be a new bus
        StudioTrack.Loop worked = loop.withGain(0.5f).withMuted(true).withSoloed(true).named("Break 2");
        tracks.replace(2, worked);
        Song song = tracks.song(128, 4, 8, DRUMS, WINDOWS);

        assertSame(loop.channel(), worked.channel(), "every with- method carries the one channel");
        assertSame(loop.channel(), ((LoopTrack) song.tracks().get(2)).channel(),
                "and the engine is handed that same one");
        assertEquals(0.4f, loop.channel().params().delayMix(), 1e-6f,
                "what was set on it is still there, without the jam being rebuilt for it");
    }

    @Test
    void aCopyGoesInJustBelowTheOneItCameFromAndIsSelected() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 1.0f, false, BASS));
        tracks.select(1);

        tracks.insertAfter(1, new StudioTrack.Melody("Lead 2", 1.0f, false, LEAD));

        assertEquals(List.of("Drums", "Lead", "Lead 2", "Bass"),
                tracks.tracks().stream().map(StudioTrack::name).toList());
        assertEquals(2, tracks.selectedIndex(), "the hand lands on the copy");
    }

    @Test
    void theDrumTrackCannotBeCopiedAnyMoreThanItCanBeTakenOut() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        assertFalse(tracks.canDuplicate(0), "the grid is the jam's, and there is one grid");
        assertTrue(tracks.canDuplicate(1));
    }

    @Test
    void soloingOneTrackSilencesTheRest() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 0.8f, false, BASS));
        tracks.replace(2, tracks.get(2).withSoloed(true));

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(0.0f, ((MelodyTrack) song.tracks().get(0)).gain(), "the drums go");
        assertEquals(0.0f, ((MelodyTrack) song.tracks().get(1)).gain(), "and the lead");
        assertEquals(0.8f, ((MelodyTrack) song.tracks().get(2)).gain(), "and the soloed one keeps its own");
    }

    @Test
    void withNothingSoloedEveryTrackIsHeard() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(1.0f, ((MelodyTrack) song.tracks().get(0)).gain());
        assertEquals(1.0f, ((MelodyTrack) song.tracks().get(1)).gain());
    }

    @Test
    void muteStillWinsOverSolo() {
        // a hand that muted something meant it, whatever it does with solo afterwards
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.replace(1, tracks.get(1).withMuted(true).withSoloed(true));

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(0.0f, ((MelodyTrack) song.tracks().get(1)).gain());
    }

    @Test
    void severalTracksCanBeSoloedAtOnce() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 0.6f, false, BASS));
        tracks.replace(1, tracks.get(1).withSoloed(true));
        tracks.replace(2, tracks.get(2).withSoloed(true));

        Song song = tracks.song(128, 4, 32, DRUMS, WINDOWS);

        assertEquals(0.0f, ((MelodyTrack) song.tracks().get(0)).gain(), "the drums still go");
        assertEquals(1.0f, ((MelodyTrack) song.tracks().get(1)).gain());
        assertEquals(0.6f, ((MelodyTrack) song.tracks().get(2)).gain());
    }

    @Test
    void aMutedTrackIsHandedOverSilent() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.replace(1, tracks.get(1).withGain(0.6f).withMuted(true));
        tracks.replace(0, tracks.get(0).withGain(0.8f));

        Song song = tracks.song(120, 4, 16, DRUMS, WINDOWS);

        assertEquals(0.8f, song.tracks().get(0).gain());
        assertEquals(0.0f, song.tracks().get(1).gain());
        tracks.replace(1, tracks.get(1).withMuted(false));
        assertEquals(0.6f, tracks.song(120, 4, 16, DRUMS, WINDOWS).tracks().get(1).gain());
    }

    @Test
    void everyMelodyTrackPlaysItsOwnWindowForTheLengthOfTheLoop() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 0.7f, false, BASS));

        Song song = tracks.song(120, 4, 8, DRUMS, WINDOWS);

        assertEquals(3, song.tracks().size());
        assertEquals(new MelodyTrack(WINDOWS.notes(BASS, 8), 8, 0.7f), song.tracks().get(2));
        assertEquals(2, tracks.selectedIndex(), "a new track is the one selected");
    }

    @Test
    void theDrumTrackCannotBeRemoved() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        assertFalse(tracks.canRemove(0));
        assertThrows(IllegalArgumentException.class, () -> tracks.remove(0));
        assertTrue(tracks.canRemove(1));
        tracks.remove(1);
        assertEquals(1, tracks.size());
        assertEquals(null, tracks.firstMelody());
        assertEquals(1, tracks.song(120, 4, 4, DRUMS, WINDOWS).tracks().size());
    }

    @Test
    void removingATrackSelectsTheOneThatTookItsPlace() {
        TrackList tracks = threeMelodies();
        tracks.select(2);

        tracks.remove(2);
        assertEquals("Pad", tracks.selected().name());

        tracks.remove(tracks.size() - 1);
        assertEquals("Lead", tracks.selected().name(), "or the one above, at the end of the list");
    }

    @Test
    void removingATrackAboveKeepsTheSameOneSelected() {
        TrackList tracks = threeMelodies();
        tracks.select(3);

        tracks.remove(1);

        assertEquals("Pad", tracks.selected().name());
    }

    @Test
    void movingKeepsTheSelectionOnTheSameTrack() {
        TrackList tracks = threeMelodies();
        tracks.select(2);

        tracks.move(2, -2);
        assertEquals(List.of("Bass", "Drums", "Lead", "Pad"), names(tracks));
        assertEquals("Bass", tracks.selected().name());

        tracks.move(3, -1);
        assertEquals(List.of("Bass", "Drums", "Pad", "Lead"), names(tracks));
        assertEquals("Bass", tracks.selected().name(), "another track moving past it");

        tracks.select(2);
        tracks.move(0, 3);
        assertEquals(List.of("Drums", "Pad", "Lead", "Bass"), names(tracks));
        assertEquals("Pad", tracks.selected().name());
        assertFalse(tracks.canMove(3, 1));
        assertFalse(tracks.canMove(0, -1));
    }

    @Test
    void aTrackKeepsItsKind() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);

        assertThrows(IllegalArgumentException.class,
                () -> tracks.replace(0, new StudioTrack.Melody("Drums", 1, false, LEAD)));
    }

    private static TrackList threeMelodies() {
        TrackList tracks = TrackList.startingWith("Lead", LEAD);
        tracks.add(new StudioTrack.Melody("Bass", 1, false, BASS));
        tracks.add(new StudioTrack.Melody("Pad", 1, false, LEAD));
        return tracks;
    }

    private static List<String> names(TrackList tracks) {
        return tracks.tracks().stream().map(StudioTrack::name).toList();
    }
}
