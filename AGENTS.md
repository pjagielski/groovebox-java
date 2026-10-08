# groovebox-java

A groovebox in plain Java 25: JavaFX for the box, Java Sound for the output, every sound
synthesized in code. No samples and no MIDI files are shipped.

## Build, test, look

- `./gradlew test` runs everything. The tests render audio offline and open no audio device.
- To count results, read the XML in `build/test-results/test/`. Delete that directory before the
  run, because an up-to-date task leaves the old files there.
- `./gradlew run` opens the box on `DemoJam`. With `--args="--config <jam.properties>"` it opens
  a jam whose melody comes from a MIDI file instead.
- To see the box start without sound, run `./gradlew installDist`, then start
  `build/install/groovebox-java/bin/groovebox-java` under a timeout. It stays silent until Play
  or Space is pressed. A click on the screen or on a pad auditions a sound.

## Layout

Packages under `io.groovebox`, from the bottom up:

- `model` holds records: notes, tracks, the song.
- `audio` holds the engine, the live renderer and samples.
- `synth` holds the novasaw synth, the effects and their params.
- `livecode` turns mini-notation code into a drum grid.
- `midi` reads MIDI files and drives an external MIDI output.
- `session` is the jam with no UI: `Session`, `Playback`, the tracks, `DemoJam`, `JamConfig`
  and `Sounds`.
- `controls` holds the JavaFX knobs and the theme.
- `app` is the box itself.

JavaFX belongs to `controls` and `app`. Everything from `session` down is plain Java, so it can
be tested without a window.

## Conventions

- Comments are prose that says why, in the voice of the code around them. Each public type gets a
  Javadoc paragraph.
- A fix comes with a test that has been seen red: take the fix out, watch the test fail, put the
  fix back.
- Sound has to change the moment a knob turns. The audio thread reads params as whole records
  through a volatile field, so a change publishes a new record. Build it with the `with…`
  methods: they keep derived fields such as the unison gain and the resonance compensation in
  step with the field that changed.
- `SynthParams` keeps what the knobs were set to. What actually sounds is decided where the
  sound is made: `oscillators()`, for instance, gives a sine one oscillator whatever the unison
  knob says.
