package com.limelight.binding.video;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Session-owned Camera2 surface encoder; no preview window and no frame backlog. */
public final class PhoneCamera {
    public interface Sink { boolean send(byte[] frame, boolean key, int rotation, long timestampUs); }
    private final Context context;
    private final Sink sink;
    private final IntSupplier displayDegrees;
    private final Consumer<Exception> failure;
    private volatile boolean running;
    private Thread worker;
    public PhoneCamera(Context context, Sink sink, IntSupplier displayDegrees, Consumer<Exception> failure) {
        this.context = context.getApplicationContext(); this.sink = sink;
        this.displayDegrees = displayDegrees; this.failure = failure;
    }
    public synchronized void start(boolean front) {
        if (worker != null) return;
        running = true;
        worker = new Thread(() -> capture(front), "Phone camera"); worker.start();
    }
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try { worker.join(); } catch (InterruptedException e) { interrupted = true; }
            }
            worker = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static byte[] bytes(ByteBuffer buffer) {
        if (buffer == null) return new byte[0];
        ByteBuffer copy = buffer.duplicate(); byte[] data = new byte[copy.remaining()]; copy.get(data); return data;
    }
    private static void requestIdr(MediaCodec codec) {
        Bundle params = new Bundle(); params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        codec.setParameters(params);
    }
    @SuppressLint("MissingPermission") // Game requests CAMERA through its history-retaining permission activity.
    private void capture(boolean front) {
        HandlerThread callbacks = new HandlerThread("Phone camera callbacks"); callbacks.start();
        Handler handler = new Handler(callbacks.getLooper());
        AtomicReference<CameraDevice> opened = new AtomicReference<>();
        AtomicReference<CameraCaptureSession> session = new AtomicReference<>();
        AtomicReference<Exception> asyncError = new AtomicReference<>();
        ArrayBlockingQueue<CameraDevice> ready = new ArrayBlockingQueue<>(1);
        ArrayBlockingQueue<CameraCaptureSession> configured = new ArrayBlockingQueue<>(1);
        MediaCodec codec = null; Surface surface = null;
        try {
            CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            String selected = null; CameraCharacteristics chars = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    selected = id; chars = c; break;
                }
            }
            if (selected == null) throw new IllegalStateException("Requested camera unavailable");
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            boolean supported = false;
            if (map != null && map.getOutputSizes(MediaCodec.class) != null)
                for (Size size : map.getOutputSizes(MediaCodec.class)) if (size.getWidth() == 1280 && size.getHeight() == 720) supported = true;
            if (!supported) throw new IllegalStateException("Camera lacks 720p encoder surface");
            Integer sensorValue = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensor = sensorValue == null ? 0 : sensorValue;
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            if (Build.VERSION.SDK_INT >= 30 && codec.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface(); codec.start();
            manager.openCamera(selected, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    opened.set(camera);
                    if (!running) camera.close();
                    ready.offer(camera);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    asyncError.set(new IllegalStateException("Camera disconnected")); camera.close();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    asyncError.set(new IllegalStateException("Camera error " + error)); camera.close();
                }
            }, handler);
            CameraDevice camera = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (camera == null && asyncError.get() == null && System.nanoTime() < deadline)
                camera = ready.poll(50, TimeUnit.MILLISECONDS);
            if (!running) return;
            if (camera == null) throw new IllegalStateException("Camera open timed out", asyncError.get());
            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession value) {
                    if (!running) { value.close(); return; }
                    session.set(value); configured.offer(value);
                }
                @Override public void onConfigureFailed(CameraCaptureSession value) {
                    value.close(); asyncError.set(new IllegalStateException("Camera configuration failed"));
                }
            }, handler);
            CameraCaptureSession captureSession = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (running && captureSession == null && asyncError.get() == null && System.nanoTime() < deadline)
                captureSession = configured.poll(50, TimeUnit.MILLISECONDS);
            if (!running) return;
            if (captureSession == null) throw new IllegalStateException("Camera configuration timed out", asyncError.get());
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface); request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> fps = null;
            if (ranges != null) for (Range<Integer> range : ranges)
                if (range.getUpper() == 30 && (fps == null || range.getLower() > fps.getLower())) fps = range;
            if (fps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            captureSession.setRepeatingRequest(request.build(), null, handler);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] config = new byte[0]; boolean waitingIdr = true;
            int lastRotation = -1;
            long clockOffset = Long.MIN_VALUE;
            while (running) {
                if (asyncError.get() != null) throw asyncError.get();
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat(); config = new byte[0];
                    for (String key : new String[]{"csd-0", "csd-1"}) {
                        byte[] part = bytes(output.getByteBuffer(key));
                        if (part.length > 0) config = CameraPacketizer.join(config, CameraPacketizer.annexB(part));
                    }
                    continue;
                }
                if (index < 0) continue;
                try {
                    if (info.size == 0 || !running) continue;
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null || info.size > CameraPacketizer.MAX_FRAME) { waitingIdr = true; requestIdr(codec); continue; }
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] data = CameraPacketizer.annexB(bytes(buffer));
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { config = data; continue; }
                    boolean key = CameraPacketizer.hasNal(data, 5);
                    int rotation = CameraState.rotation(sensor, displayDegrees.getAsInt(), front);
                    if (rotation != lastRotation) { waitingIdr = true; lastRotation = rotation; requestIdr(codec); }
                    long now = System.nanoTime() / 1000;
                    // Some camera clocks are not MONOTONIC. Establish a local offset once, then detect backlog.
                    if (clockOffset == Long.MIN_VALUE) clockOffset = now - info.presentationTimeUs;
                    if (now - info.presentationTimeUs - clockOffset > 100000) {
                        waitingIdr = true; requestIdr(codec); continue;
                    }
                    if (waitingIdr && !key) continue;
                    if (key && !(CameraPacketizer.hasNal(data, 7) && CameraPacketizer.hasNal(data, 8))) {
                        if (!CameraPacketizer.hasNal(config, 7) || !CameraPacketizer.hasNal(config, 8)) continue;
                        data = CameraPacketizer.join(config, data);
                    }
                    long sentAt = System.nanoTime();
                    boolean sent = sink.send(data, key, rotation, info.presentationTimeUs);
                    waitingIdr = !sent || System.nanoTime() - sentAt > TimeUnit.MILLISECONDS.toNanos(33);
                    if (waitingIdr) requestIdr(codec);
                } finally { codec.releaseOutputBuffer(index, false); }
            }
        } catch (Exception error) {
            if (running) { running = false; failure.accept(error); }
        } finally {
            // Drain queued callbacks before closing every handle; late onOpened cannot leak a device.
            handler.post(() -> {
                CameraCaptureSession currentSession = session.getAndSet(null);
                if (currentSession != null) currentSession.close();
                CameraDevice currentCamera = opened.getAndSet(null);
                if (currentCamera != null) currentCamera.close();
            });
            callbacks.quitSafely();
            boolean interrupted = false;
            while (callbacks.isAlive()) {
                try { callbacks.join(); } catch (InterruptedException e) { interrupted = true; }
            }
            CameraCaptureSession remainingSession = session.getAndSet(null);
            if (remainingSession != null) remainingSession.close();
            CameraDevice remainingCamera = opened.getAndSet(null);
            if (remainingCamera != null) remainingCamera.close();
            if (codec != null) { try { codec.stop(); } catch (IllegalStateException ignored) { } codec.release(); }
            if (surface != null) surface.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
