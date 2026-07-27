package com.buchla.launchpadseq;

import com.bitwig.extension.controller.ControllerExtension;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ClipLauncherSlotBank;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.CursorTrack;
import com.bitwig.extension.controller.api.MidiIn;
import com.bitwig.extension.controller.api.MidiOut;
import com.bitwig.extension.controller.api.NoteStep;
import com.bitwig.extension.controller.api.PinnableCursorClip;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 *   rows 2, 1, 0    - modifier rows for the held step; what they edit depends on the current
 *                     Modifier Column mode (see below)
 *
 * The 9th column past the edge of the 8x8 grid ("Modifier Column", CC 19-89, +10 per row -
 * see LpProtocol) is a set of radio buttons that pick what rows 2-0 do. Only the bottom three
 * are used so far, one per mode:
 *   - Note Ops (row 2 button, CC39, the default mode): row 2 = recurrence pattern (8 cycles),
 *     row 1 = chance as a 0-8 bar in 12.5% increments, row 0 = note length in 1/16 increments
 *     up to 8/16.
 *   - Note Expressions (row 1 button, CC29): row 2 = timbre (-100..100, bipolar - see
 *     onTimbrePad), row 1 = pressure as a 0-8 bar in 12.5% increments, row 0 = velocity, same.
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
 * Rows 2-0 reflect and edit the note(s) on whichever step is currently held down (rows 6/5);
 * if a step holds more than one note, they show/edit the lowest-pitched one, and edits apply
 * to every note in that step. These rows are blank when no step is held or the held step is
 * empty - there's nothing to show or edit until a note exists.
 */
public class LaunchpadSeqExtension extends ControllerExtension {

    private static final int STEP_COUNT = 16;
    private static final int GRID_HEIGHT = 128;
    private static final double STEP_SIZE_BEATS = 0.25; // sixteenth notes: 16 steps = 1 bar of 4/4
    private static final int NEW_CLIP_LENGTH_BEATS = 4;
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

    private static final int RECURRENCE_LENGTH = 8;
    private static final int MODIFIER_LEVELS = 8; // columns per bar-style modifier row
    private static final long TAP_THRESHOLD_MS = 300; // below this, a step press+release is a tap

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

    private CursorTrack cursorTrack;
    private ClipLauncherSlotBank slotBank;
    private int bankSize;
    private PinnableCursorClip clip;

    private int octaveOffset = 0;
    private int lastPitch = BASE_NOTE;
    private int playingStep = -1;
    // Which of the 8 recurrence cycles is currently playing, inferred by counting how many
    // times playingStep() has wrapped back to the start of the clip's loop (there's no direct
    // API for "current recurrence cycle"). Resets to 0 whenever playback stops.
    private int recurrenceCycle = 0;
    private int displayStep = -1; // step currently held, whose modifiers rows 2-0 show/edit; -1 = none
    private int modifierMode = MODE_NOTE_OPS; // which Modifier Column radio button is selected

