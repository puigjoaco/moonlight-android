package com.limelight.binding.input.keyboard;
import android.view.KeyEvent;
import com.limelight.ui.StreamView;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class ImeKeyEventTest {
    @Test public void actionMultipleReachesTheExistingTextEntryPoint() {
        KeyEvent event=mock(KeyEvent.class);
        when(event.getAction()).thenReturn(KeyEvent.ACTION_MULTIPLE);
        when(event.getKeyCode()).thenReturn(KeyEvent.KEYCODE_UNKNOWN);
        when(event.getCharacters()).thenReturn("año");
        StreamView.InputCallbacks callbacks=mock(StreamView.InputCallbacks.class);
        when(callbacks.handleKeyMultiple(event)).thenReturn(true);
        assertTrue(StreamView.dispatchImeKeyEvent(event, callbacks));
        verify(callbacks).handleKeyMultiple(event);
        verifyNoMoreInteractions(callbacks);
    }
}
