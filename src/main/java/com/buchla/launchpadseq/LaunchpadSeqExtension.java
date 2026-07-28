package com.buchla.launchpadseq;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.NoteStep;
import com.bitwig.extension.controller.api.PinnableCursorClip;
import com.bitwig.extension.controller.api.Transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step sequencer for editing note-grid clips from a Launchpad Mini [MK3]'s 8x8 pad grid.
 *
 * Grid layout (rows counted from the bottom pad row, as in Novation's numbering):
 *   row 7 (top)    - clip launcher slots for the cursor track (8 clips)
 *   rows 6 and 5   - step toggles, 16 steps total (row 6 = steps 0-7, row 5 = steps 8-15)
 *   row 4          - one-octave keyboard, black keys (also octave shift at columns 0 and 7)
 *   row 3          - one-octave keyboard, white keys
 *   rows 2, 1, 0    - modifier rows for the held step (or the whole clip - see below); what
 *                     they edit depends on the current Modifier Column mode (see below)
 *
 * The 9th column past the edge of the 8x8 grid ("Modifier Column", CC 19-89, +10 per row -
 * see LpProtocol) is a set of radio buttons that pick what rows 2-0 do. Only the bottom three
 * are used so far, one per mode:
 *   - Note Ops (row 2 button, CC39, the default mode): row 2 = recurrence pattern (8 cycles),
 *     row 1 = chance as a 0-8 bar in 12.5% increments, row 0 = note length in 1/16 increments
 *     up to 8/16.
 *   - Note Expressions (row 1 button, CC29): row 2 = timbre, -100..100 and bipolar - each
 *     column alone sets one of 8 values skipping zero (-100,-75,-50,-25,25,50,75,100), and two
 *     adjacent held columns set the midpoint of theirs, e.g. columns 3+4 land on the skipped 0
 *     (see timbreSingleValue). Row 1 = pressure as a 0-8 bar in 12.5% increments, row 0 =
 *     velocity, same.
 *   - The row 0 button (CC19) is reserved; selecting it leaves rows 2-0 blank.
 * Whichever mode is active lights its Modifier Column button white; the other two are off.
 *
 * Holding a piano pad and pressing a step pad toggles a note at that pitch on that step
 * (and vice versa, so either press order works) - this happens immediately on press.
 *
 * Pressing a step pad alone (no piano pad held) does nothing until it's released: on release,
 * if nothing else happened during the hold (no chord placed via a piano pad, no rows 2-0
 * modifier edit), it's treated as a plain tap - clearing the step if it holds any notes, or
 * adding one at the last-used pitch if it's empty. If something did happen during the hold,
 * the release applies no further action, since the hold was clearly used to edit the step
 * rather than to tap it on/off. This is what makes it possible to hold an already-on step
 * just to inspect or edit its modifiers without also toggling it off.
 *
 * Rows 2-0 normally reflect and edit the note(s) on whichever step is currently held down
 * (rows 6/5): if a step holds more than one note, they show/edit the lowest-pitched one, and
 * edits apply to every note in that step. They're blank when no step is held or the held step
 * is empty - there's nothing to show or edit until a note exists.
 *
 * Holding a clip pad (row 7) instead of a step - with no step held - switches rows 2-0 to
 * whole-clip mode: they show default values (recurrence off, 100% chance, 1/16 length, 0
 * timbre, etc. - see the displayedXxx methods) rather than any particular note's, and any edit
 * applies to every note in the whole clip, not just one step's. A held step always takes
 * priority over a held clip pad if both happen to be held at once.
 *
 * Clips are normally 16 steps (1 bar), but two more Modifier Column buttons - CC79 (next to row
 * 6) and CC69 (next to row 5) - extend that. Pressing CC79 alone shows/edits steps 1-16; CC69
 * alone extends the clip to 32 steps (if it wasn't already) and shows/edits steps 17-32 - neither
 * ever shrinks the clip back down, so switching between them never loses anything in 17-32.
 * Holding both down together (a chord, in either press order) turns on auto-follow instead: both
 * buttons light, and the view automatically jumps to whichever page contains the playhead as the
 * clip plays (see the playingStep() observer in init). Pressing either button on its own again -
 * a single press, not part of a chord - turns auto-follow back off and fixes the view on that
 * button's page, including while stopped, so you can always get to either page to program it.
 * Implemented via clip.scrollToStep(), not by resizing the note-grid window itself - the 16
 * physical step pads always address "whatever's currently scrolled into view", so the rest of
 * the step/modifier logic doesn't need to know there's more than 16 steps at all. A clip's actual
 * length (see clipLengthBeats) also decides what loading it shows - loading an already-32-step
 * clip shows CC69's page - and what length a new clip is created at.
 *
 * Two more Modifier Column buttons, next to the piano rows, pick what a piano pad press actually
 * does - same press/chord pattern as the view buttons (a single press picks a mode, holding both
 * together is a third mode). CC59 (next to row 4, black keys, the default) is PIANO_MODE_HOLD -
 * the hold-a-piano-pad-then-press-a-step behaviour described above. CC49 (next to row 3, white
 * keys) is PIANO_MODE_LIVE_STEP: pressing a piano pad immediately toggles that pitch on whatever
 * step the playhead is currently on (jumping the view to that page first if needed), with no step
 * pad involved at all - a live-record-while-playing gesture. Chording both is PIANO_MODE_PLAY_ALONG:
 * pressing a piano pad just plays that note (via a dedicated NoteInput, "Launchpad Seq Play
 * Along" - the user has to select it as a track's input in Bitwig for anything to actually sound,
 * same as any other MIDI controller) and writes nothing to the clip at all. Octave shift (the
 * black-key row's columns 0 and 7) isn't part of this dispatch and works identically in all three
 * modes, so transposition stays consistent regardless of which piano mode is active.
 */
public class LaunchpadSeqExtension extends ControllerExtension {

    private static final int STEP_COUNT = 16;
    private static final int GRID_HEIGHT = 128;
    private static final double STEP_SIZE_BEATS = 0.25; // sixteenth notes: 16 steps = 1 bar of 4/4
    private static final int NEW_CLIP_LENGTH_BEATS = 4; // 16 steps, 1 bar
    private static final int LONG_CLIP_LENGTH_BEATS = NEW_CLIP_LENGTH_BEATS * 2; // 32 steps, 2 bars
    private static final int INSERT_VELOCITY = 100;
    private static final int NOTE_CHANNEL = 0;

    private static final int BASE_NOTE = 60; // C3, per Bitwig's convention
    private static final int MIN_OCTAVE_OFFSET = -4;
    private static final int MAX_OCTAVE_OFFSET = 4;

    // Semitone offset from the octave's root for each of the 8 columns; -1 = no pad (no black key there).
    private static final int[] WHITE_KEY_OFFSETS = {0, 2, 4, 5, 7, 9, 11, 12};
    private static final int[] BLACK_KEY_OFFSETS = {-1, 1, 3, -1, 6, 8, 10, -1};

    private static final int ROW_CLIPS = 7;
    private static final int ROW_STEPS_HI = 6; // steps 0-7
    private static final int ROW_STEPS_LO = 5; // steps 8-15
    private static final int ROW_BLACK_KEYS = 4;
    private static final int ROW_WHITE_KEYS = 3;
    private static final int ROW_MOD_TOP = 2;
    private static final int ROW_MOD_MID = 1;
    private static final int ROW_MOD_BOTTOM = 0;

    private static final int MODE_NOTE_OPS = 0;
    private static final int MODE_NOTE_EXPRESSIONS = 1;
    private static final int MODE_RESERVED = 2;

    private static final int VIEW_MODE_FIXED = 0;
    private static final int VIEW_MODE_AUTO = 1;
    private static final int VIEW_BUTTON_PAGE_0 = 0;
    private static final int VIEW_BUTTON_PAGE_1 = 1;

    private static final int PIANO_MODE_HOLD = 0; // hold a piano pad, press a step to place it (default)
    private static final int PIANO_MODE_LIVE_STEP = 1; // press a piano pad to place it at the playhead's step
    private static final int PIANO_MODE_PLAY_ALONG = 2; // press a piano pad to just play it, no step writes
    private static final int PIANO_MODE_BUTTON_FIRST = 0; // CC59, black-key row - PIANO_MODE_HOLD
    private static final int PIANO_MODE_BUTTON_SECOND = 1; // CC49, white-key row - PIANO_MODE_LIVE_STEP

    private static final int RECURRENCE_LENGTH = 8;
    private static final int MODIFIER_LEVELS = 8; // columns per bar-style modifier row
    private static final long TAP_THRESHOLD_MS = 300; // below this, a step press+release is a tap

    // Default values shown/applied for whole-clip editing (no held note to read from) and used
    // as the starting point for a fresh note. Chance and length match Bitwig's own note defaults;
    // timbre 0 is confirmed by spec. Pressure and velocity defaults aren't documented anywhere
    // we've found, so these are reasonable assumptions: 0 pressure (no aftertouch applied is the
    // natural "nothing" value), and velocity matching INSERT_VELOCITY (what notes we create
    // actually get).
    private static final double DEFAULT_CHANCE = 1.0;
    private static final double DEFAULT_LENGTH_BEATS = STEP_SIZE_BEATS;
    private static final double DEFAULT_TIMBRE_UI = 0.0;
    private static final double DEFAULT_PRESSURE = 0.0;
    private static final double DEFAULT_VELOCITY = INSERT_VELOCITY / 127.0;

    private static final int STEP_PLAYHEAD_COLOR = ColorLookup.WHITE;
    private static final int KEY_IDLE_COLOR = ColorLookup.TURQUOISE;
    private static final int KEY_HELD_COLOR = ColorLookup.WHITE;
    private static final int CONTROL_IDLE_COLOR = ColorLookup.DIM_GREY;
    private static final int MODE_ACTIVE_COLOR = ColorLookup.WHITE;
    private static final int RECURRENCE_COLOR = ColorLookup.YELLOW;
    private static final int CHANCE_COLOR = ColorLookup.ORANGE;
    private static final int LENGTH_COLOR = ColorLookup.RED;
    private static final int TIMBRE_COLOR = ColorLookup.BLUE;
    private static final int PRESSURE_COLOR = ColorLookup.PURPLE;
    private static final int VELOCITY_COLOR = ColorLookup.GREEN;

    private MidiIn midiIn;
    private MidiOut midiOut;
    private Transport transport;
    // Note-input purely for injecting notes into whatever track's input the user assigns it to,
    // for PIANO_MODE_PLAY_ALONG - never receives real hardware input (see the never-matching mask
    // passed to createNoteInput in init).
    private NoteInput previewNoteInput;

    private CursorTrack cursorTrack;
    private ClipLauncherSlotBank slotBank;
    private int bankSize;
    private PinnableCursorClip clip;

    private int octaveOffset = 0;
    private int lastPitch = BASE_NOTE;
    private int playingStep = -1; // absolute step index across the whole clip; -1 = not playing
    private int displayStep = -1; // step currently held, whose modifiers rows 2-0 show/edit; -1 = none
    private int modifierMode = MODE_NOTE_OPS; // which Modifier Column radio button is selected
    private int pianoMode = PIANO_MODE_HOLD; // which of the two piano-mode buttons is selected
    // Which of the two piano-mode buttons (PIANO_MODE_BUTTON_FIRST/SECOND) are currently held, so
    // a chord (both at once) can be told apart from two independent single presses - mirrors
    // heldViewButtons/onViewButtonPress.
    private final Set<Integer> heldPianoModeButtons = new HashSet<>();
    // Transport position (in beats) at which the clip's current run started - i.e. the last time
    // it was freshly launched or retriggered, not just looped naturally. Re-synced in the
    // playingStep() observer; see currentRecurrenceCycle for why this needs to exist at all.
    private double clipTriggerBeats = 0.0;

    // Which 16-step page of the clip the step/piano/modifier rows currently address: 0 or
    // STEP_COUNT. Kept in sync with clip.scrollToStep() - see setViewOffset.
    private int viewOffset = 0;
    // Fixed (view buttons manually pick the page) or auto (the page follows the playhead while
    // playing) - see onViewButtonPress and the auto-follow check in the playingStep() observer.
    private int viewMode = VIEW_MODE_FIXED;
    // Which of the two view buttons (VIEW_BUTTON_PAGE_0/1) are currently held, so a chord (both
    // held at once) can be told apart from two independent single presses - see onViewButtonPress.
    private final Set<Integer> heldViewButtons = new HashSet<>();
    // The loaded clip's actual current length in beats, tracked from clip.getPlayStop() - used to
    // decide whether there's a second page at all, and to reset viewOffset when a different clip
    // (of possibly different length) gets loaded.
    private double clipLengthBeats = NEW_CLIP_LENGTH_BEATS;

    private final Set<Integer> heldPianoKeys = new HashSet<>();
    private final Set<Integer> heldSteps = new HashSet<>();
    // Clip pads (row 7) currently held, for whole-clip modifier editing - see the class doc comment.
    private final Set<Integer> heldClipPads = new HashSet<>();
    // Accumulator for the recurrence pattern being built up in whole-clip mode: there's no single
    // representative note to read a "current" mask from, unlike single-step editing, so this is
    // its own scratch state, reset to the default (off) each time a fresh clip-pad hold begins.
    private int bulkRecurrenceMask = 0xFF;
    private boolean bulkRecurrenceEnabled = false;
    // Columns currently held in row 2 while in Note Expressions mode, for the timbre chord gesture.
    private final Set<Integer> heldTimbreCols = new HashSet<>();
    // Steps that had a note placed (via a held piano key) or a modifier edited during the
    // current hold - if a step is in here on release, its plain toggle/clear is skipped,
    // since the hold was clearly used to edit it rather than to tap it on/off.
    private final Set<Integer> editedWhileHeld = new HashSet<>();
    // Press timestamp per currently-held step, so a release can tell a quick tap (toggles the
    // step) from a hold used just to look at its modifiers (leaves the step alone either way).
    private final Map<Integer, Long> stepPressedAt = new HashMap<>();
    @SuppressWarnings("unchecked")
    private final Set<Integer>[] occupiedKeysPerStep = new HashSet[STEP_COUNT];

    // Last palette colour index sent per LED index, so flush() only sends what changed. -1 = never sent.
    private final int[] lastSentColor = new int[100];

    protected LaunchpadSeqExtension(final ControllerExtensionDefinition definition, final ControllerHost host) {
        super(definition, host);
    }

    @Override
    public void init() {
        final ControllerHost host = getHost();

        for (int i = 0; i < STEP_COUNT; i++) {
            occupiedKeysPerStep[i] = new HashSet<>();
        }
        Arrays.fill(lastSentColor, -1);

        midiIn = host.getMidiInPort(0);
        midiOut = host.getMidiOutPort(0);
        midiIn.setMidiCallback(this::onMidi);
        // "FF????" never matches anything the Launchpad itself sends (it only ever sends channel
        // voice messages, 0x80-0xEF), so this note input never intercepts our own grid/CC parsing
        // above - it exists purely so sendRawMidiEvent can inject notes for PIANO_MODE_PLAY_ALONG.
        previewNoteInput = midiIn.createNoteInput("Launchpad Seq Play Along", "FF????");

        transport = host.createTransport();
        transport.getPosition().markInterested();

        cursorTrack = host.createCursorTrack("LAUNCHPAD_SEQ_TRACK", "Launchpad Seq", 0, 8, true);

        slotBank = cursorTrack.clipLauncherSlotBank();
        slotBank.setSizeOfBank(Math.min(8, slotBank.getCapacityOfBank()));
        bankSize = slotBank.getSizeOfBank();
        for (int i = 0; i < bankSize; i++) {
            final ClipLauncherSlot slot = slotBank.getItemAt(i);
            slot.hasContent().addValueObserver(v -> host.requestFlush());
            slot.isPlaying().addValueObserver(v -> host.requestFlush());
            slot.color().addValueObserver((r, g, b) -> host.requestFlush());
        }

        clip = cursorTrack.createLauncherCursorClip(STEP_COUNT, GRID_HEIGHT);
        clip.scrollToKey(0);
        clip.setStepSize(STEP_SIZE_BEATS);
        clip.addNoteStepObserver(this::onNoteStepChanged);
        clip.playingStep().addValueObserver(v -> {
            // playingStep() is absolute across the whole clip (unlike NoteStep.x(), which is
            // relative to the current scroll window) - confirmed by testing, not just assumed.
            // -1 means this clip isn't currently playing at all.
            if (v >= 0 && (playingStep < 0 || v < playingStep)) {
                final int lastStep = (int) Math.round(clipLengthBeats / STEP_SIZE_BEATS) - 1;
                final boolean naturalWrap = playingStep == lastStep;
                if (!naturalWrap) {
                    // A fresh start from stopped, or a retrigger mid-playback (a jump back from
                    // somewhere other than the clip's actual last step): re-sync the recurrence
                    // phase anchor so this pass counts as the first one again. A natural wrap
                    // (playingStep really did just reach the last step) leaves it alone, since the
                    // pass count should keep advancing continuously through those.
                    clipTriggerBeats = transport.getPosition().get() - v * STEP_SIZE_BEATS;
                }
            }
            if (viewMode == VIEW_MODE_AUTO && clipLengthBeats > NEW_CLIP_LENGTH_BEATS && v >= 0) {
                setViewOffset(v >= STEP_COUNT ? STEP_COUNT : 0);
            }
            playingStep = v;
            host.requestFlush();
        });
        clip.color().addValueObserver((r, g, b) -> host.requestFlush());
        clip.getPlayStop().addValueObserver(beats -> {
            clipLengthBeats = beats;
            setViewOffset(beats > NEW_CLIP_LENGTH_BEATS ? STEP_COUNT : 0);
        });

        midiOut.sendSysex(LpProtocol.dawMode(true));
        midiOut.sendSysex(LpProtocol.selectLayout(0)); // Session layout

        host.requestFlush();
    }

    @Override
    public void exit() {
        midiOut.sendSysex(LpProtocol.dawMode(false));
    }

    @Override
    public void flush() {
        for (int col = 0; col < 8; col++) {
            sendColor(LpProtocol.gridNote(ROW_CLIPS, col), clipPadColor(col));
        }
        for (int col = 0; col < 8; col++) {
            sendColor(LpProtocol.gridNote(ROW_STEPS_HI, col), stepPadColor(col));
            sendColor(LpProtocol.gridNote(ROW_STEPS_LO, col), stepPadColor(col + 8));
        }
        for (int col = 0; col < 8; col++) {
            sendColor(LpProtocol.gridNote(ROW_BLACK_KEYS, col), blackKeyColor(col));
            sendColor(LpProtocol.gridNote(ROW_WHITE_KEYS, col), whiteKeyColor(col));
        }
        for (int col = 0; col < 8; col++) {
            sendColor(LpProtocol.gridNote(ROW_MOD_TOP, col), modTopColor(col));
            sendColor(LpProtocol.gridNote(ROW_MOD_MID, col), modMidColor(col));
            sendColor(LpProtocol.gridNote(ROW_MOD_BOTTOM, col), modBottomColor(col));
        }
        sendControlColor(LpProtocol.CC_OCTAVE_UP, CONTROL_IDLE_COLOR);
        sendControlColor(LpProtocol.CC_OCTAVE_DOWN, CONTROL_IDLE_COLOR);
        sendControlColor(LpProtocol.modifierColumnCC(ROW_MOD_TOP), modeButtonColor(MODE_NOTE_OPS));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_MOD_MID), modeButtonColor(MODE_NOTE_EXPRESSIONS));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_MOD_BOTTOM), modeButtonColor(MODE_RESERVED));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_STEPS_HI), viewButtonColor(viewOffset == 0));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_STEPS_LO), viewButtonColor(viewOffset == STEP_COUNT));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_BLACK_KEYS), pianoModeButtonColor(PIANO_MODE_BUTTON_FIRST));
        sendControlColor(LpProtocol.modifierColumnCC(ROW_WHITE_KEYS), pianoModeButtonColor(PIANO_MODE_BUTTON_SECOND));
    }

    /** Both view buttons light in auto-follow mode; otherwise only whichever matches the current page. */
    private int viewButtonColor(final boolean isCurrentPage) {
        if (viewMode == VIEW_MODE_AUTO) {
            return MODE_ACTIVE_COLOR;
        }
        return isCurrentPage ? MODE_ACTIVE_COLOR : ColorLookup.OFF;
    }

    /** Both piano-mode buttons light in play-along mode; otherwise only whichever matches the current mode. */
    private int pianoModeButtonColor(final int button) {
        if (pianoMode == PIANO_MODE_PLAY_ALONG) {
            return MODE_ACTIVE_COLOR;
        }
        final int expectedMode = button == PIANO_MODE_BUTTON_FIRST ? PIANO_MODE_HOLD : PIANO_MODE_LIVE_STEP;
        return pianoMode == expectedMode ? MODE_ACTIVE_COLOR : ColorLookup.OFF;
    }

    /**
     * Which of the 8 recurrence cycles is "currently playing", taken as the transport's absolute
     * position - relative to clipTriggerBeats, i.e. when the clip's current run actually started -
     * modulo the clip's own length (in "bars", one bar being one clip length). There's no direct
     * API for a note's actual recurrence-cycle counter, so this is deliberately an approximation:
     * mostly a stateless read of where the main playhead is (so it can't accumulate drift the way
     * an incremental counter did in an earlier version - see git history), but anchored to the
     * clip's last (re)trigger so it stays in phase across retriggers, which a purely stateless
     * transport-position-modulo-length calculation doesn't know about at all.
     */
    private int currentRecurrenceCycle() {
        final double beats = transport.getPosition().get();
        final int bar = (int) Math.floor((beats - clipTriggerBeats) / clipLengthBeats);
        return ((bar % RECURRENCE_LENGTH) + RECURRENCE_LENGTH) % RECURRENCE_LENGTH;
    }

    private void setViewOffset(final int offset) {
        if (offset == viewOffset) {
            return;
        }
        viewOffset = offset;
        clip.scrollToStep(offset);
        for (int i = 0; i < STEP_COUNT; i++) {
            occupiedKeysPerStep[i].clear();
        }
        getHost().requestFlush();
    }

    private int modeButtonColor(final int mode) {
        return modifierMode == mode ? MODE_ACTIVE_COLOR : ColorLookup.OFF;
    }

    private int modTopColor(final int col) {
        if (modifierMode == MODE_NOTE_OPS) {
            return recurrenceColor(col);
        }
        if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            return timbreColor(col);
        }
        return ColorLookup.OFF;
    }

    private int modMidColor(final int col) {
        if (modifierMode == MODE_NOTE_OPS) {
            return chanceColor(col);
        }
        if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            return pressureColor(col);
        }
        return ColorLookup.OFF;
    }

    private int modBottomColor(final int col) {
        if (modifierMode == MODE_NOTE_OPS) {
            return lengthColor(col);
        }
        if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            return velocityColor(col);
        }
        return ColorLookup.OFF;
    }

    /** The step whose modifier note (lowest pitch) rows 2-0 are showing, or null if none. */
    private NoteStep displayedModifierNote() {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return null;
        }
        final int lowestY = Collections.min(occupiedKeysPerStep[displayStep]);
        return clip.getStep(NOTE_CHANNEL, displayStep, lowestY);
    }

    private int recurrenceColor(final int col) {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            if (note == null) {
                return ColorLookup.OFF;
            }
            // No pattern applied yet (the default) shows as fully off, not as an all-on pattern -
            // only an actually-applied mask lights anything here.
            final boolean on = note.isRecurrenceEnabled() && ((note.recurrenceMask() >> col) & 1) != 0;
            return recurrenceCellColor(on, col);
        }
        if (!heldClipPads.isEmpty()) {
            final boolean on = bulkRecurrenceEnabled && ((bulkRecurrenceMask >> col) & 1) != 0;
            return recurrenceCellColor(on, col);
        }
        return ColorLookup.OFF;
    }

    private int recurrenceCellColor(final boolean on, final int col) {
        final boolean playhead = playingStep >= 0 && col == currentRecurrenceCycle();
        if (playhead) {
            return on ? ColorLookup.PALE_YELLOW : ColorLookup.WHITE;
        }
        return on ? RECURRENCE_COLOR : ColorLookup.OFF;
    }

    /** Chance 0-1, from the held step's lowest note, the whole-clip default, or null if neither
     * a step nor a clip pad is held. */
    private Double displayedChance() {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            return note == null ? null : (note.isChanceEnabled() ? note.chance() : DEFAULT_CHANCE);
        }
        return heldClipPads.isEmpty() ? null : DEFAULT_CHANCE;
    }

    private int chanceColor(final int col) {
        final Double chance = displayedChance();
        if (chance == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(chance * MODIFIER_LEVELS);
        return col < level ? CHANCE_COLOR : ColorLookup.OFF;
    }

    private Double displayedLengthBeats() {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            return note == null ? null : note.duration();
        }
        return heldClipPads.isEmpty() ? null : DEFAULT_LENGTH_BEATS;
    }

    private int lengthColor(final int col) {
        final Double lengthBeats = displayedLengthBeats();
        if (lengthBeats == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(lengthBeats / STEP_SIZE_BEATS);
        return col < level ? LENGTH_COLOR : ColorLookup.OFF;
    }

    private Double displayedTimbreUi() {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            return note == null ? null : note.timbre() * 100.0;
        }
        return heldClipPads.isEmpty() ? null : DEFAULT_TIMBRE_UI;
    }

    private int timbreColor(final int col) {
        final Double uiValue = displayedTimbreUi();
        if (uiValue == null) {
            return ColorLookup.OFF;
        }
        return isTimbreColumnLit(uiValue, col) ? TIMBRE_COLOR : ColorLookup.OFF;
    }

    /**
     * A single column, left to right, sets one of 8 evenly-spaced values skipping zero: -100,
     * -75, -50, -25, 25, 50, 75, 100 (so there's a wider-than-usual gap between columns 3 and 4).
     * Holding two adjacent columns together sets the midpoint of their two single-press values -
     * e.g. columns 3 and 4 together land exactly on the skipped 0.
     */
    private static double timbreSingleValue(final int col) {
        final int slot = col < 4 ? col : col + 1; // slots 0-8 are -100..100 in steps of 25; slot 4 (0) is skipped
        return -100 + 25.0 * slot;
    }

    /** Finds whichever of the 8 single-press values or 7 adjacent-chord midpoints is closest to
     * the given value, and reports whether that representation would light the given column. */
    private static boolean isTimbreColumnLit(final double uiValue, final int col) {
        int nearestSingleCol = 0;
        double nearestSingleDiff = Double.MAX_VALUE;
        for (int c = 0; c < MODIFIER_LEVELS; c++) {
            final double diff = Math.abs(timbreSingleValue(c) - uiValue);
            if (diff < nearestSingleDiff) {
                nearestSingleDiff = diff;
                nearestSingleCol = c;
            }
        }
        int nearestChordLo = 0;
        double nearestChordDiff = Double.MAX_VALUE;
        for (int c = 0; c < MODIFIER_LEVELS - 1; c++) {
            final double mid = (timbreSingleValue(c) + timbreSingleValue(c + 1)) / 2.0;
            final double diff = Math.abs(mid - uiValue);
            if (diff < nearestChordDiff) {
                nearestChordDiff = diff;
                nearestChordLo = c;
            }
        }
        if (nearestSingleDiff <= nearestChordDiff) {
            return col == nearestSingleCol;
        }
        return col == nearestChordLo || col == nearestChordLo + 1;
    }

    private Double displayedPressure() {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            return note == null ? null : note.pressure();
        }
        return heldClipPads.isEmpty() ? null : DEFAULT_PRESSURE;
    }

    private int pressureColor(final int col) {
        final Double pressure = displayedPressure();
        if (pressure == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(pressure * MODIFIER_LEVELS);
        return col < level ? PRESSURE_COLOR : ColorLookup.OFF;
    }

    private Double displayedVelocity() {
        if (displayStep >= 0) {
            final NoteStep note = displayedModifierNote();
            return note == null ? null : note.velocity();
        }
        return heldClipPads.isEmpty() ? null : DEFAULT_VELOCITY;
    }

    private int velocityColor(final int col) {
        final Double velocity = displayedVelocity();
        if (velocity == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(velocity * MODIFIER_LEVELS);
        return col < level ? VELOCITY_COLOR : ColorLookup.OFF;
    }

    /** Every (x, y) note cell that rows 2-0 currently edit: all notes in the held step, or (if no
     * step is held) all notes in the whole clip while a clip pad is held. Empty if neither. */
    private List<int[]> editTargetCells() {
        final List<int[]> cells = new ArrayList<>();
        if (displayStep >= 0) {
            for (final int y : occupiedKeysPerStep[displayStep]) {
                cells.add(new int[]{displayStep, y});
            }
        } else if (!heldClipPads.isEmpty()) {
            for (int x = 0; x < STEP_COUNT; x++) {
                for (final int y : occupiedKeysPerStep[x]) {
                    cells.add(new int[]{x, y});
                }
            }
        }
        return cells;
    }

    /** Marks the held step as edited, so releasing it doesn't also toggle it off. No-op in
     * whole-clip mode, which has no equivalent tap/hold gesture to protect. */
    private void markEdited() {
        if (displayStep >= 0) {
            editedWhileHeld.add(displayStep);
        }
    }

    /** For the 8x8 grid, which is note-addressed. */
    private void sendColor(final int index, final int paletteColor) {
        sendColorViaStatus(LpProtocol.NOTE_STATUS, index, paletteColor);
    }

    /** For CC-addressed controls (top row, Modifier Column) - lighting these needs a CC message,
     * not a Note-On; unlike Programmer mode, Session/DAW mode doesn't accept either for a given
     * index interchangeably. */
    private void sendControlColor(final int index, final int paletteColor) {
        sendColorViaStatus(LpProtocol.CC_STATUS, index, paletteColor);
    }

    private void sendColorViaStatus(final int status, final int index, final int paletteColor) {
        if (lastSentColor[index] == paletteColor) {
            return;
        }
        lastSentColor[index] = paletteColor;
        midiOut.sendMidi(status, index, paletteColor);
    }

    private int clipPadColor(final int col) {
        if (col >= bankSize) {
            return ColorLookup.OFF;
        }
        final ClipLauncherSlot slot = slotBank.getItemAt(col);
        if (!slot.hasContent().get()) {
            return ColorLookup.OFF;
        }
        return ColorLookup.nearestPaletteIndex(slot.color().red(), slot.color().green(), slot.color().blue());
    }

    private int stepPadColor(final int x) {
        // playingStep() is absolute across the whole clip, unlike note addressing (x), which is
        // relative to the current scroll window - so it needs the view offset added back in.
        if (x + viewOffset == playingStep) {
            return STEP_PLAYHEAD_COLOR;
        }
        if (occupiedKeysPerStep[x].isEmpty()) {
            return ColorLookup.DIM_GREY;
        }
        return ColorLookup.nearestPaletteIndex(clip.color().red(), clip.color().green(), clip.color().blue());
    }

    private int blackKeyColor(final int col) {
        if (col == 0) {
            return octaveShiftColor(-octaveOffset);
        }
        if (col == 7) {
            return octaveShiftColor(octaveOffset);
        }
        final int offset = BLACK_KEY_OFFSETS[col];
        if (offset < 0) {
            return ColorLookup.OFF;
        }
        return heldPianoKeys.contains(pitchForOffset(offset)) ? KEY_HELD_COLOR : KEY_IDLE_COLOR;
    }

    private int octaveShiftColor(final int magnitude) {
        if (magnitude <= 0) {
            return ColorLookup.OFF;
        }
        if (magnitude == 1) {
            return ColorLookup.YELLOW;
        }
        if (magnitude == 2) {
            return ColorLookup.ORANGE;
        }
        return ColorLookup.RED;
    }

    private int whiteKeyColor(final int col) {
        final int offset = WHITE_KEY_OFFSETS[col];
        return heldPianoKeys.contains(pitchForOffset(offset)) ? KEY_HELD_COLOR : KEY_IDLE_COLOR;
    }

    private int pitchForOffset(final int offset) {
        return BASE_NOTE + octaveOffset * 12 + offset;
    }

    private void onNoteStepChanged(final NoteStep step) {
        if (step.channel() != NOTE_CHANNEL) {
            return;
        }
        final int x = step.x();
        if (x < 0 || x >= STEP_COUNT) {
            return;
        }
        final int y = step.y();
        if (step.state() == NoteStep.State.Empty) {
            occupiedKeysPerStep[x].remove(y);
        } else {
            occupiedKeysPerStep[x].add(y);
        }
        getHost().requestFlush();
    }

    private void onMidi(final int status, final int data1, final int data2) {
        final int type = status & 0xF0;

        if (type == LpProtocol.NOTE_STATUS || type == 0x80) {
            final boolean isOn = type == LpProtocol.NOTE_STATUS && data2 > 0;
            handleNote(data1, isOn);
        } else if (type == LpProtocol.CC_STATUS) {
            handleControl(data1, data2 > 0);
        }
    }

    private void handleNote(final int note, final boolean isOn) {
        if (note < 11 || note > 88) {
            return;
        }
        final int row = (note - 11) / 10;
        final int col = (note - 11) % 10;
        if (col > 7) {
            return;
        }

        switch (row) {
            case ROW_CLIPS:
                if (col < bankSize) {
                    onClipPad(col, isOn);
                }
                break;
            case ROW_STEPS_HI:
                onStepPad(col, isOn);
                break;
            case ROW_STEPS_LO:
                onStepPad(col + 8, isOn);
                break;
            case ROW_BLACK_KEYS:
                if (col == 0) {
                    if (isOn) {
                        shiftOctave(-1);
                    }
                } else if (col == 7) {
                    if (isOn) {
                        shiftOctave(1);
                    }
                } else if (BLACK_KEY_OFFSETS[col] >= 0) {
                    onPianoPad(BLACK_KEY_OFFSETS[col], isOn);
                }
                break;
            case ROW_WHITE_KEYS:
                onPianoPad(WHITE_KEY_OFFSETS[col], isOn);
                break;
            case ROW_MOD_TOP:
                onModTopPad(col, isOn);
                break;
            case ROW_MOD_MID:
                if (isOn) {
                    onModMidPad(col);
                }
                break;
            case ROW_MOD_BOTTOM:
                if (isOn) {
                    onModBottomPad(col);
                }
                break;
            default:
                break;
        }
    }

    private void onModTopPad(final int col, final boolean isOn) {
        if (modifierMode == MODE_NOTE_OPS) {
            if (isOn) {
                onRecurrencePad(col);
            }
        } else if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            onTimbrePad(col, isOn);
        }
    }

    private void onModMidPad(final int col) {
        if (modifierMode == MODE_NOTE_OPS) {
            onChancePad(col);
        } else if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            onPressurePad(col);
        }
    }

    private void onModBottomPad(final int col) {
        if (modifierMode == MODE_NOTE_OPS) {
            onLengthPad(col);
        } else if (modifierMode == MODE_NOTE_EXPRESSIONS) {
            onVelocityPad(col);
        }
    }

    private void handleControl(final int cc, final boolean isOn) {
        if (cc == LpProtocol.modifierColumnCC(ROW_STEPS_HI)) {
            onViewButtonPress(VIEW_BUTTON_PAGE_0, isOn);
            return;
        }
        if (cc == LpProtocol.modifierColumnCC(ROW_STEPS_LO)) {
            onViewButtonPress(VIEW_BUTTON_PAGE_1, isOn);
            return;
        }
        if (cc == LpProtocol.modifierColumnCC(ROW_BLACK_KEYS)) {
            onPianoModeButtonPress(PIANO_MODE_BUTTON_FIRST, isOn);
            return;
        }
        if (cc == LpProtocol.modifierColumnCC(ROW_WHITE_KEYS)) {
            onPianoModeButtonPress(PIANO_MODE_BUTTON_SECOND, isOn);
            return;
        }
        if (!isOn) {
            return;
        }
        if (cc == LpProtocol.CC_OCTAVE_UP) {
            shiftOctave(1);
        } else if (cc == LpProtocol.CC_OCTAVE_DOWN) {
            shiftOctave(-1);
        } else if (cc == LpProtocol.modifierColumnCC(ROW_MOD_TOP)) {
            setModifierMode(MODE_NOTE_OPS);
        } else if (cc == LpProtocol.modifierColumnCC(ROW_MOD_MID)) {
            setModifierMode(MODE_NOTE_EXPRESSIONS);
        } else if (cc == LpProtocol.modifierColumnCC(ROW_MOD_BOTTOM)) {
            setModifierMode(MODE_RESERVED);
        }
    }

    /**
     * A single press (from no view button held) fixes the view to that button's page, turning
     * off auto-follow if it was on. Holding both down at once - a chord, in either order - turns
     * auto-follow on instead. Releases don't change anything by themselves; only a fresh press
     * does, so letting go of one button after a chord doesn't fall back to "fixed" on the other.
     */
    private void onViewButtonPress(final int button, final boolean isOn) {
        if (!isOn) {
            heldViewButtons.remove(button);
            return;
        }
        heldViewButtons.add(button);
        if (heldViewButtons.size() == 2) {
            viewMode = VIEW_MODE_AUTO;
        } else if (heldViewButtons.size() == 1) {
            viewMode = VIEW_MODE_FIXED;
            if (button == VIEW_BUTTON_PAGE_1) {
                clip.getPlayStop().set(LONG_CLIP_LENGTH_BEATS);
                clip.getLoopLength().set(LONG_CLIP_LENGTH_BEATS);
            }
            setViewOffset(button == VIEW_BUTTON_PAGE_0 ? 0 : STEP_COUNT);
        }
        getHost().requestFlush();
    }

    /**
     * Same press/chord pattern as onViewButtonPress: a single press (from neither held) fixes the
     * piano mode to that button's mode; holding both down at once (a chord, either order) switches
     * to PIANO_MODE_PLAY_ALONG instead.
     */
    private void onPianoModeButtonPress(final int button, final boolean isOn) {
        if (!isOn) {
            heldPianoModeButtons.remove(button);
            return;
        }
        heldPianoModeButtons.add(button);
        if (heldPianoModeButtons.size() == 2) {
            setPianoMode(PIANO_MODE_PLAY_ALONG);
        } else if (heldPianoModeButtons.size() == 1) {
            setPianoMode(button == PIANO_MODE_BUTTON_FIRST ? PIANO_MODE_HOLD : PIANO_MODE_LIVE_STEP);
        }
    }

    private void setPianoMode(final int mode) {
        if (mode == pianoMode) {
            return;
        }
        if (pianoMode == PIANO_MODE_PLAY_ALONG) {
            // Stop anything left sounding from play-along mode before leaving it, so a key that's
            // still physically held when the mode changes doesn't turn into a stuck note.
            for (final int y : heldPianoKeys) {
                previewNoteInput.sendRawMidiEvent(0x80, y, 0);
            }
        }
        pianoMode = mode;
        getHost().requestFlush();
    }

    private void setModifierMode(final int mode) {
        modifierMode = mode;
        heldTimbreCols.clear();
        getHost().requestFlush();
    }

    private void shiftOctave(final int delta) {
        octaveOffset = Math.max(MIN_OCTAVE_OFFSET, Math.min(MAX_OCTAVE_OFFSET, octaveOffset + delta));
        getHost().requestFlush();
    }

    private void onClipPad(final int col, final boolean isOn) {
        if (isOn) {
            if (heldClipPads.isEmpty() && displayStep < 0) {
                bulkRecurrenceMask = 0xFF;
                bulkRecurrenceEnabled = false;
            }
            heldClipPads.add(col);
            final ClipLauncherSlot slot = slotBank.getItemAt(col);
            if (slot.hasContent().get()) {
                slot.select();
                slot.launch();
            } else {
                final int newClipLength = viewOffset == STEP_COUNT ? LONG_CLIP_LENGTH_BEATS : NEW_CLIP_LENGTH_BEATS;
                slot.createEmptyClip(newClipLength);
                slot.select();
                getHost().scheduleTask(() -> clip.setStepSize(STEP_SIZE_BEATS), 50);
            }
        } else {
            heldClipPads.remove(col);
        }
        getHost().requestFlush();
    }

    private void onStepPad(final int x, final boolean isOn) {
        if (isOn) {
            heldSteps.add(x);
            displayStep = x;
            editedWhileHeld.remove(x);
            stepPressedAt.put(x, System.currentTimeMillis());
            if (pianoMode == PIANO_MODE_HOLD && !heldPianoKeys.isEmpty()) {
                for (final int y : heldPianoKeys) {
                    clip.toggleStep(NOTE_CHANNEL, x, y, INSERT_VELOCITY);
                }
                editedWhileHeld.add(x);
            }
        } else {
            heldSteps.remove(x);
            final Long pressedAt = stepPressedAt.remove(x);
            final boolean wasEdited = editedWhileHeld.remove(x);
            final boolean wasTap = pressedAt != null
                && System.currentTimeMillis() - pressedAt < TAP_THRESHOLD_MS;
            if (wasTap && !wasEdited) {
                if (!occupiedKeysPerStep[x].isEmpty()) {
                    clip.clearStepsAtX(NOTE_CHANNEL, x);
                } else {
                    clip.toggleStep(NOTE_CHANNEL, x, lastPitch, INSERT_VELOCITY);
                }
            }
            if (displayStep == x) {
                displayStep = heldSteps.isEmpty() ? -1 : heldSteps.iterator().next();
            }
        }
        getHost().requestFlush();
    }

    private void onRecurrencePad(final int col) {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final int newMask;
        final boolean enable;
        if (displayStep >= 0) {
            final int lowestY = Collections.min(occupiedKeysPerStep[displayStep]);
            final NoteStep reference = clip.getStep(NOTE_CHANNEL, displayStep, lowestY);
            final int currentMask = reference.isRecurrenceEnabled() ? reference.recurrenceMask() : 0xFF;
            newMask = currentMask ^ (1 << col);
            enable = newMask != 0xFF;
        } else {
            bulkRecurrenceMask ^= (1 << col);
            newMask = bulkRecurrenceMask;
            enable = newMask != 0xFF;
            bulkRecurrenceEnabled = enable;
        }
        for (final int[] cell : targets) {
            final NoteStep note = clip.getStep(NOTE_CHANNEL, cell[0], cell[1]);
            note.setRecurrence(RECURRENCE_LENGTH, newMask);
            note.setIsRecurrenceEnabled(enable);
        }
        markEdited();
        getHost().requestFlush();
    }

    private void onChancePad(final int col) {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final double newChance = (col + 1) / (double) MODIFIER_LEVELS;
        final boolean enable = newChance < 1.0;
        for (final int[] cell : targets) {
            final NoteStep note = clip.getStep(NOTE_CHANNEL, cell[0], cell[1]);
            note.setChance(newChance);
            note.setIsChanceEnabled(enable);
        }
        markEdited();
        getHost().requestFlush();
    }

    private void onLengthPad(final int col) {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final double newLength = (col + 1) * STEP_SIZE_BEATS;
        for (final int[] cell : targets) {
            clip.getStep(NOTE_CHANNEL, cell[0], cell[1]).setDuration(newLength);
        }
        markEdited();
        getHost().requestFlush();
    }

    private void onTimbrePad(final int col, final boolean isOn) {
        if (isOn) {
            heldTimbreCols.add(col);
        } else {
            heldTimbreCols.remove(col);
        }
        applyTimbreFromHeldCols();
    }

    /**
     * Applies a timbre value once the held columns in row 2 form a valid gesture: any single
     * held column (its own value, per {@link #timbreSingleValue}), or two adjacent held columns
     * (the midpoint of their two values - e.g. columns 3 and 4 together set 0). Any other
     * combination is ignored, so a user building up to a chord doesn't apply a stray value along
     * the way.
     */
    private void applyTimbreFromHeldCols() {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final Double uiValue = resolveTimbreValue();
        if (uiValue == null) {
            return;
        }
        final double newTimbre = uiValue / 100.0;
        for (final int[] cell : targets) {
            clip.getStep(NOTE_CHANNEL, cell[0], cell[1]).setTimbre(newTimbre);
        }
        markEdited();
        getHost().requestFlush();
    }

    /** Null if the currently-held columns in row 2 don't form a valid timbre gesture. */
    private Double resolveTimbreValue() {
        if (heldTimbreCols.size() == 1) {
            return timbreSingleValue(heldTimbreCols.iterator().next());
        }
        if (heldTimbreCols.size() == 2) {
            final int[] cols = heldTimbreCols.stream().mapToInt(Integer::intValue).sorted().toArray();
            if (cols[1] - cols[0] == 1) {
                return (timbreSingleValue(cols[0]) + timbreSingleValue(cols[1])) / 2.0;
            }
        }
        return null;
    }

    private void onPressurePad(final int col) {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final double newPressure = (col + 1) / (double) MODIFIER_LEVELS;
        for (final int[] cell : targets) {
            clip.getStep(NOTE_CHANNEL, cell[0], cell[1]).setPressure(newPressure);
        }
        markEdited();
        getHost().requestFlush();
    }

    private void onVelocityPad(final int col) {
        final List<int[]> targets = editTargetCells();
        if (targets.isEmpty()) {
            return;
        }
        final double newVelocity = (col + 1) / (double) MODIFIER_LEVELS;
        for (final int[] cell : targets) {
            clip.getStep(NOTE_CHANNEL, cell[0], cell[1]).setVelocity(newVelocity);
        }
        markEdited();
        getHost().requestFlush();
    }

    private void onPianoPad(final int offset, final boolean isOn) {
        final int y = pitchForOffset(offset);
        // heldPianoKeys tracks physical press state for LED feedback in every mode, regardless of
        // what that press actually does functionally below.
        if (isOn) {
            heldPianoKeys.add(y);
            lastPitch = y;
        } else {
            heldPianoKeys.remove(y);
        }

        if (pianoMode == PIANO_MODE_HOLD) {
            if (isOn) {
                for (final int x : heldSteps) {
                    clip.toggleStep(NOTE_CHANNEL, x, y, INSERT_VELOCITY);
                    editedWhileHeld.add(x);
                }
            }
        } else if (pianoMode == PIANO_MODE_LIVE_STEP) {
            if (isOn) {
                recordNoteAtPlayhead(y);
            }
        } else { // PIANO_MODE_PLAY_ALONG
            previewNoteInput.sendRawMidiEvent(isOn ? 0x90 : 0x80, y, isOn ? INSERT_VELOCITY : 0);
        }

        getHost().requestFlush();
    }

    /**
     * Toggles a note at the pitch y on whatever step the playhead is currently on. If the playhead
     * isn't in the currently-shown page, jumps the view to the page it's actually in first, so the
     * newly (or no longer) placed note is visible where it landed rather than silently written
     * off-screen. Does nothing if the clip isn't playing - there's no "current step" to place at.
     */
    private void recordNoteAtPlayhead(final int y) {
        if (playingStep < 0) {
            return;
        }
        if (playingStep < viewOffset || playingStep >= viewOffset + STEP_COUNT) {
            setViewOffset(playingStep >= STEP_COUNT ? STEP_COUNT : 0);
        }
        clip.toggleStep(NOTE_CHANNEL, playingStep - viewOffset, y, INSERT_VELOCITY);
    }
}
