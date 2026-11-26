package com.limelight.binding.video;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
public class CameraPacketizerTest {
    @Test public void packetizesCanonicalChunksAndStopWithLittleEndianFields() {
        byte[] data = new byte[769]; data[768] = 42;
        List<byte[]> packets = new ArrayList<>();
        assertTrue(CameraPacketizer.send(data, true, 270, 0x800000fe, 0x0102030405060708L,
                p -> { packets.add(p); return 0; }));
        assertEquals(2, packets.size()); assertEquals(792, packets.get(0).length); assertEquals(25, packets.get(1).length);
        ByteBuffer p = ByteBuffer.wrap(packets.get(1)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, p.get()); assertEquals(1, p.get()); assertEquals(270, p.getShort()); assertEquals(0, p.getInt());
        assertEquals(0x800000fe, p.getInt()); assertEquals(1, p.getShort()); assertEquals(2, p.getShort());
        assertEquals(0x0102030405060708L, p.getLong()); assertEquals(42, p.get());
        packets.clear();
        assertTrue(CameraPacketizer.send(null, true, 0, -1, 0, p2 -> { packets.add(p2); return 0; }));
        assertEquals(24, packets.get(0).length); assertEquals(2, packets.get(0)[1]); assertEquals(0, packets.get(0)[14]);
    }
    @Test public void boundsMemoryAndAbortsWholeFrameOnFirstSendFailure() {
        assertFalse(CameraPacketizer.send(new byte[0], false, 0, 0, 0, p -> { fail(); return 0; }));
        assertFalse(CameraPacketizer.send(new byte[CameraPacketizer.MAX_FRAME + 1], true, 0, 0, 0, p -> { fail(); return 0; }));
        int[] count = {0};
        assertFalse(CameraPacketizer.send(new byte[2000], true, 0, 0, 0, p -> ++count[0] == 2 ? -1 : 0));
        assertEquals(2, count[0]);
    }
    @Test public void acceptsAnnexBAndDetectsIdrAndParameterSets() {
        byte[] annex = {0,0,0,1,0x65,7,0,0,1,0x67};
        assertSame(annex, CameraPacketizer.annexB(annex));
        assertTrue(CameraPacketizer.hasNal(annex, 5)); assertTrue(CameraPacketizer.hasNal(annex, 7));
        assertFalse(CameraPacketizer.hasNal(annex, 8));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsLengthPrefixedFraming() {
        CameraPacketizer.annexB(new byte[]{0,0,0,2,0x65,7});
    }
    @Test public void startsOffAndOnlyCapturesWhenAllLifecycleGatesAllowIt() {
        CameraState s = new CameraState(false);
        assertFalse(s.shouldCapture(true,true,true,false,false,false)); s.toggle();
        assertTrue(s.shouldCapture(true,true,true,false,false,false));
        assertFalse(s.shouldCapture(false,true,true,false,false,false));
        assertFalse(s.shouldCapture(true,false,true,false,false,false));
        assertFalse(s.shouldCapture(true,true,false,false,false,false));
        assertFalse(s.shouldCapture(true,true,true,true,false,false));
        assertFalse(s.shouldCapture(true,true,true,false,true,false));
        assertFalse(s.shouldCapture(true,true,true,false,false,true));
        CameraState restored = new CameraState(s.requested()); assertTrue(restored.requested());
        restored.off(); assertFalse(restored.requested()); s.toggle(); assertFalse(s.requested());
    }
    @Test public void frontAndBackRotationRemainUprightWithoutMirroring() {
        assertEquals(90, CameraState.rotation(90,0,false));
        assertEquals(0, CameraState.rotation(90,90,false));
        assertEquals(0, CameraState.rotation(270,90,true));
        assertEquals(270, CameraState.rotation(270,0,true));
    }
}
