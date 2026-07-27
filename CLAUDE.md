# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Bitwig Studio controller extension (Java, built against `com.bitwig:extension-api`) that turns a
Novation Launchpad Mini [MK3] into a hardware step sequencer for editing note-grid clips directly -
add/remove/edit notes on the device without touching the mouse. It is a from-scratch extension, not
a fork of Bitwig's own bundled Launchpad scripts.

## Build

```
mvn package
```

Produces `target/LaunchpadStepSequencer.bwextension` (a renamed jar; see the
`copy-rename-maven-plugin` step in `pom.xml`). Requires JDK 21+. `com.bitwig:extension-api:25` is
resolved from `https://maven.bitwig.com` (or the local `~/.m2` cache if already present). There is
no test suite, linter, or CI in this repo - build success plus manual verification in Bitwig is the
whole feedback loop.

## Install / manual test loop

```
cp target/LaunchpadStepSequencer.bwextension "$HOME/Documents/Bitwig Studio/Extensions/"
```

Then in Bitwig: Settings > Controllers > remove and re-add "Launchpad Mini MK3" (or restart Bitwig)
to pick up a new build. There's no way to test this outside Bitwig with real hardware attached - the
build-install-reload-poke-the-hardware loop is the actual development cycle.

If it fails to load or misbehaves, check `~/Library/Logs/Bitwig/BitwigStudio.log` - Bitwig logs
extension stack traces there (search for the extension's class package,
`com.buchla.launchpadseq`). This is the fastest way to see what actually broke; don't guess from the
Bitwig UI alone.

## Architecture

Three files under `src/main/java/com/buchla/launchpadseq/`:

- **`LaunchpadSeqExtensionDefinition`** - `ControllerExtensionDefinition` boilerplate: extension
  metadata, MIDI port auto-detection names, and instantiates `LaunchpadSeqExtension`. Registered via
  `src/main/resources/META-INF/services/com.bitwig.extension.ExtensionDefinition` (a one-line
  ServiceLoader file naming the definition class - Bitwig discovers extensions this way, not via any
  manifest).
- **`LpProtocol`** - pure hardware/protocol constants and SysEx byte-builders for the Launchpad Mini
  MK3 (grid note numbering, Modifier Column CC numbering, DAW-mode/layout SysEx). No Bitwig API
  usage, no state.
