package com.limelight.porta;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.limelight.Game;
import com.limelight.R;

/** Launch with --es state connecting|error|lost|reconnecting. Never included in release. */
public final class SessionPreviewActivity extends Game {
    @Override protected void onCreate(Bundle state) {
        getIntent().putExtra(EXTRA_HOST, "127.0.0.1");
        getIntent().putExtra(EXTRA_APP_ID, 1);
        getIntent().putExtra(EXTRA_APP_NAME, "Desktop");
        getIntent().putExtra(EXTRA_PC_NAME, "torre");
        getIntent().putExtra(EXTRA_UNIQUEID, "porta-ui-preview");
        super.onCreate(state);
    }

    @Override protected void setPreferredOrientationForCurrentDisplay() {
        setRequestedOrientation("reconnecting".equals(getIntent().getStringExtra("state"))
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override protected boolean showSessionPreview(SessionUi ui) {
        SurfaceView surface = findViewById(R.id.surfaceView);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {}
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Canvas canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(0xFF373C6E);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(0xFFFBFCFE);
                    canvas.drawRoundRect(width * .05f, height * .05f, width * .95f, height * .92f, 16, 16, paint);
                    paint.setColor(0xFFDCE1E8);
                    canvas.drawRect(width * .05f, height * .05f, width * .95f, height * .10f, paint);
                    paint.setTextSize(width * .032f);
                    paint.setColor(Color.DKGRAY);
                    canvas.drawText("notas.txt — KWrite", width * .3f, height * .084f, paint);
                    String[] lines = {"Año nuevo en Concón", "", "Pendientes", "– Llamar a la Sra. Núñez",
                            "– Comprar pan de pascua", "– Revisar el contrato del depto.", "", "Canción: «Gracias a la vida»"};
                    for (int i = 0; i < lines.length; i++)
                        canvas.drawText((i + 1) + "   " + lines[i], width * .075f, height * (.16f + i * .035f), paint);
                    holder.unlockCanvasAndPost(canvas);
                }
                surface.postDelayed(() -> {
                    String state = getIntent().getStringExtra("state");
                    if ("error".equals(state)) ui.failed(SessionFailure.Cause.NO_RESPONSE);
                    else if ("pair".equals(state)) ui.failed(SessionFailure.Cause.PAIRING_REVOKED);
                    else if ("lost".equals(state)) ui.lost();
                    else if ("reconnecting".equals(state)) ui.reconnecting(null);
                }, 150);
            }
        });
        return true;
    }
}
