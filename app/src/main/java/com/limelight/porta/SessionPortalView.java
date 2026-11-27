package com.limelight.porta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.appcompat.content.res.AppCompatResources;
import com.limelight.R;

/** Vector illustration from the approved portal mockup, with one independently pulsing halo. */
@android.annotation.SuppressLint("ViewConstructor") // Created programmatically with a required state.
final class SessionPortalView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path door = new Path();
    private final boolean lit;
    private final Path beam = new Path();
    private final Shader glow = new RadialGradient(192, 172, 170,
            new int[]{0x6BFF9E66, 0x1FFF9E66, 0x00FF9E66}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
    private final Shader floor = new LinearGradient(0, 230, 0, 330,
            new int[]{0x99FFB774, 0x00FFB774}, null, Shader.TileMode.CLAMP);
    private final Shader horizon = new LinearGradient(20, 0, 364, 0,
            new int[]{0x00FFB774, 0x99FFF1DA, 0x00FFB774}, null, Shader.TileMode.CLAMP);
    private final Shader light = new LinearGradient(0, 38, 0, 230,
            new int[]{0xFFD8467C, 0xFFFF7A57, 0xFFFFB774, 0xFFFFF1DA},
            new float[]{0, .38f, .72f, 1}, Shader.TileMode.CLAMP);
    private final Shader core = new RadialGradient(192, 232, 70,
            new int[]{0xF2FFFBF2, 0x8CFFE7C2, 0x00FFD9A6}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP);
    private final Drawable linkOff;
    private float glowAlpha = 1f;
    private android.animation.ValueAnimator pulse;

    SessionPortalView(Context context, boolean lit) {
        super(context);
        this.lit = lit;
        door.moveTo(134, 230);
        door.lineTo(134, 96);
        door.arcTo(new RectF(134, 38, 250, 154), 180, 180);
        door.lineTo(250, 230);
        door.close();
        beam.moveTo(134, 230); beam.lineTo(250, 230);
        beam.lineTo(290, 330); beam.lineTo(94, 330); beam.close();
        linkOff = AppCompatResources.getDrawable(context, R.drawable.ic_porta_link_off);
        if (linkOff != null) {
            linkOff.setTint(PortaViews.color(context, R.color.porta_on_surface_variant));
            linkOff.setBounds(180, 146, 204, 170);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getResources().getDisplayMetrics().density, getHeight() / 330f);
        canvas.save();
        canvas.translate((getWidth() - 384 * scale) / 2, (getHeight() - 330 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        if (lit) {
            float alpha = getResources().getBoolean(R.bool.porta_is_light) ? .6f : 1f;
            canvas.save();
            canvas.scale(1f, .95f, 192, 172);
            paint.setShader(glow);
            paint.setAlpha(Math.round(255 * alpha * glowAlpha));
            canvas.drawCircle(192, 172, 170, paint);
            canvas.restore();
            paint.setAlpha(Math.round(255 * alpha * .7f));
            paint.setShader(floor);
            canvas.drawPath(beam, paint);
            paint.setAlpha(255);
            paint.setShader(horizon);
            canvas.drawRect(20, 229.4f, 364, 230.6f, paint);
            paint.setShader(light);
            canvas.drawPath(door, paint);
            canvas.save();
            canvas.clipPath(door);
            canvas.scale(1.2f, 1f, 192, 232);
            paint.setShader(core);
            canvas.drawCircle(192, 232, 70, paint);
            canvas.restore();
        } else {
            paint.setShader(null);
            paint.setColor(PortaViews.color(getContext(), R.color.porta_surface_container_high));
            canvas.drawPath(door, paint);
            paint.setColor(PortaViews.color(getContext(), R.color.porta_outline_variant));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            canvas.drawPath(door, paint);
            canvas.drawLine(40, 230, 344, 230, paint);
            paint.setStyle(Paint.Style.FILL);
            if (linkOff != null) linkOff.draw(canvas);
        }
        paint.setShader(null);
        paint.setAlpha(255);
        canvas.restore();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (lit && android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0) {
            pulse = android.animation.ValueAnimator.ofFloat(.8f, 1f);
            pulse.setDuration(2400);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.addUpdateListener(value -> { glowAlpha = (float) value.getAnimatedValue(); invalidate(); });
            pulse.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (pulse != null) pulse.cancel();
        super.onDetachedFromWindow();
    }
}
