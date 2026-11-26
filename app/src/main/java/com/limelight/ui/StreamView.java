package com.limelight.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.text.InputType;
import android.text.Selection;
import android.util.Log;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import com.limelight.binding.input.keyboard.ImeTextBuffer;

public class StreamView extends SurfaceView {
    private double desiredAspectRatio;
    private InputCallbacks inputCallbacks;
    private BaseInputConnection imeConnection;

    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    public StreamView(Context context) {
        super(context);
    }

    public StreamView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public StreamView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If no fixed aspect ratio has been provided, simply use the default onMeasure() behavior
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Based on code from: https://www.buzzingandroid.com/2012/11/easy-measuring-of-custom-views-with-specific-aspect-ratio/
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int measuredHeight, measuredWidth;
        if (widthSize > heightSize * desiredAspectRatio) {
            measuredHeight = heightSize;
            measuredWidth = (int)(measuredHeight * desiredAspectRatio);
        } else {
            measuredWidth = widthSize;
            measuredHeight = (int)(measuredWidth / desiredAspectRatio);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    /** Commit the unsent composing span once before an accessory can move focus or apply a modifier. */
    public void flushImeComposition() {
        if (imeConnection != null && imeConnection.getEditable().length() != 0) {
            imeConnection.finishComposingText();
            ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(this);
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN |
                EditorInfo.IME_ACTION_NONE;
        outAttrs.initialSelStart = outAttrs.initialSelEnd = 0;
        return imeConnection = new BaseInputConnection(this, true) {
            private final ImeTextBuffer context = new ImeTextBuffer(new ImeTextBuffer.Sink() {
                @Override public void text(String text) { if (inputCallbacks != null) inputCallbacks.commitText(text); }
                @Override public void delete(int before, int after) {
                    if (inputCallbacks != null) inputCallbacks.deleteText(before, after);
                }
            });
            // Committed text belongs to the remote application. Never offer it as local IME context.
            @Override public CharSequence getTextBeforeCursor(int length, int flags) { return ""; }
            @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
            @Override public CharSequence getSelectedText(int flags) { return ""; }
            @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
                ExtractedText result = new ExtractedText();
                result.text = "";
                result.selectionStart = result.selectionEnd = 0;
                return result;
            }
            private void clearLocalText() {
                super.finishComposingText();
                getEditable().clear();
                Selection.setSelection(getEditable(), 0);
            }
            private void ignoredReplacement() {
                Log.w("StreamView", "Ignoring IME replacement outside the unsent composing span");
            }
            @Override public boolean setSelection(int start, int end) {
                if (start < 0 || end < 0 || start > getEditable().length() || end > getEditable().length()) {
                    context.rejectReplacement();
                    ignoredReplacement();
                    return true;
                }
                return super.setSelection(start, end);
            }
            @Override public boolean setComposingRegion(int start, int end) {
                if (!context.composingRegion(start, end)) {
                    ignoredReplacement();
                    return true;
                }
                return super.setComposingRegion(start, end);
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (!context.compose(text == null ? "" : text.toString())) {
                    ignoredReplacement();
                    return true;
                }
                return super.setComposingText(text, newCursorPosition);
            }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                if (!context.commit(text == null ? "" : text.toString())) {
                    ignoredReplacement();
                    return true;
                }
                // Cursor requests cannot move the remote caret; only the composing span is local.
                clearLocalText();
                return true;
            }
            @Override public boolean finishComposingText() {
                context.finish();
                clearLocalText();
                return true;
            }
            private boolean deleteText(int beforeLength, int afterLength, boolean codePoints) {
                if (beforeLength < 0 || afterLength < 0) return false;
                ImeTextBuffer.LocalDeletion deletion = context.delete(beforeLength, afterLength, codePoints,
                        Selection.getSelectionStart(getEditable()), Selection.getSelectionEnd(getEditable()));
                getEditable().delete(deletion.end, deletion.end + deletion.afterUnits);
                getEditable().delete(deletion.start - deletion.beforeUnits, deletion.start);
                return true;
            }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return deleteText(beforeLength, afterLength, false);
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                return deleteText(beforeLength, afterLength, true);
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                boolean handled = dispatchImeKeyEvent(event, inputCallbacks);
                if (handled && event.getAction() == KeyEvent.ACTION_MULTIPLE &&
                        event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && event.getCharacters() != null) {
                    context.remember(event.getCharacters());
                    clearLocalText();
                }
                if (handled && event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN) context.forgetTail();
                return handled;
            }
            @Override public boolean performEditorAction(int actionCode) {
                finishComposingText();
                if (inputCallbacks != null) inputCallbacks.commitText("\n");
                return true;
            }
        };
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    public static boolean dispatchImeKeyEvent(KeyEvent event, InputCallbacks callbacks) {
        if (callbacks == null) return false;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) return callbacks.handleKeyMultiple(event);
        return event.getAction() == KeyEvent.ACTION_DOWN ? callbacks.handleKeyDown(event) :
                event.getAction() == KeyEvent.ACTION_UP && callbacks.handleKeyUp(event);
    }

    public interface InputCallbacks {
        void commitText(String text);
        void deleteText(int beforeCodePoints, int afterCodePoints);
        boolean handleKeyMultiple(KeyEvent event);
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
    }
}