    private final Set<Integer> heldPianoKeys = new HashSet<>();
    private final Set<Integer> heldSteps = new HashSet<>();
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
            if (v < 0) {
                recurrenceCycle = 0;
            } else if (playingStep >= 0 && v < playingStep) {
                recurrenceCycle = (recurrenceCycle + 1) % RECURRENCE_LENGTH;
            }
            playingStep = v;
            host.requestFlush();
        });
        clip.color().addValueObserver((r, g, b) -> host.requestFlush());

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
        sendColor(LpProtocol.CC_OCTAVE_UP, CONTROL_IDLE_COLOR);
        sendColor(LpProtocol.CC_OCTAVE_DOWN, CONTROL_IDLE_COLOR);
        sendColor(LpProtocol.modifierColumnCC(ROW_MOD_TOP), modeButtonColor(MODE_NOTE_OPS));
        sendColor(LpProtocol.modifierColumnCC(ROW_MOD_MID), modeButtonColor(MODE_NOTE_EXPRESSIONS));
        sendColor(LpProtocol.modifierColumnCC(ROW_MOD_BOTTOM), modeButtonColor(MODE_RESERVED));
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
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        // No pattern applied yet (the default) shows as fully off, not as an all-on pattern -
        // only an actually-applied mask lights anything here.
        final boolean on = note.isRecurrenceEnabled() && ((note.recurrenceMask() >> col) & 1) != 0;
        final boolean playhead = playingStep >= 0 && col == recurrenceCycle;
        if (playhead) {
            return on ? ColorLookup.PALE_YELLOW : ColorLookup.WHITE;
        }
        return on ? RECURRENCE_COLOR : ColorLookup.OFF;
    }

    private int chanceColor(final int col) {
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        final double chance = note.isChanceEnabled() ? note.chance() : 1.0;
        final int level = (int) Math.round(chance * MODIFIER_LEVELS);
        return col < level ? CHANCE_COLOR : ColorLookup.OFF;
    }

    private int lengthColor(final int col) {
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(note.duration() / STEP_SIZE_BEATS);
        return col < level ? LENGTH_COLOR : ColorLookup.OFF;
    }

    private int timbreColor(final int col) {
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        return timbreIndexLitColumns(timbreIndex(note.timbre()), col) ? TIMBRE_COLOR : ColorLookup.OFF;
    }

    /** Maps timbre's -1..1 API range to a 0-8 chord index: 0 and 8 are the single-pad extremes. */
    private static int timbreIndex(final double timbre) {
        final int index = (int) Math.round((timbre + 1.0) / 2.0 * MODIFIER_LEVELS);
        return Math.max(0, Math.min(MODIFIER_LEVELS, index));
    }

    /** Index 0 lights only column 0, index 8 only column 7, others light columns (index-1, index). */
    private static boolean timbreIndexLitColumns(final int index, final int col) {
        if (index == 0) {
            return col == 0;
        }
        if (index == MODIFIER_LEVELS) {
            return col == 7;
        }
        return col == index - 1 || col == index;
    }

    private int pressureColor(final int col) {
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(note.pressure() * MODIFIER_LEVELS);
        return col < level ? PRESSURE_COLOR : ColorLookup.OFF;
    }

    private int velocityColor(final int col) {
        final NoteStep note = displayedModifierNote();
        if (note == null) {
            return ColorLookup.OFF;
        }
        final int level = (int) Math.round(note.velocity() * MODIFIER_LEVELS);
        return col < level ? VELOCITY_COLOR : ColorLookup.OFF;
    }

    private void sendColor(final int index, final int paletteColor) {
        if (lastSentColor[index] == paletteColor) {
            return;
        }
        lastSentColor[index] = paletteColor;
        midiOut.sendMidi(LpProtocol.NOTE_STATUS, index, paletteColor);
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
        if (x == playingStep) {
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
            if (data2 > 0) {
                handleControl(data1);
            }
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
                if (isOn && col < bankSize) {
                    onClipPad(col);
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

    private void handleControl(final int cc) {
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

    private void setModifierMode(final int mode) {
        modifierMode = mode;
        heldTimbreCols.clear();
        getHost().requestFlush();
    }

    private void shiftOctave(final int delta) {
        octaveOffset = Math.max(MIN_OCTAVE_OFFSET, Math.min(MAX_OCTAVE_OFFSET, octaveOffset + delta));
        getHost().requestFlush();
    }

    private void onClipPad(final int col) {
        final ClipLauncherSlot slot = slotBank.getItemAt(col);
        if (slot.hasContent().get()) {
            slot.select();
            slot.launch();
        } else {
            slot.createEmptyClip(NEW_CLIP_LENGTH_BEATS);
            slot.select();
            getHost().scheduleTask(() -> clip.setStepSize(STEP_SIZE_BEATS), 50);
        }
    }

    private void onStepPad(final int x, final boolean isOn) {
        if (isOn) {
            heldSteps.add(x);
            displayStep = x;
            editedWhileHeld.remove(x);
            stepPressedAt.put(x, System.currentTimeMillis());
            if (!heldPianoKeys.isEmpty()) {
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
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final int lowestY = Collections.min(occupiedKeysPerStep[displayStep]);
        final NoteStep reference = clip.getStep(NOTE_CHANNEL, displayStep, lowestY);
        final int currentMask = reference.isRecurrenceEnabled() ? reference.recurrenceMask() : 0xFF;
        final int newMask = currentMask ^ (1 << col);
        final boolean enable = newMask != 0xFF;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            final NoteStep note = clip.getStep(NOTE_CHANNEL, displayStep, y);
            note.setRecurrence(RECURRENCE_LENGTH, newMask);
            note.setIsRecurrenceEnabled(enable);
        }
        editedWhileHeld.add(displayStep);
        getHost().requestFlush();
    }

    private void onChancePad(final int col) {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final double newChance = (col + 1) / (double) MODIFIER_LEVELS;
        final boolean enable = newChance < 1.0;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            final NoteStep note = clip.getStep(NOTE_CHANNEL, displayStep, y);
            note.setChance(newChance);
            note.setIsChanceEnabled(enable);
        }
        editedWhileHeld.add(displayStep);
        getHost().requestFlush();
    }

    private void onLengthPad(final int col) {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final double newLength = (col + 1) * STEP_SIZE_BEATS;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            clip.getStep(NOTE_CHANNEL, displayStep, y).setDuration(newLength);
        }
        editedWhileHeld.add(displayStep);
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
     * Applies a timbre value once the held columns in row 2 form a valid gesture: column 0 or
     * column 7 alone (the -100/+100 extremes), or two adjacent held columns (a "chord" - e.g.
     * columns 3 and 4 together set 0, the centre). Any other combination is ignored, so a user
     * building up to a chord doesn't apply a stray value along the way.
     */
    private void applyTimbreFromHeldCols() {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final Integer index = resolveTimbreIndex();
        if (index == null) {
            return;
        }
        final double newTimbre = (index / (double) MODIFIER_LEVELS) * 2.0 - 1.0;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            clip.getStep(NOTE_CHANNEL, displayStep, y).setTimbre(newTimbre);
        }
        editedWhileHeld.add(displayStep);
        getHost().requestFlush();
    }

    /** Null if the currently-held columns in row 2 don't form a valid timbre gesture. */
    private Integer resolveTimbreIndex() {
        if (heldTimbreCols.size() == 1) {
            final int col = heldTimbreCols.iterator().next();
            if (col == 0) {
                return 0;
            }
            if (col == 7) {
                return MODIFIER_LEVELS;
            }
            return null;
        }
        if (heldTimbreCols.size() == 2) {
            final int[] cols = heldTimbreCols.stream().mapToInt(Integer::intValue).sorted().toArray();
            if (cols[1] - cols[0] == 1) {
                return cols[1];
            }
        }
        return null;
    }

    private void onPressurePad(final int col) {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final double newPressure = (col + 1) / (double) MODIFIER_LEVELS;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            clip.getStep(NOTE_CHANNEL, displayStep, y).setPressure(newPressure);
        }
        editedWhileHeld.add(displayStep);
        getHost().requestFlush();
    }

    private void onVelocityPad(final int col) {
        if (displayStep < 0 || occupiedKeysPerStep[displayStep].isEmpty()) {
            return;
        }
        final double newVelocity = (col + 1) / (double) MODIFIER_LEVELS;
        for (final int y : occupiedKeysPerStep[displayStep]) {
            clip.getStep(NOTE_CHANNEL, displayStep, y).setVelocity(newVelocity);
        }
        editedWhileHeld.add(displayStep);
        getHost().requestFlush();
    }

    private void onPianoPad(final int offset, final boolean isOn) {
        final int y = pitchForOffset(offset);
        if (isOn) {
            heldPianoKeys.add(y);
            lastPitch = y;
            for (final int x : heldSteps) {
                clip.toggleStep(NOTE_CHANNEL, x, y, INSERT_VELOCITY);
                editedWhileHeld.add(x);
            }
        } else {
            heldPianoKeys.remove(y);
        }
        getHost().requestFlush();
    }
}
