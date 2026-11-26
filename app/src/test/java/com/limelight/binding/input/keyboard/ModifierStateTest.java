package com.limelight.binding.input.keyboard;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static com.limelight.binding.input.keyboard.VirtualKey.*;

public class ModifierStateTest {
    private final List<String> events = new ArrayList<>();
    private final ModifierState model = new ModifierState(new ModifierState.Listener() {
        @Override public void send(VirtualKey key, boolean down) {
            events.add(key.name() + (down ? "+" : "-") + model.flags());
        }
        @Override public void changed() { }
    });
    private void tap(VirtualKey key) { model.press(key); model.release(key, false); }
    private void lock(VirtualKey key) { model.press(key); assertTrue(model.longPress(key)); model.release(key, false); }
    @Test public void armingOneShotSendsNothingAndWrapsOnlyTheNextStroke() {
        tap(CTRL);
        assertEquals(ModifierState.State.ONE_SHOT, model.state(CTRL));
        assertTrue(events.isEmpty()); assertEquals(0, model.flags());
        model.nonModifierDown(); events.add("key+"); events.add("key-"); model.nonModifierUp();
        assertEquals(List.of("CTRL+2", "key+", "key-", "CTRL-0"), events);
        assertEquals(0, model.armedFlags());
    }
    @Test public void pendingModifierFingerSendsNothingUntilLift() {
        model.press(SHIFT); assertTrue(events.isEmpty());
        model.release(SHIFT, false); assertTrue(events.isEmpty());
        assertEquals(ModifierState.State.ONE_SHOT, model.state(SHIFT));
    }
    @Test public void winTapCompletesMetaStrokeOnlyOnFingerLift() {
        model.press(WIN); assertTrue(events.isEmpty()); model.release(WIN, false);
        assertEquals(List.of("WIN+8", "WIN-0"), events);
    }
    @Test public void winLongPressAndAltLockSurviveRepeatedStrokesUntilTapped() {
        lock(WIN); lock(ALT);
        for (int i=0;i<3;i++) { model.nonModifierDown(); model.nonModifierUp(); }
        assertEquals(12, model.flags());
        tap(WIN); tap(ALT);
        assertEquals(List.of("WIN+8", "ALT+12", "WIN-4", "ALT-0"), events);
    }
    @Test public void tappingAnArmedOneShotDisarmsWithoutAnyPackets() {
        tap(SHIFT); tap(SHIFT); assertTrue(events.isEmpty()); assertEquals(0,model.armedFlags());
    }
    @Test public void dragCancellationCannotFireOrArmAKey() {
        model.press(WIN); model.release(WIN,true);
        model.press(CTRL); model.release(CTRL,true);
        assertTrue(events.isEmpty()); assertEquals(0,model.armedFlags());
    }
    @Test public void cancellingALockGestureReleasesItAndCancellingAHighlightedTapPreservesIt() {
        model.press(SHIFT); model.longPress(SHIFT); model.release(SHIFT,true);
        assertEquals(List.of("SHIFT+1","SHIFT-0"),events);
        lock(CTRL); model.press(CTRL); model.release(CTRL,true);
        assertEquals(2,model.flags());
    }
    @Test public void combinedOneShotsWrapAMouseClickAndUpdateFlagsBeforePackets() {
        tap(CTRL); tap(ALT); model.nonModifierDown(); events.add("click"); model.nonModifierUp();
        assertEquals(List.of("CTRL+2","ALT+6","click","CTRL-4","ALT-0"),events);
    }
    @Test public void unicodeTemporarilyReleasesEveryLockAndRestoresAfterInjection() {
        lock(CTRL); lock(ALT); lock(WIN);
        String text=model.beforeText("ñ");
        assertEquals("ñ",text); assertEquals(0,model.flags()); events.add("unicode"); model.afterText();
        assertEquals(14,model.flags());
        assertEquals(List.of("CTRL+2","ALT+6","WIN+14","CTRL-12","WIN-4","ALT-0",
                "unicode","CTRL+2","WIN+10","ALT+14"),events);
    }
    @Test public void oneShotShiftUppercasesUnicodeButDoesNotPersist() {
        tap(SHIFT); tap(CTRL);
        assertEquals("AÑO ¿QUÉ?",model.beforeText("año ¿qué?"));
        assertEquals(0,model.flags()); model.afterText(); assertEquals(0,model.armedFlags());
        assertTrue(events.isEmpty());
    }
    @Test public void lockedShiftUppercasesUnicodeWhileItsPhysicalKeyIsSuspended() {
        lock(SHIFT);
        assertEquals("ÑÜ", model.beforeText("ñü"));
        assertEquals(0, model.flags());
        model.afterText();
        assertEquals(1, model.flags());
    }
    @Test public void focusLossRotationCloseAndPauseReleaseAndInvalidatePendingTouches() {
        lock(CTRL); tap(SHIFT); model.press(WIN);
        model.releaseAll(); model.releaseAll(); int count=events.size();
        model.release(WIN,false); model.afterText();
        assertEquals(count,events.size()); assertEquals(0,model.flags()); assertEquals(0,model.armedFlags());
    }
    @Test public void aCancelledOrReleasedFingerCannotLockLater() {
        model.press(ALT); model.release(ALT,true); assertFalse(model.longPress(ALT));
        assertEquals(0,model.flags());
    }

    @Test public void aLongPlainCommitDoesNotFloodTheUiWithUnchangedModifierNotifications() {
        int[] changes={0};
        ModifierState plain=new ModifierState(new ModifierState.Listener() {
            public void send(VirtualKey key,boolean down) { }
            public void changed() {changes[0]++;}
        });
        for(int i=0;i<2000;i++){plain.nonModifierDown();plain.nonModifierUp();}
        assertEquals(0,changes[0]);
    }
}