- **`ColorLookup`** - named palette-index constants and an RGB-to-nearest-palette-index matcher
  (ported from Bitwig's own bundled Launchpad script's colour-matching algorithm, since Bitwig
  doesn't expose it as a library call).
- **`LaunchpadSeqExtension`** - everything else: the `ControllerExtension` lifecycle (`init`/
  `flush`/`exit`), the grid-to-clip mapping, all input handling, and all LED rendering. This is a
  single class by design (state is small enough that splitting it up would mostly add indirection);
  it's organized top-to-bottom as constants, fields, lifecycle methods, `flush()` and colour
  helpers, then input handlers, roughly grid-row by grid-row. Read the class doc comment at the top
  first - it's the source of truth for current grid layout and interaction rules, kept in sync with
  the code as features are added.

## Non-obvious things worth knowing before changing anything

- **The device is driven in DAW mode + Session layout, not Programmer mode.** Programmer mode is
  the obvious/documented way to fully own the grid, but its LED SysEx (arbitrary RGB) only works in
  Programmer mode or a Lighting Custom Mode - it silently does nothing in Session/DAW mode. Session
  mode uses the *same* grid note/CC numbering as Programmer mode, but LEDs are set with plain
  Note-On messages (channel 1, velocity = a palette index 0-127, not RGB) - see `ColorLookup`. This
  is also why Bitwig's own bundled Launchpad scripts use DAW mode: it's the documented preferred
  approach and leaves the device's other MIDI port free. Don't reach for the RGB SysEx path again
  without switching layouts.
- **Grid pad note numbers**: `11 + col + 10*row`, row 0 = bottom, col 0 = left (`LpProtocol.gridNote`).
  Top control row (Up/Down/Left/Right/Session/Drums/Keys/User) is CC 91-98; only Up (91) and Down
  (92) are usable (the other four are reserved by the device for its own layout switching and don't
  reliably light or stay controllable). The "Modifier Column" - a 9th column past the 8x8 grid - is
  CC `19 + 10*row`, one button per row, plus CC99 as a corner button (unused so far).
  `LpProtocol.modifierColumnCC` is the formula; don't hardcode these numbers elsewhere.
- **`ControllerHost.createCursorTrack`**: the extension calls the 5-arg overload
  (`id, name, numSends, numScenes, shouldFollowSelection`), not the deprecated 3-arg one - Bitwig
  throws on any deprecated API call by default, which kills `init()`. `numScenes` also isn't
  optional in practice: passing 0 makes `clipLauncherSlotBank()` return null (already hit this once).
- **Grid layout is currently**: row 7 (top) = clip launcher slots; rows 6/5 = the 16 step toggles;
  row 4/3 = a one-octave piano (black/white keys, columns 0 and 7 of row 4 repurposed as octave
  shift); rows 2/1/0 = modifier rows whose function depends on the Modifier Column mode (Note Ops:
  recurrence/chance/length; Note Expressions: timbre/pressure/velocity; a third mode is reserved and
  currently does nothing). Check the class doc comment for anything more specific than this -
  row/mode assignments are exactly the kind of thing that drifts from a summary like this one.
- **Step "hold" is a tap/hold state machine, not a plain toggle-on-press.** A step pad's on/off
  toggle fires on *release*, and only if the release was quick (under `TAP_THRESHOLD_MS`) *and*
  nothing else happened during the hold (no chord placed via a piano key, no modifier-row edit) -
  see `editedWhileHeld` and `stepPressedAt` in `onStepPad`. This exists specifically so you can hold
  an already-on step to inspect/edit its modifiers without also toggling it off; it went through a
  couple of iterations to get right, so don't simplify it back to "toggle on press" without
  re-reading why.
- **Multi-note steps**: rows 2/1/0 always show/edit the *lowest-pitched* note on the held step, but
  edits apply to every note on that step (`displayedModifierNote`, `occupiedKeysPerStep`).
- **`occupiedKeysPerStep`** (which (step, pitch) cells currently hold a note) is maintained purely
  from Bitwig's own `addNoteStepObserver` callback, not computed locally - it's the source of truth
  for step/row rendering and must stay in sync with whatever `NoteStep.State` reports, including
  edits made by the user with the mouse in Bitwig's own clip editor.

## Reference material used while building this

- `/Users/peternyboer/Documents/GitHub/bitwig-api-flat/BitwigAPI25.txt` - the full Bitwig Extension
  API (all interfaces/javadoc) flattened into one text file for fast lookup. Check the version
  matches the `extension-api` dependency version in `pom.xml` before trusting a signature from it.
- `/Users/peternyboer/Documents/GitHub/bitwig-extensions` - Bitwig's own bundled-extensions
  monorepo, including their official Launchpad Mini MK3/X/Pro implementations under
  `src/main/java/com/bitwig/extensions/controllers/novation/`. Useful as a working reference for
  protocol details and confirmed-correct palette colours (`commonsmk3/ColorLookup.java`,
  `RgbState.java`), but it's built on Bitwig's own internal `com.bitwig.extensions.framework`
  (dependency injection, `HardwareSurface`, layers) which this project deliberately does not depend
  on - this project talks to MIDI directly instead, which is simpler for a single bespoke
  controller. Don't import from that framework.
- Novation's "Launchpad Mini [MK3] Programmer's reference manual" (official PDF) is the primary
  source for the SysEx/MIDI protocol details baked into `LpProtocol` and `ColorLookup`.
