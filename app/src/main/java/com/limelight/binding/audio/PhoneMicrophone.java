package com.limelight.binding.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Session-owned phone microphone. Join capture before the native connection is stopped. */
public final class PhoneMicrophone {
    private static final String MIME = "audio/opus";
    private final MicrophonePump.Sink sink;
    private final Consumer<Exception> failure;
    private volatile boolean running;
    private AudioRecord record;
    private Thread worker;
    private final Object recordLock = new Object();

    public PhoneMicrophone(MicrophonePump.Sink sink, Consumer<Exception> failure) {
        this.sink = sink; this.failure = failure;
    }
    private static MediaFormat format() {
        MediaFormat format = MediaFormat.createAudioFormat(MIME, 48000, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 40000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        return format;
    }
    public static boolean supported() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try { return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format()) != null; }
        catch (RuntimeException e) { return false; }
    }
    public synchronized void start() {
        if (worker != null) return;
        running = true;
        worker = new Thread(this::capture, "Phone microphone");
        worker.start();
    }
    /** Stop AudioRecord to unblock its read, then join before releasing the connection. */
    public synchronized void stop() {
        running = false;
        synchronized (recordLock) {
            if (record != null) {
                try { record.stop(); } catch (IllegalStateException ignored) { }
            }
        }
        if (worker != null) {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try { worker.join(); } catch (InterruptedException e) { interrupted = true; }
            }
            worker = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    @SuppressLint("MissingPermission") // Game requests RECORD_AUDIO before starting this worker.
    private void capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        MediaCodec codec = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int size = Math.max(AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT), 3840);
            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            synchronized (recordLock) {
                if (!running) return;
                // Recognition preserves dictation audio; request AEC/NS explicitly below.
                // A device probe returned silence for COMMUNICATION but audio for RECOGNITION.
                record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                }
                record.startRecording();
            }
            MediaCodec encoder = codec;
            AudioRecord capture = record;
            MicrophonePump.run(capture::read, (pcm, pts) -> encode(encoder, pcm, pts), sink,
                    () -> System.nanoTime() / 1000, () -> running);
        } catch (Exception e) {
            if (running) { running = false; failure.accept(e); }
        } finally {
            if (codec != null) { try { codec.stop(); } catch (IllegalStateException ignored) { } codec.release(); }
            synchronized (recordLock) {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
                if (record != null) { record.release(); record = null; }
            }
        }
    }
    private List<MicrophonePump.Packet> encode(MediaCodec codec, short[] pcm, long pts) {
        List<MicrophonePump.Packet> output = new ArrayList<>();
        int index = codec.dequeueInputBuffer(10000);
        if (index >= 0) {
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < pcm.length * 2) throw new IllegalStateException("Opus input buffer too small");
            input.clear(); input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
            codec.queueInputBuffer(index, 0, pcm.length * 2, pts, 0);
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = true;
        while (true) {
            index = codec.dequeueOutputBuffer(info, first ? 5000 : 0);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (index < 0) break;
            first = false;
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    if (info.size > MicrophonePump.MAX_PACKET) throw new IllegalStateException("Opus packet too large");
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null) throw new IllegalStateException("Missing Opus output buffer");
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] packet = new byte[info.size]; buffer.get(packet);
                    output.add(new MicrophonePump.Packet(packet, info.presentationTimeUs));
                }
            } finally { codec.releaseOutputBuffer(index, false); }
        }
        return output;
    }
}
