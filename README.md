# Launchpad Step Sequencer

A Bitwig Studio controller extension that turns a Novation Launchpad Mini [MK3] into a
step sequencer for editing note-grid clips.

## Grid layout

Rows, top to bottom:

1. **Clip launch** - 8 clip slots for the currently selected (cursor) track. Press an empty
   pad to create and select a new 1-bar clip; press a pad with a clip to select and launch it.
2. **Step toggle A** - steps 1-8
3. **Step toggle B** - steps 9-16
4. **Piano, black keys** - one octave
5. **Piano, white keys** - one octave
6-8. Reserved (currently unlit) for future step-operator / note-expression editing.

Hold a piano pad and press a step pad (in either order) to toggle a note at that pitch on
that step. Pressing a step pad with no piano pad held clears the step if it has any notes,
or adds one at the last-used pitch if it's empty. The top-row `Up`/`Down` buttons shift the
piano octave.

## Build

Requires JDK 21+ and Maven (the Bitwig extension API is fetched from
`https://maven.bitwig.com`, or resolved from your local `~/.m2` cache if already present):

```
mvn package
```

This produces `target/LaunchpadStepSequencer.bwextension`.

## Install

Copy the built file into Bitwig's extensions folder:

- macOS: `~/Documents/Bitwig Studio/Extensions/`
- Windows: `Documents\Bitwig Studio\Extensions\`
- Linux: `~/Bitwig Studio/Extensions/`

Then in Bitwig Studio, open Settings > Controllers, add a new controller, and choose
"Novation" / "Launchpad Mini MK3" (author: Peter Nyboer) from the list, selecting the
Launchpad's DAW In/Out MIDI ports.

## Status

Core sequencing (add/delete/assign notes per step) is implemented. Step data is read and
written through Bitwig's `NoteStep` API, which also exposes per-note velocity, timbre,
pressure, chance, occurrence, recurrence and repeat ("operators") - these aren't wired up
to the controller yet but the groundwork is in place to add them.
