package io.groovebox.session;

import io.groovebox.synth.SynthSetting;
import io.groovebox.synth.LiveNovasawSynth;
import io.groovebox.synth.NovasawSynth;
import io.groovebox.synth.PitchSynth;

/**
 * What one melody track is played by: a synth of its own, and the synth panel as it was last set
 * for it, so that selecting the track puts its knobs back where they were. A ported patch becomes
 * a {@link LiveNovasawSynth}, whose knobs reach the notes already sounding; any other synth plays
 * as it is, with the panel switched off.
 *
 * <p>A track can go out to an external MIDI synth instead, on a channel of its own, with the
 * filter that synth follows as a control change.
 *
 * <p>An instrument is known by its identity, as the engine knows the synth in it: two tracks made
 * from the same patch are two instruments, with two sets of knobs and two delays.
 */
public final class Instrument {
    // the patch it was made from, kept so another of the same kind can be made from it
    private String patch;
    private final PitchSynth synth;
    private final LiveNovasawSynth live;
    private SynthSetting setting;
    // an external synth instead: whether the track goes there, and on which channel with which filter
    private boolean external;
    private int channel;
    private int controller = 74;
    private int filter = 64;

    private Instrument(String patch, PitchSynth synth, LiveNovasawSynth live, SynthSetting setting) {
        this.patch = patch;
        this.synth = synth;
        this.live = live;
        this.setting = setting;
    }

    /** A fresh instrument from the patch {@code name} names: "pluck", "anthem" and so on. */
    public static Instrument of(String name) {
        PitchSynth named = Sounds.resolveSynth(name);
        if (named instanceof NovasawSynth ported) {
            LiveNovasawSynth live = new LiveNovasawSynth(ported);
            return new Instrument(name, live, live, SynthSetting.initial(name, live.params()));
        }
        return new Instrument(name, named, null, null);
    }

    /**
     * Another instrument of the same patch, turned the same way and going out the same way, but its
     * own: two tracks made from one patch are two instruments, with two sets of knobs and two
     * delays, and a copy that shared them would not be a copy of the track but a second view of it.
     * The channel comes with it and is the caller's to move, since only the jam knows what is free.
     */
    public Instrument copy() {
        Instrument next = of(patch);
        if (setting != null) {
            next.set(setting);
        }
        next.setMidi(channel, controller, filter);
        next.setExternal(external);
        return next;
    }

    public PitchSynth synth() {
        return synth;
    }

    /** Whether the synth panel can play it: a ported patch, rather than a synth from outside them. */
    public boolean playable() {
        return live != null;
    }

    public SynthSetting setting() {
        return setting;
    }

    /** Whether the track goes out to the external synth rather than being played here. */
    public boolean external() {
        return external;
    }

    public void setExternal(boolean next) {
        external = next;
    }

    /** The MIDI channel the track goes out on, counting from 0 as the messages do. */
    public int channel() {
        return channel;
    }

    /** The control change the external synth's filter follows, CC 74 unless told otherwise. */
    public int controller() {
        return controller;
    }

    public int filter() {
        return filter;
    }

    /** Where the track goes out, and the filter it is sent there with. */
    public void setMidi(int nextChannel, int nextController, int nextFilter) {
        channel = nextChannel;
        controller = nextController;
        filter = nextFilter;
    }

    /**
     * Another of the ported patches on the same synth: its knobs as that patch has them, the
     * effects as they were. The synth stays the one the engine already plays, so the notes under
     * way take it up, and nothing has to be handed over. False when this synth is not a ported
     * one, or {@code name} does not name one, and nothing has changed.
     */
    public boolean load(String name) {
        if (live == null || !(Sounds.resolveSynth(name) instanceof NovasawSynth ported)) {
            return false;
        }
        patch = name;
        SynthSetting now = setting;
        set(new SynthSetting(name, new LiveNovasawSynth(ported).params(), now.effects(), now.delaySync()));
        return true;
    }

    /** The patch this instrument started from, or was last loaded with. */
    public String patch() {
        return patch;
    }

    /** The panel turned for this instrument: the synth hears it now, and the panel will show it again. */
    public void set(SynthSetting next) {
        if (live == null) {
            return;
        }
        setting = next;
        live.setParams(next.params());
        live.setEffectParams(next.effects());
    }
}
