package com.limelight.binding.input.keyboard;

import org.junit.Test;
import android.view.KeyEvent;
import com.limelight.binding.input.KeyboardTranslator;
import static org.mockito.Mockito.*;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;
import static com.limelight.binding.input.keyboard.VirtualKey.*;

public class VirtualKeyTest {
    @Test public void everyKeyUsesExpectedWindowsVkAndProtocolPrefix() {
        VirtualKey[] keys = {ESC,TAB,SHIFT,CTRL,WIN,ALT,CAPS,LEFT,UP,DOWN,RIGHT,
            F1,F2,F3,F4,F5,F6,F7,F8,F9,F10,F11,F12,INSERT,DELETE,HOME,END,PAGE_UP,PAGE_DOWN,
            PRINT_SCREEN,SPACE,BACKSPACE,ENTER};
        int[] expected = {0x1B,0x09,0xA1,0xA3,0x5B,0xA4,0x14,0x25,0x26,0x28,0x27,
            0x70,0x71,0x72,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7A,0x7B,
            0x2D,0x2E,0x24,0x23,0x21,0x22,0x2C,0x20,0x08,0x0D};
        assertEquals(VirtualKey.values().length, keys.length);
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < keys.length; i++) {
            assertEquals(keys[i].name(), expected[i], keys[i].vk);
            assertEquals(0x8000 | expected[i], keys[i].wireCode() & 0xFFFF);
            assertTrue(unique.add(keys[i].vk));
        }
    }
    @Test public void physicalPrintScreenAndRightModifiersMatchTheOverlayWireCodes() {
        KeyboardTranslator translator = mock(KeyboardTranslator.class, CALLS_REAL_METHODS);
        assertEquals(PRINT_SCREEN.wireCode(), translator.translate(KeyEvent.KEYCODE_SYSRQ, -1));
        assertEquals(SHIFT.wireCode(), translator.translate(KeyEvent.KEYCODE_SHIFT_RIGHT, -1));
        assertEquals(CTRL.wireCode(), translator.translate(KeyEvent.KEYCODE_CTRL_RIGHT, -1));
    }
    @Test public void modifierBitsMatchProtocolAndRecognizePhysicalVariants() {
        assertEquals(1, SHIFT.modifier); assertEquals(2, CTRL.modifier);
        assertEquals(4, ALT.modifier); assertEquals(8, WIN.modifier);
        for (int key : new int[]{0x10,0x11,0x12,0x5B,0x5C,0xA0,0xA1,0xA2,0xA3,0xA4,0xA5}) assertTrue(VirtualKey.isModifier(key));
        for (VirtualKey key : VirtualKey.values()) assertEquals(key.modifier != 0, VirtualKey.isModifier(key.vk));
    }
}
