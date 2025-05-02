package com.example.mytpu.schedule;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class WheelLayoutManager extends LinearLayoutManager {

    private static final float SCALE_FACTOR = 0.5f;
    private static final float MIN_SCALE = 0.7f;
    private static final float MAX_SCALE = 1.2f;

    private boolean isPotentialLongPress;
    private Rect itemTouchRect = new Rect();
    private Handler longPressHandler = new Handler();
    private Runnable longPressRunnable;
    private int pendingPosition = -1;
    private RecyclerView recyclerView;
    private OnItemCenteredListener itemCenteredListener;

    public interface OnItemCenteredListener {
        void onItemCentered(int position);
    }

    public void setOnItemCenteredListener(OnItemCenteredListener listener) {
        this.itemCenteredListener = listener;
    }

    public WheelLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    public WheelLayoutManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void snapToCenter() {
        if (recyclerView == null) return;

        View centerView = findCenterView();
        if (centerView != null) {
            int position = getPosition(centerView);
            if (position >= 0 && position < getItemCount()) {
                smoothScrollToPosition(recyclerView, null, position);
            }
        }
    }

    @Override
    public void onAttachedToWindow(@NonNull RecyclerView view) {
        super.onAttachedToWindow(view);
        this.recyclerView = view;

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    snapToCenter();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                scaleItems();
            }
        });

        recyclerView.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private View touchedView;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        touchedView = recyclerView.findChildViewUnder(startX, startY);
                        isPotentialLongPress = touchedView != null;

                        if (isPotentialLongPress) {
                            pendingPosition = getPosition(touchedView);
                            touchedView.getHitRect(itemTouchRect);

                            longPressHandler.postDelayed(longPressRunnable = () -> {
                                if (isPotentialLongPress && pendingPosition >= 0) {
                                    snapToPosition(pendingPosition);
                                }
                            }, 500);
                        }
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (isPotentialLongPress) {
                            float dx = Math.abs(event.getX() - startX);
                            float dy = Math.abs(event.getY() - startY);
                            float slop = ViewConfiguration.get(recyclerView.getContext()).getScaledTouchSlop();

                            if (dx > slop || dy > slop || !itemTouchRect.contains((int) event.getX(), (int) event.getY())) {
                                cancelPendingSnap();
                            }
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelPendingSnap();
                        break;
                }
                return false;
            }
        });
    }

    private void cancelPendingSnap() {
        isPotentialLongPress = false;
        pendingPosition = -1;
        if (longPressRunnable != null) {
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void snapToPosition(int position) {
        if (recyclerView == null || position < 0 || position >= getItemCount()) return;

        recyclerView.stopScroll();

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
            @Override
            protected int getHorizontalSnapPreference() {
                return SNAP_TO_START;
            }

            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 100f / displayMetrics.densityDpi;
            }

            @Override
            protected int calculateTimeForDeceleration(int dx) {
                return Math.min(super.calculateTimeForDeceleration(dx) * 2, 400);
            }
        };

        smoothScroller.setTargetPosition(position);
        startSmoothScroll(smoothScroller);
    }

    @Override
    public int scrollHorizontallyBy(int dx, @NonNull RecyclerView.Recycler recycler, @NonNull RecyclerView.State state) {
        int scrolled = super.scrollHorizontallyBy(dx, recycler, state);
        scaleItems();
        return scrolled;
    }

    @Override
    public void onLayoutCompleted(@NonNull RecyclerView.State state) {
        super.onLayoutCompleted(state);
        scaleItems();
        notifyCenterItem();
    }

    private void notifyCenterItem() {
        View centerView = findCenterView();
        if (centerView != null && itemCenteredListener != null) {
            int position = getPosition(centerView);
            if (position >= 0 && position < getItemCount()) {
                itemCenteredListener.onItemCentered(position);
            }
        }
    }

    public View findCenterView() {
        int center = getWidth() / 2;
        View closestView = null;
        int minDistance = Integer.MAX_VALUE;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) continue;

            int childCenter = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2;
            int distance = Math.abs(childCenter - center);

            if (distance < minDistance) {
                minDistance = distance;
                closestView = child;
            }
        }
        return closestView;
    }

    private void scaleItems() {
        float midpoint = getWidth() / 2f;
        float d1 = getWidth() * SCALE_FACTOR;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null) continue;

            float childMidpoint = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2f;
            float d = Math.min(d1, Math.abs(midpoint - childMidpoint));
            float scale = MAX_SCALE - (d / d1) * (MAX_SCALE - MIN_SCALE);

            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(0.5f + (1 - d / d1) * 0.5f);
        }
    }

    @Override
    public void scrollToPosition(int position) {
        if (position < 0 || position >= getItemCount()) return;
        super.scrollToPosition(position);
        if (recyclerView != null) {
            recyclerView.post(() -> smoothScrollToPosition(recyclerView, null, position));
        }
    }

    @Override
    public void smoothScrollToPosition(
            @NonNull RecyclerView recyclerView,
            RecyclerView.State state,
            int position
    ) {
        if (position < 0 || position >= getItemCount()) return;

        recyclerView.stopScroll();

        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(recyclerView.getContext()) {
            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2);
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 100f / displayMetrics.densityDpi;
            }

            @Override
            protected int calculateTimeForScrolling(int dx) {
                return Math.min(super.calculateTimeForScrolling(dx), 300);
            }

            @Override
            protected int calculateTimeForDeceleration(int dx) {
                return Math.min(super.calculateTimeForDeceleration(dx) * 2, 400);
            }
        };

        smoothScroller.setTargetPosition(position);
        startSmoothScroll(smoothScroller);
    }

    @Override
    public boolean canScrollHorizontally() {
        return getItemCount() > 1;
    }
}
