package com.limelight.binding.input.keyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public class KeyboardLayoutTest {
    @Test public void allFourteenCellsFitNarrowAndInsetLandscapeWidths() {
        for (int width : new int[]{400,560,640,672,673,784,832}) {
            int cell=KeyboardLayout.cellWidth(width,14,1,48);
            assertTrue(cell*14+1<=width);
            assertTrue(cell<=48);
            if(width>=561)assertTrue(cell>=40);
        }
        assertEquals(45,KeyboardLayout.cellWidth(640,14,1,48));
        assertEquals(48,KeyboardLayout.cellWidth(784,14,1,48));
    }
}
