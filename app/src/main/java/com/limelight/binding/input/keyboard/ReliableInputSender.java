package com.limelight.binding.input.keyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Serial delivery with native-queue backpressure. A full queue retries the same packet, including key-ups. */
public final class ReliableInputSender implements AutoCloseable {
    public interface Attempt { int send() throws InterruptedException; }
    private final LinkedBlockingQueue<Attempt> queue = new LinkedBlockingQueue<>();
    private final ThreadLocal<List<Attempt>> batch = new ThreadLocal<>();
    private volatile boolean closed;
    private Thread worker;

    public synchronized boolean enqueue(Attempt packet) {
        if (closed) return false;
        List<Attempt> current = batch.get();
        if (current != null) { current.add(packet); return true; }
        queue.add(packet);
        if (worker == null) {
            worker = new Thread(this::drain, "Moonlight input sender");
            worker.setDaemon(true);
            worker.start();
        }
        return true;
    }
    /** Reserve one position before planning so later Enter/mouse events cannot split a commit. */
    public void batch(Runnable planner) {
        if (batch.get() != null) { planner.run(); return; }
        List<Attempt> packets = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(1);
        if (!enqueue(() -> {
            ready.await();
            for (Attempt packet : packets) deliver(packet);
            return 0;
        })) return;
        batch.set(packets);
        try { planner.run(); }
        finally { batch.remove(); ready.countDown(); }
    }
    private void deliver(Attempt packet) throws InterruptedException {
        while (!closed && packet.send() != 0) Thread.sleep(2);
    }
    private void drain() {
        try {
            while (!closed) {
                Attempt packet = queue.take();
                deliver(packet);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch idle = new CountDownLatch(1);
        return enqueue(() -> { idle.countDown(); return 0; }) && idle.await(timeout, unit);
    }
    /** Teardown cancels old-session work; the host resets its keys when the connection closes. */
    @Override public void close() {
        Thread current;
        synchronized (this) {
            if (closed) return;
            closed = true;
            queue.clear();
            current = worker;
            if (current != null) current.interrupt();
        }
        if (current != null && current != Thread.currentThread()) {
            try { current.join(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
