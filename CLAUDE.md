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
  Top control row (Up/Down/Left/Right/Session/Drums/Keys/User) is CC 91-98. Session/Drums/Keys/User
  (95-98) switch the extension's own software `Page` (see below) and reliably deliver CC presses in
  DAW mode - confirmed by reading Bitwig's own bundled Launchpad Mini MK3 extension, which binds real
  functionality to all of them, not just Up/Down. Left/Right (93/94) are wired up for Session-page
  track scrolling; whether they *light* reliably via `sendControlColor` (like Up/Down already do) is
  unconfirmed on real hardware - CC99 (Modifier Column corner button) remains unused. The "Modifier
  Column" - a 9th column past the 8x8 grid - is CC `19 + 10*row`, one button per row, User-page-only.
  `LpProtocol.modifierColumnCC` is the formula; don't hardcode these numbers elsewhere.
- **Four-page model**: the device's Session/Drums/Keys/User buttons (CC95-98) select one of four
  independent pages (`LaunchpadSeqExtension.Page`, `setPage`) - User (the sequencer described
  elsewhere in this doc) is the only page the rest of this doc's grid-layout description applies to.
  Session is a full 8x8 clip/scene launcher (`onSessionPad`, `renderSessionGrid`, backed by a
  separate `sessionTrackBank`/`sessionSceneBank` - unrelated to the sequencer's own single-row row-7
  launcher, which is still User-page-only). Session and User are both confirmed working on real
  hardware.
  - **User needed a SysEx fix, re-sent on *every* press, not just page changes**: it was completely
    inert (no LEDs, no input) until the DAW-mode-enable + layout-select SysEx got re-sent on entering
    SESSION or USER. The physical User button is Novation's "Custom Mode" (`LpMode.CUSTOM` in
    Bitwig's own bundled script) - the same "Lighting Custom Mode" noted above as requiring RGB SysEx
    instead of Note-On palette; pressing it evidently switches the device's firmware into that
    protocol, and re-asserting DAW mode + layout 0 pulls it back out. This re-assertion
    (`reassertGridProtocol`) is deliberately a *separate* method from `setPage`, called unconditionally
    on every Session/User press from `handleControl` - `setPage` itself still early-returns when the
    software page hasn't changed (to skip a redundant repaint), but the physical button press needs
    correcting even when press #2 in a row doesn't change `currentPage` at all. Confirmed via hardware
    testing: gating the SysEx behind "only on an actual page change" (the first version of this fix)
    left a second consecutive User press dark with notes leaking to `hardwareNoteInput`'s track,
    identical to the original bug, because the device's firmware re-enters Custom Mode on every press
    of the physical button, not just the first.
  - **The four page buttons' own LEDs are explicitly driven in `flush()`** (`pageButtonColor`),
    overriding the device's own autonomous indicator rather than trusting it - confirmed via hardware
    testing that the firmware's own indicator can show the wrong button lit after a page switch,
    consistent with the pre-existing "don't reliably light" note above.
  - **Drums/Keys note traffic arrives on a second, separate MIDI port**, not the "DAW" port
    (`getMidiInPort(0)`) everything else in this class talks to. Confirmed both by hardware testing
    (no console output at all from a temporary trace on port 0 while pressing Drums/Keys pads, ruling
    out a channel-filtering problem on that port) and by Bitwig's own bundled
    `LaunchPadMiniMk3ExtensionDefinition`, which declares a second input port name,
    `"Launchpad Mini MK3 LPMiniMK3 MIDI Out"` (Mac/Linux) / `"MIDIIN2 (LPMiniMK3 MIDI)"` (Windows) -
    this project's `LaunchpadSeqExtensionDefinition` now declares the same second port
    (`getNumMidiInPorts() == 2`), and `hardwareNoteInput` (a real, non-injection-only `NoteInput`) is
    registered on `getMidiInPort(1)` with Bitwig's own plain wildcard masks
    (`"8?????","9?????","A?????","D?????"`) - safe to use unmodified since this port is structurally
    separate from the grid/session port, unlike an earlier (reverted) attempt at channel-0 exclusion
    on port 0, which was solving a problem that didn't exist there. No code-side note handling is
    needed for Drums/Keys at all - traffic on port 1 never reaches `onMidi`, and Bitwig's own
    host-level routing handles delivering it to whatever track the user assigns as that NoteInput's
    input.
  - **Correction to earlier work**: an early version of this fix assumed Drums arrives on MIDI
    channel 8, based on reading `launchpadmini3/layers/DrumLayer.java` in
    `/Users/peternyboer/Documents/GitHub/bitwig-extensions` - that file turned out to belong to
    `LaunchpadXControllerExtension` (the Launchpad **X**, a different model), not
    `LaunchpadMiniMk3ControllerExtension` (the actual Mini MK3 this project targets, which has no
    Drums/Keys note-routing logic at all in its own script - just cosmetic mode-tracking). Don't
    trust `layers/DrumLayer.java` or `LaunchpadXControllerExtension`/`LaunchPadXExtensionDefinition`
    for Mini MK3 behavior again - use the `LaunchPadMiniMk3ExtensionDefinition`/
    `LaunchpadMiniMk3ControllerExtension` pair specifically.
- **`ControllerHost.println`/`errorln` write to Bitwig's Control Surface Console (View menu), not
  `BitwigStudio.log`** - only uncaught exceptions land in the log file. Don't assume a temporary
  `println` trace will show up there; check the console window in Bitwig itself, or have the user do
  so and report back.
- **`ControllerHost.createCursorTrack`**: the extension calls the 5-arg overload
  (`id, name, numSends, numScenes, shouldFollowSelection`), not the deprecated 3-arg one - Bitwig
  throws on any deprecated API call by default, which kills `init()`. `numScenes` also isn't
  optional in practice: passing 0 makes `clipLauncherSlotBank()` return null (already hit this once).
- **Grid layout is currently** (User page only - see "Four-page model" above): row 7 (top) = clip
  launcher slots; rows 6/5 = the 16 step toggles;
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
- **Multi-note steps**: rows 2/1/0 always show/edit the *lowest-pitched note that actually starts on
  the held step* - never a step that only has a longer note sustaining through it (`noteOnKeysPerStep`,
  not `occupiedKeysPerStep`; see `displayedModifierNote`/`editTargetCells`) - and edits apply to every
  such note on that step.
- **`occupiedKeysPerStep`/`noteOnKeysPerStep`** (which (step, pitch) cells currently hold a note, and
  which of those are the note's actual start vs. just sustain, respectively) are kept in sync two ways:
  incrementally via Bitwig's `addNoteStepObserver` callback for live edits (including ones made with
  the mouse in Bitwig's own clip editor), and rebuilt from scratch via `refreshVisibleSteps()` (a
  direct `clip.getStep()` pull) any time the view's identity changes - `scrollToStep()` in
  `setViewOffset`, or loading a different clip in `onClipPad`. The pull exists because
  `addNoteStepObserver` only fires when a given (channel, x, y) address's *reported value* changes -
  after scrolling moves a different absolute step into relative position x, if that step's
  occupied/empty state happens to coincidentally match what was last reported at that same x on the
  previous page, no callback ever arrives, silently leaving a cleared entry wrong until something else
  happens to touch that exact cell. Found via a real bug report: a step's LED could stay dark forever
  after paging away and back, despite the note genuinely being there.

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
