package com.limelight;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/** Receives runtime permission results on behalf of the noHistory stream activity. */
public class MicrophonePermissionActivity extends Activity {
    private static final int MICROPHONE_PERMISSION = 7104;
    private static volatile Boolean permissionResult;

    private String permission;
    static void requestPermission(Activity activity) { requestPermission(activity, Manifest.permission.RECORD_AUDIO); }
    static void requestPermission(Activity activity, String permission) {
        permissionResult = null;
        // Opening our own permission UI must not count as the user leaving for PiP.
        activity.startActivity(new Intent(activity, MicrophonePermissionActivity.class).putExtra("permission", permission)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
    }

    static Boolean getPermissionResult() {
        return permissionResult;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permission = getIntent().getStringExtra("permission");
        if (!Manifest.permission.CAMERA.equals(permission)) permission = Manifest.permission.RECORD_AUDIO;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            permissionResult = true;
            finish();
        }
        else if (savedInstanceState == null) {
            requestPermissions(new String[] {permission}, MICROPHONE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_PERMISSION) {
            permissionResult = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            finish();
        }
    }

    @Override
    public void finish() {
        // Treat backing out without a permission result as denial, too.
        if (permissionResult == null) {
            permissionResult = false;
        }
        super.finish();
    }
}
