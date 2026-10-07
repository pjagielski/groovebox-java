# groovebox-java

A groovebox in plain Java: sixteen pads, eight knobs and a screen, with every sound synthesized
in code. JavaFX for the box, Java Sound for the output, no native audio libraries.

Work in progress.

## Running

Needs JDK 25 (Gradle fetches one if it is missing).

```
./gradlew run
```

The box starts on a demo jam written in code: Am9 Fmaj9 Dm9 E7♭9 under a plucked lead - a
syncopated line over a ticking pedal, asked and answered over eight bars - echoing on the dotted
eighth, a pad pumping under the kick, a sub bass and a disco
beat. Press space to play.

A jam can also start from a config naming a MIDI file for its melody:

```
./gradlew run --args="--config path/to/jam.properties"
```

```
file=song.mid
track=1
bars=2
synth=pluck
drums=disco
```

## License

MIT
