package com.buchla.launchpadseq;

/**
 * Maps an arbitrary RGB colour to the nearest entry in the Launchpad Mini [MK3]'s fixed
 * 128-colour palette (used because Session/DAW-mode pads can only be coloured by a single
 * palette-index velocity, not arbitrary RGB - that's only available in Programmer mode).
 */
final class ColorLookup {

    private ColorLookup() {
    }

    static final int OFF = 0;
    static final int WHITE = 3;
    static final int TURQUOISE = 37;
    static final int DIM_GREY = 1;
    static final int YELLOW = 13;
    static final int PALE_YELLOW = 12; // yellow lightened towards white
    static final int ORANGE = 9;
    static final int RED = 5;
    static final int BLUE = 41;
    static final int PURPLE = 49;
    static final int GREEN = 21;

    /** @param r red 0-1, g green 0-1, b blue 0-1 */
    static int nearestPaletteIndex(final float r, final float g, final float b) {
        final int rv = (int) Math.floor(r * 255);
        final int gv = (int) Math.floor(g * 255);
        final int bv = (int) Math.floor(b * 255);

        if (rv < 10 && gv < 10 && bv < 10) {
            return OFF;
        }
        if (rv > 230 && gv > 230 && bv > 230) {
            return WHITE;
        }
        if (rv == gv && bv == gv) {
            return (rv >> 4) > 7 ? 2 : DIM_GREY;
        }

        final Hsb hsb = rgbToHsb(rv, gv, bv);
        int hueInd = hsb.hue > 6 ? hsb.hue - 1 : hsb.hue;
        hueInd = Math.min(13, hueInd);
        int color = 5 + hueInd * 4 + 1;
        if (hsb.sat < 8) {
            color -= 2;
        } else if (hsb.bright <= 8) {
            color += 2;
        }
        return adjust(color);
    }

    private static int adjust(final int c) {
        final int rst = (c - 2) % 4;
        return rst == 0 ? c - 1 : c;
    }

    private static Hsb rgbToHsb(final float rv, final float gv, final float bv) {
        final float max = Math.max(Math.max(rv, gv), bv);
        final float min = Math.min(Math.min(rv, gv), bv);
        final int bright = (int) max;
        if (bright == 0) {
            return new Hsb(0, 0, 0);
        }
        final int sat = (int) (255 * (max - min) / bright);
        if (sat == 0) {
            return new Hsb(0, 0, 0);
        }
        float hue;
        if (max == rv) {
            hue = 43 * (gv - bv) / (max - min);
        } else if (max == gv) {
            hue = 85 + 43 * (bv - rv) / (max - min);
        } else {
            hue = 171 + 43 * (rv - gv) / (max - min);
        }
        if (hue < 0) {
            hue = 256 + hue;
        }
        return new Hsb((int) Math.floor(hue / 16.0 + 0.3), sat >> 4, bright >> 4);
    }

    private static final class Hsb {
        final int hue;
        final int sat;
        final int bright;

        Hsb(final int hue, final int sat, final int bright) {
            this.hue = hue;
            this.sat = sat;
            this.bright = bright;
        }
    }
}
