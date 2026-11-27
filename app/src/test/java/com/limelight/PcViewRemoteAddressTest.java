package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder;
import com.limelight.nvstream.http.ComputerDetails;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PcViewRemoteAddressTest {
    @Test public void dialogPrefillsKnownRouteAndOffersRemove() {
        ComputerDetails c = new ComputerDetails();
        c.remoteAddress = new ComputerDetails.AddressTuple("2001:db8::10", 48089);
        AlertDialog dialog = openDialog(c, mock(ComputerManagerBinder.class));
        assertEquals("[2001:db8::10]:48089", findInput(dialog.getWindow().getDecorView()).getText().toString());
        assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
        dialog.dismiss();
    }

    @Test public void invalidAndLanAddressesStayInDialogWithoutSaving() {
        ComputerManagerBinder binder = mock(ComputerManagerBinder.class);
        AlertDialog dialog = openDialog(new ComputerDetails(), binder);
        TextInputEditText input = findInput(dialog.getWindow().getDecorView());
        TextInputLayout field = (TextInputLayout) input.getParent().getParent();
        assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_NEUTRAL).getVisibility());
        input.setText("192.168.1.1");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals(input.getContext().getString(R.string.porta_remote_address_public), field.getError());
        input.setText("203.0.113.10:65536");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertEquals(input.getContext().getString(R.string.porta_remote_address_invalid), field.getError());
        verifyNoInteractions(binder);
        dialog.dismiss();
    }

    private AlertDialog openDialog(ComputerDetails c, ComputerManagerBinder binder) {
        PcView home = spy(Robolectric.buildActivity(PcView.class).get());
        home.setTheme(R.style.Theme_Porta);
        c.uuid = "test-device";
        doReturn(c).when(home).getComputer(c.uuid);
        ReflectionHelpers.setField(home, "managerBinder", binder);
        ReflectionHelpers.callInstanceMethod(home, "showRemoteAddress",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, c));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private TextInputEditText findInput(View view) {
        if (view instanceof TextInputEditText) return (TextInputEditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextInputEditText input = findInput(group.getChildAt(i));
                if (input != null) return input;
            }
        }
        return null;
    }
}
