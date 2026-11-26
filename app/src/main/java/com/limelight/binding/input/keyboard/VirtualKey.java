package com.limelight.binding.input.keyboard;

import com.limelight.R;

/** Windows virtual keys sent directly to Sunshine, bypassing Android system shortcuts. */
public enum VirtualKey {
    ESC(0x1B, "Esc", R.string.keyboard_key_escape),
    TAB(0x09, "Tab", R.string.keyboard_key_tab),
    SHIFT(0xA1, "Shift", R.string.keyboard_key_shift, 1),
    CTRL(0xA3, "Ctrl", R.string.keyboard_key_ctrl, 2),
    WIN(0x5B, "⊞", R.string.keyboard_key_win, 8),
    ALT(0xA4, "Alt", R.string.keyboard_key_alt, 4),
    CAPS(0x14, "Caps", R.string.keyboard_key_caps),
    LEFT(0x25, "←", R.string.keyboard_key_left),
    UP(0x26, "↑", R.string.keyboard_key_up),
    DOWN(0x28, "↓", R.string.keyboard_key_down),
    RIGHT(0x27, "→", R.string.keyboard_key_right),
    F1(0x70, "F1", R.string.keyboard_key_f1), F2(0x71, "F2", R.string.keyboard_key_f2),
    F3(0x72, "F3", R.string.keyboard_key_f3), F4(0x73, "F4", R.string.keyboard_key_f4),
    F5(0x74, "F5", R.string.keyboard_key_f5), F6(0x75, "F6", R.string.keyboard_key_f6),
    F7(0x76, "F7", R.string.keyboard_key_f7), F8(0x77, "F8", R.string.keyboard_key_f8),
    F9(0x78, "F9", R.string.keyboard_key_f9), F10(0x79, "F10", R.string.keyboard_key_f10),
    F11(0x7A, "F11", R.string.keyboard_key_f11), F12(0x7B, "F12", R.string.keyboard_key_f12),
    INSERT(0x2D, "Ins", R.string.keyboard_key_insert), DELETE(0x2E, "Del", R.string.keyboard_key_delete),
    HOME(0x24, "Home", R.string.keyboard_key_home), END(0x23, "End", R.string.keyboard_key_end),
    PAGE_UP(0x21, "PgUp", R.string.keyboard_key_page_up), PAGE_DOWN(0x22, "PgDn", R.string.keyboard_key_page_down),
    PRINT_SCREEN(0x2C, "PrtSc", R.string.keyboard_key_print_screen),
    SPACE(0x20, "Space", R.string.keyboard_key_space), BACKSPACE(0x08, "⌫", R.string.keyboard_key_backspace),
    ENTER(0x0D, "↵", R.string.keyboard_key_enter);

    public final int vk;
    public final String label;
    public final int description;
    public final int modifier;

    VirtualKey(int vk, String label, int description) { this(vk, label, description, 0); }
    VirtualKey(int vk, String label, int description, int modifier) {
        this.vk = vk;
        this.label = label;
        this.description = description;
        this.modifier = modifier;
    }
    public short wireCode() { return (short) (0x8000 | vk); }

    public static boolean isModifier(int vk) {
        return vk == 0x10 || vk == 0x11 || vk == 0x12 || vk == 0x5B || vk == 0x5C ||
                (vk >= 0xA0 && vk <= 0xA5);
    }
}
