package com.lpnovi.radiation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Holds the Studio's live widget preview and keeps every touch from reaching it: the preview is a
 * look, not a widget (it's also rendered without PendingIntents). A future version can map touches
 * here to the matching settings section instead.
 */
public class PreviewFrame extends FrameLayout {

    public PreviewFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @SuppressLint("ClickableViewAccessibility") // deliberately inert: nothing to perform
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}
