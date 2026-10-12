package io.groovebox.audio;

import io.groovebox.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MixerAudioTest {
    @Test
    void masterChangesTheMixAtOnceWithoutChangingTrackGains() {
        float[] samples = new float[1000]; Arrays.fill(samples, 1);
        var engine = new AudioEngine(SampleBank.from(Map.of(Drum.KICK, Sample.mono(samples))), 1000, 128, 8);
        var song = new Song(120, 4, List.of(new DrumTrack(Drum.KICK, "X...", 0.5f)));
        var jam = new AtomicReference<>(new AudioEngine.Jam(song, List.of((pitch, frames, rate) -> Sample.mono(0)), List.of(-1), 1));
        var renderer = engine.liveRenderer(jam::get, false);
        float[] mix = new float[256]; renderer.renderNext(mix);
        assertEquals(0.5f, mix[0]);
        jam.set(new AudioEngine.Jam(song, jam.get().synths(), List.of(-1), 0.25f));
        renderer.renderNext(mix); renderer.renderNext(mix);
        assertEquals(0.125f, mix[0]);
        assertEquals(0.5f, renderer.levels().track(0));
        assertEquals(0.125f, renderer.levels().master());
        assertEquals(0.5f, song.tracks().getFirst().gain());
        jam.set(new AudioEngine.Jam(song, jam.get().synths(), List.of(-1), 0));
        renderer.renderNext(mix); renderer.renderNext(mix);
        assertEquals(0, renderer.levels().master());
        assertEquals(0, mix[0]);
    }

    @Test
    void metersSumOverlappingVoicesBeforeMeasuringTheirStereoPeak() {
        var meters = new LevelMeters(4);
        meters.begin(2);
        meters.add(0, 0, 0.2f, -0.3f);
        meters.add(0, 0, 0.4f, -0.5f);
        meters.add(1, 1, -0.7f, 0.1f);
        meters.add(-1, 0, 1, 1);
        var levels = meters.finish(new float[]{0.6f, -0.8f, -0.7f, 0.1f, 0, 0, 0, 0});
        assertEquals(0.8f, levels.track(0));
        assertEquals(0.7f, levels.track(1));
        assertEquals(0.8f, levels.master());
        meters.begin(2);
        assertEquals(0, meters.finish(new float[8]).track(0));
        assertEquals(0.8f, levels.track(0));
    }
}
