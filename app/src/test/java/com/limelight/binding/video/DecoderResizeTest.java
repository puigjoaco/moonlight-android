package com.limelight.binding.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.SurfaceHolder;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import com.limelight.nvstream.jni.MoonBridge;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DecoderResizeTest {
    private MediaCodec codec;
    private MediaFormat format;
    private Runnable failure;

    private MediaCodecDecoderRenderer renderer(int videoFormat, String mime) {
        MediaCodecDecoderRenderer r = mock(MediaCodecDecoderRenderer.class, CALLS_REAL_METHODS);
        codec = mock(MediaCodec.class);
        failure = mock(Runnable.class);
        format = MediaFormat.createVideoFormat(mime, 640, 360);
        format.setInteger("vendor.test.low-latency", 1);
        when(codec.getInputFormat()).thenReturn(format);
        ReflectionHelpers.setField(r, "configuredFormat", format);
        ReflectionHelpers.setField(r, "videoDecoder", codec);
        ReflectionHelpers.setField(r, "renderTarget", mock(SurfaceHolder.class));
        ReflectionHelpers.setField(r, "videoFormat", videoFormat);
        ReflectionHelpers.setField(r, "initialWidth", 640);
        ReflectionHelpers.setField(r, "initialHeight", 360);
        ReflectionHelpers.setField(r, "adaptivePlayback", true);
        ReflectionHelpers.setField(r, "codecRecoveryType", new AtomicInteger());
        ReflectionHelpers.setField(r, "codecRecoveryMonitor", new Object());
        ReflectionHelpers.setField(r, "codecRecoveryThreadQuiescedFlags", 2); // renderer already quiesced
        ReflectionHelpers.setField(r, "outputBufferQueue", new LinkedBlockingQueue<Integer>());
        ReflectionHelpers.setField(r, "vpsBuffers", new ArrayList<byte[]>());
        ReflectionHelpers.setField(r, "spsBuffers", new ArrayList<byte[]>());
        ReflectionHelpers.setField(r, "ppsBuffers", new ArrayList<byte[]>());
        r.enableInSessionResize(true, failure);
        return r;
    }

    private boolean prepare(MediaCodecDecoderRenderer r, byte[] data, int type, int frameType) {
        return ReflectionHelpers.callInstanceMethod(r, "prepareStreamDimensions",
                ReflectionHelpers.ClassParameter.from(byte[].class, data),
                ReflectionHelpers.ClassParameter.from(int.class, data.length),
                ReflectionHelpers.ClassParameter.from(int.class, type),
                ReflectionHelpers.ClassParameter.from(int.class, frameType));
    }

    @Test public void h264RestartsBeforeIdrAndPreservesAcceptedLowLatencyOptions() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_H264, "video/avc");
        assertTrue(prepare(r, StreamDimensionsTest.H264, MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).stop(); verify(codec).start();
        assertEquals(360, format.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(640, format.getInteger(MediaFormat.KEY_HEIGHT));
        assertEquals(360, format.getInteger(MediaFormat.KEY_MAX_WIDTH));
        assertEquals(640, format.getInteger(MediaFormat.KEY_MAX_HEIGHT));
        assertEquals(1, format.getInteger("vendor.test.low-latency"));
        assertEquals(0, (int)ReflectionHelpers.getField(r, "codecRecoveryAttempts"));
        verifyNoInteractions(failure);
    }

    @Test public void hevcPreservesAlreadyReceivedVpsWhenSpsChangesSize() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_H265, "video/hevc");
        ReflectionHelpers.setField(r, "initialWidth", 360);
        ReflectionHelpers.setField(r, "initialHeight", 640);
        ArrayList<byte[]> vps = ReflectionHelpers.getField(r, "vpsBuffers");
        byte[] precedingVps = {0, 0, 0, 1, 0x40, 1};
        vps.add(precedingVps);
        assertTrue(prepare(r, StreamDimensionsTest.HEVC, MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).stop(); verify(codec).start();
        assertEquals(640, format.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(360, format.getInteger(MediaFormat.KEY_HEIGHT));
        assertSame(precedingVps, vps.get(0));
    }

    @Test public void av1SequenceHeaderReconfiguresBeforeInlineKeyFrame() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "video/av01");
        assertTrue(prepare(r, StreamDimensionsTest.AV1, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).stop(); verify(codec).start();
        assertEquals(360, format.getInteger(MediaFormat.KEY_WIDTH));
    }

    @Test public void nonIdrMismatchRequestsIdrAndDropsFollowingInterFrames() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "video/av01");
        assertFalse(prepare(r, StreamDimensionsTest.AV1, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertFalse(prepare(r, new byte[] {0x12, 0}, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec, never()).stop();
        assertTrue(prepare(r, StreamDimensionsTest.AV1, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).stop();
    }

    @Test public void stockHostBypassesNewParsingAndReconfiguration() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_H265, "video/hevc");
        r.enableInSessionResize(false, failure);
        assertTrue(prepare(r, new byte[] {0}, MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        verify(codec, never()).stop();
    }

    @Test public void resultAheadOfVideoRequestsIdrUntilDecoderHasNewDimensions() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "video/av01");
        r.expectStreamDimensions(360, 640);
        assertFalse(prepare(r, new byte[] {0x12, 0}, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec, never()).stop();
        assertTrue(prepare(r, StreamDimensionsTest.AV1, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        assertTrue(prepare(r, new byte[] {0x12, 0}, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec).stop();
    }

    @Test public void videoAheadOfResultDoesNotRestartTwice() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_AV1_MAIN8, "video/av01");
        assertTrue(prepare(r, StreamDimensionsTest.AV1, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        r.expectStreamDimensions(360, 640);
        assertTrue(prepare(r, new byte[] {0x12, 0}, MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec).stop();
    }

    @Test public void configurationFailureCallsReconnectFallback() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_H264, "video/avc");
        doThrow(new IllegalStateException("unsupported orientation")).when(codec).configure(any(), any(), any(), anyInt());
        assertFalse(prepare(r, StreamDimensionsTest.H264, MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).reset();
        verify(failure).run();
    }
    @Test public void decodeFailureBeforeFirstResizedFrameCallsReconnectFallback() {
        MediaCodecDecoderRenderer r = renderer(MoonBridge.VIDEO_FORMAT_H264, "video/avc");
        assertTrue(prepare(r, StreamDimensionsTest.H264, MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        verifyNoInteractions(failure);
        ReflectionHelpers.callInstanceMethod(r, "handleDecoderException",
                ReflectionHelpers.ClassParameter.from(IllegalStateException.class, new IllegalStateException("bad size")));
        verify(failure).run();
        assertTrue((boolean) ReflectionHelpers.getField(r, "stopping"));
    }
}
