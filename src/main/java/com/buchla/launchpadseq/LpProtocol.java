package com.buchla.launchpadseq;

/**
 * MIDI/SysEx details for the Novation Launchpad Mini [MK3], per Novation's Programmer's Reference Manual.
 * Grid pad note numbers run 11-88 (bottom-left to top-right, +1 per column, +10 per row).
 * Top row round buttons are CC 91-98 (Up, Down, Left, Right, Session, Drums, Keys, User).
 * A 9th "Modifier Column" runs down the right edge, one button per grid row, CC 19-89
 * (row 0/bottom = CC19, +10 per row up to row 7/top = CC89), plus CC99 at the top-right
 * corner beside the CC91-98 row.
 */
final class LpProtocol {

    private LpProtocol() {
    }

    static final int SYSEX_DEVICE_ID = 0x0D;

    static final int CC_OCTAVE_UP = 91;
    static final int CC_OCTAVE_DOWN = 92;

    static final int NOTE_STATUS = 0x90;
    static final int CC_STATUS = 0xB0;

    /** Grid pad note number for a pad at (rowFromBottom, col), both 0-7. */
    static int gridNote(int rowFromBottom, int col) {
        return 11 + col + 10 * rowFromBottom;
    }

    /** Modifier Column CC number for the given grid row (0-7). */
    static int modifierColumnCC(int rowFromBottom) {
        return 19 + 10 * rowFromBottom;
    }

    static byte[] dawMode(boolean enable) {
        return new byte[]{
            (byte) 0xF0, 0x00, 0x20, 0x29, 0x02, (byte) SYSEX_DEVICE_ID,
            0x10, (byte) (enable ? 1 : 0),
            (byte) 0xF7
        };
    }

    static byte[] selectLayout(int layout) {
        return new byte[]{
            (byte) 0xF0, 0x00, 0x20, 0x29, 0x02, (byte) SYSEX_DEVICE_ID,
            0x00, (byte) layout,
            (byte) 0xF7
        };
    }
}
