package com.blueberry.youtubeswipetoseek;

import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;

/**
 * Created by hieptran on 24/05/2016.
 */

public class SwipeDetector {
    private final boolean DEBUG = true;
    private final int SWIPE_THRESHOLD_MM = 2;

    private boolean isSwiping = false, isCanceled = false;
    private OnSwipe mOnSwipe;

    private int oriX, oriY;
    private int mOldXSwipeMm, mOldYSwipeMm;

    private float mmToPx;

    SwipeDetector(Resources r, OnSwipe onSwipe) {
        mmToPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1, r.getDisplayMetrics());
        mOnSwipe = onSwipe;
    }


    void onEvent(MotionEvent motionEvent) {
        int action = motionEvent.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (isCanceled) return;
                oriX = (int) motionEvent.getX();
                oriY = (int) motionEvent.getY();
                if (!mOnSwipe.onTouchedDown(oriX, oriY)) {
                    isCanceled = true;
                    return;
                }
                isSwiping = false;
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isSwiping) mOnSwipe.onSwipeStop();
                isSwiping = false;
                isCanceled = false;
                break;

            case MotionEvent.ACTION_MOVE:
                if (isCanceled) return;
                int currentX = (int) motionEvent.getX();
                int currentY = (int) motionEvent.getY();
                int xMm = (int) ((currentX - oriX) / mmToPx);
                int yMm = (int) ((currentY - oriY) / mmToPx);
                int xMmAbs = Math.abs(xMm);
                int yMmAbs = Math.abs(yMm);

                if (BuildConfig.DEBUG) {
                    Log.d("SwipeDetector", String.format("orix %d oriy %d; x %d y %d; xmil %d ymil %d", oriX, oriY, currentX, currentY, xMm, yMm));
                }
                if (!isSwiping && (xMmAbs > SWIPE_THRESHOLD_MM || yMmAbs > SWIPE_THRESHOLD_MM)) {
                    isSwiping = mOnSwipe.onSwipeStart();
                    mOldXSwipeMm = 0;
                    mOldYSwipeMm = 0;
                    break;
                }
                if (isSwiping) {
                    if (xMm != mOldXSwipeMm || yMm != mOldYSwipeMm) {
                        if (xMmAbs > yMmAbs) mOnSwipe.swipeX(xMm);
                        else if (yMmAbs > xMmAbs) mOnSwipe.swipeY(yMm);
                        mOldXSwipeMm = xMm;
                        mOldYSwipeMm = yMm;
                    }
                }
                break;
        }
    }

    interface OnSwipe {
        void swipeX(int mm);
        void swipeY(int mm);

        /**
         *
         * @param x
         * @param y
         * @return false if touch event is canceled
         */
        boolean onTouchedDown(int x, int y);
        void onSwipeStop();
        boolean onSwipeStart();
    }
    enum Direction {HORIZONTAL, VERTICAL}
}
