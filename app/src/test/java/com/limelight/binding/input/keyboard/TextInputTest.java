package com.limelight.binding.input.keyboard;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TextInputTest {
    private final List<String> events = new ArrayList<>();
    private final TextInput.Sink sink = new TextInput.Sink() {
        @Override public void key(int vk, boolean down) { events.add(Integer.toHexString(vk)+(down ? "+" : "-")); }
        @Override public void unicode(String text) { events.add("unicode:"+text); }
    };
    @Test public void shellCommandUsesRealUsKeysWithoutAnImplicitEnter() {
        TextInput.send("ls -la",sink);
        assertEquals(List.of("4c+","4c-","53+","53-","20+","20-","bd+","bd-","4c+","4c-","41+","41-"),events);
    }
    @Test public void allPrintableAsciiHasARealKeyMapping() {
        for (char ch=32;ch<=126;ch++) assertNotNull("Missing "+ch,TextInput.ascii(ch));
        String chars="@#*/?"; int[] keys={0x32,0x33,0x38,0xBF,0xBF}; boolean[] shift={true,true,true,false,true};
        for(int i=0;i<chars.length();i++) {
            TextInput.Stroke stroke=TextInput.ascii(chars.charAt(i));
            assertEquals(keys[i],stroke.vk); assertEquals(shift[i],stroke.shift);
        }
    }
    @Test public void uppercaseAndPunctuationUseBalancedShiftAroundACompleteStroke() {
        TextInput.send("A@",sink);
        assertEquals(List.of("a0+","41+","41-","a0-","a0+","32+","32-","a0-"),events);
    }
    @Test public void onlyNonAsciiUsesUnicodeAndSurrogatePairsStayTogether() {
        TextInput.send("año ¿qué? ¡sí! €😀",sink);
        assertEquals(List.of("unicode:ñ","unicode:¿","unicode:é","unicode:¡","unicode:í","unicode:€😀"),
                events.stream().filter(x->x.startsWith("unicode:")).toList());
        assertFalse(events.contains("d+"));
    }
    @Test public void explicitNewlinesAndTabsAreTheOnlyControlKeysSent() {
        TextInput.send("\r\n\n\t\0",sink);
        assertEquals(List.of("d+","d-","d+","d-","9+","9-"),events);
    }
}
