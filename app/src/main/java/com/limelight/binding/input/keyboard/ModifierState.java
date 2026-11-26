package com.limelight.binding.input.keyboard;

import java.util.EnumMap;
import java.util.Locale;

/** Armed one-shots are local; only locked modifiers and the current wrapped stroke are held remotely. */
public final class ModifierState {
    public enum State { OFF, HELD, ONE_SHOT, LOCKED }
    public interface Listener {
        void send(VirtualKey key, boolean down);
        void changed();
    }
    private static final class Entry {
        State state = State.OFF;
        State original = State.OFF;
        boolean fingerDown;
        boolean physical;
    }
    private final EnumMap<VirtualKey, Entry> entries = new EnumMap<>(VirtualKey.class);
    private final Listener listener;
    private int textDepth;

    public ModifierState(Listener listener) {
        this.listener = listener;
        for (VirtualKey key : VirtualKey.values()) if (key.modifier != 0) entries.put(key, new Entry());
    }
    public synchronized State state(VirtualKey key) { return entries.get(key).state; }
    public synchronized int flags() {
        int result = 0;
        for (VirtualKey key : entries.keySet()) if (entries.get(key).physical) result |= key.modifier;
        return result;
    }
    public synchronized int armedFlags() {
        int result = 0;
        for (VirtualKey key : entries.keySet()) {
            State state = entries.get(key).state;
            if (state == State.ONE_SHOT || state == State.LOCKED) result |= key.modifier;
        }
        return result;
    }
    private void physical(VirtualKey key, boolean down) {
        Entry entry = entries.get(key);
        if (entry.physical == down) return;
        entry.physical = down; // Metadata must reflect the transition before the packet is sent.
        listener.send(key, down);
    }
    public synchronized void press(VirtualKey key) {
        Entry entry = entries.get(key);
        if (entry.fingerDown) return;
        entry.original = entry.state;
        entry.fingerDown = true;
        if (entry.state == State.OFF) entry.state = State.HELD;
        listener.changed();
    }
    public synchronized boolean longPress(VirtualKey key) {
        Entry entry = entries.get(key);
        if (!entry.fingerDown || entry.original != State.OFF || entry.state != State.HELD) return false;
        entry.state = State.LOCKED;
        if (textDepth == 0) physical(key, true);
        listener.changed();
        return true;
    }
    public synchronized void release(VirtualKey key, boolean cancelled) {
        Entry entry = entries.get(key);
        if (!entry.fingerDown) return;
        entry.fingerDown = false;
        if (cancelled) {
            entry.state = entry.original;
            physical(key, entry.state == State.LOCKED && textDepth == 0);
        } else if (entry.original != State.OFF) {
            entry.state = State.OFF;
            physical(key, false);
        } else if (entry.state != State.LOCKED) {
            if (key == VirtualKey.WIN) {
                physical(key, true);
                physical(key, false);
                entry.state = State.OFF;
            } else entry.state = State.ONE_SHOT;
        }
        listener.changed();
    }
    public synchronized void nonModifierDown() {
        if (textDepth != 0) return;
        for (VirtualKey key : entries.keySet()) {
            if (entries.get(key).state == State.ONE_SHOT) physical(key, true);
        }
    }
    public synchronized void nonModifierUp() {
        boolean changed = false;
        for (VirtualKey key : entries.keySet()) {
            Entry entry = entries.get(key);
            if (entry.state == State.ONE_SHOT && entry.physical) {
                physical(key, false);
                entry.state = State.OFF;
                changed = true;
            }
        }
        if (changed) listener.changed();
    }
    /** Unicode synthesis must never inherit Ctrl, Alt, Meta or a held Shift. */
    public synchronized String beforeText(String text) {
        State shift = entries.get(VirtualKey.SHIFT).state;
        if (shift == State.ONE_SHOT || shift == State.LOCKED) text = text.toUpperCase(Locale.ROOT);
        textDepth++;
        for (VirtualKey key : entries.keySet()) {
            Entry entry = entries.get(key);
            physical(key, false);
            if (entry.state == State.ONE_SHOT) entry.state = State.OFF;
        }
        listener.changed();
        return text;
    }
    public synchronized void afterText() {
        if (textDepth == 0 || --textDepth != 0) return;
        for (VirtualKey key : entries.keySet()) {
            if (entries.get(key).state == State.LOCKED) physical(key, true);
        }
        listener.changed();
    }
    public synchronized void releaseAll() {
        textDepth = 0;
        for (VirtualKey key : entries.keySet()) {
            Entry entry = entries.get(key);
            entry.fingerDown = false;
            entry.state = entry.original = State.OFF;
            physical(key, false);
        }
        listener.changed();
    }
}
