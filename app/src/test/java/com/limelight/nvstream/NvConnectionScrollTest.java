package com.limelight.nvstream;

import com.limelight.binding.input.keyboard.ModifierState;
import com.limelight.binding.input.keyboard.ReliableInputSender;
import com.limelight.binding.input.keyboard.VirtualKey;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NvConnectionScrollTest {
    @Test public void zeroVerticalScrollPreservesOneShotForTheRealHorizontalScroll() throws Exception {
        NvConnection connection = mock(NvConnection.class, CALLS_REAL_METHODS);
        NvConnection.InputObserver observer = mock(NvConnection.InputObserver.class);
        ReliableInputSender sender = mock(ReliableInputSender.class);
        Field field = NvConnection.class.getDeclaredField("inputSender");
        field.setAccessible(true);
        field.set(connection, sender);
        List<String> events = new ArrayList<>();
        ModifierState modifiers = new ModifierState(new ModifierState.Listener() {
            public void send(VirtualKey key, boolean down) { events.add(key.name() + (down ? "+" : "-")); }
            public void changed() { }
        });
        modifiers.press(VirtualKey.CTRL); modifiers.release(VirtualKey.CTRL, false);
        doAnswer(call -> { modifiers.nonModifierDown(); return null; }).when(observer).nonModifierDown();
        doAnswer(call -> { modifiers.nonModifierUp(); return null; }).when(observer).nonModifierUp();
        when(sender.enqueue(any())).thenAnswer(call -> {
            assertEquals(2, modifiers.flags());
            events.add("horizontal-scroll");
            return true; // Do not invoke JNI in a JVM test.
        });
        connection.setInputObserver(observer);
        connection.sendMouseScroll((byte) 0);
        connection.sendMouseHScroll((byte) 0);
        connection.sendMouseHighResScroll((short) 0);
        connection.sendMouseHighResHScroll((short) 0);
        verifyNoInteractions(observer, sender);
        assertEquals(ModifierState.State.ONE_SHOT, modifiers.state(VirtualKey.CTRL));
        connection.sendMouseHighResHScroll((short) 120);
        assertEquals(List.of("CTRL+", "horizontal-scroll", "CTRL-"), events);
        assertEquals(ModifierState.State.OFF, modifiers.state(VirtualKey.CTRL));
    }
}
