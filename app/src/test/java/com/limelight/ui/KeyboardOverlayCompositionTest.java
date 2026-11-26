package com.limelight.ui;

import android.content.Context;
import android.text.Editable;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputMethodManager;
import com.limelight.binding.input.keyboard.ImeTextBuffer;
import com.limelight.binding.input.keyboard.VirtualKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class KeyboardOverlayCompositionTest {
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    @Test public void stripAndPanelKeysCommitHelloOnceBeforeTheirStrokesAndResetTheIme() throws Exception {
        // Both the strip and panel route Tab/Enter/arrows through the same stroke implementation.
        for (VirtualKey key : new VirtualKey[] {VirtualKey.TAB, VirtualKey.ENTER, VirtualKey.LEFT,
                VirtualKey.UP, VirtualKey.DOWN, VirtualKey.RIGHT}) {
            List<String> sent = new ArrayList<>();
            ImeTextBuffer buffer = new ImeTextBuffer(new ImeTextBuffer.Sink() {
                public void text(String text) { sent.add(text); }
                public void delete(int before, int after) { fail("No deletion expected"); }
            });
            buffer.compose("hello");
            StreamView stream = mock(StreamView.class, CALLS_REAL_METHODS);
            Context context = mock(Context.class);
            InputMethodManager manager = mock(InputMethodManager.class);
            doReturn(context).when(stream).getContext();
            when(context.getSystemService(Context.INPUT_METHOD_SERVICE)).thenReturn(manager);
            BaseInputConnection ime = mock(BaseInputConnection.class);
            Editable editable = mock(Editable.class);
            when(ime.getEditable()).thenReturn(editable);
            when(editable.length()).thenAnswer(call -> buffer.compositionLength());
            when(ime.finishComposingText()).thenAnswer(call -> { buffer.finish(); return true; });
            field(stream, StreamView.class, "imeConnection", ime);
            KeyboardOverlay overlay = mock(KeyboardOverlay.class, CALLS_REAL_METHODS);
            field(overlay, KeyboardOverlay.class, "stream", stream);
            field(overlay, KeyboardOverlay.class, "host", (KeyboardOverlay.Host)(value, down) -> sent.add(value.name() + (down ? "+" : "-")));
            Method stroke = KeyboardOverlay.class.getDeclaredMethod("stroke", VirtualKey.class);
            stroke.setAccessible(true);
            stroke.invoke(overlay, key);
            buffer.finish(); // A later IME finish must not type into the next field.
            stream.flushImeComposition();
            assertEquals(List.of("hello", key.name() + "+", key.name() + "-"), sent);
            verify(ime, times(1)).finishComposingText();
            verify(manager, times(1)).restartInput(stream);
        }
    }
}
