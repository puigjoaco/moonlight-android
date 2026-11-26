package com.limelight.binding.input.keyboard;

/** Fit the complete non-scrolling strip; preserve 48dp where possible and shrink equally otherwise. */
public final class KeyboardLayout {
    private KeyboardLayout() { }
    public static int cellWidth(int availablePixels, int cells, int dividerPixels, int preferredPixels) {
        return Math.max(0, Math.min(preferredPixels, Math.max(0, availablePixels - dividerPixels) / cells));
    }
}
