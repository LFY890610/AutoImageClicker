package com.lfy.autoimageclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static volatile WeakReference<AutoClickAccessibilityService> instance = new WeakReference<>(null);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Fast path: react to UI changes immediately instead of waiting for the next screenshot.
        if (CaptureService.isRunning()) {
            CaptureService.requestAccessibilityFastPath();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = new WeakReference<>(null);
        return super.onUnbind(intent);
    }

    public static boolean isConnected() {
        return instance.get() != null;
    }

    public static boolean clickAt(float x, float y) {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    /** Click the lowest visible matching node. Useful for the newest visible WeChat red packet. */
    public static boolean clickLatestTextIfPresent(String... terms) {
        return clickBestTextNode(true, terms);
    }

    /** Click the matching node nearest to screen center. Useful for the open button. */
    public static boolean clickCenterTextIfPresent(String... terms) {
        return clickBestTextNode(false, terms);
    }

    public static boolean clickTextIfPresent(String... terms) {
        return clickBestTextNode(false, terms);
    }

    private static boolean clickBestTextNode(boolean preferBottom, String... terms) {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo best = null;
        double bestScore = -Double.MAX_VALUE;
        int visited = 0;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        float cx = rootBounds.exactCenterX();
        float cy = rootBounds.exactCenterY();
        if (cx <= 0) cx = 540;
        if (cy <= 0) cy = 960;

        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) continue;

            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (node.isVisibleToUser() && (matches(text, terms) || matches(desc, terms))) {
                AccessibilityNodeInfo clickable = node;
                int parents = 0;
                while (clickable != null && !clickable.isClickable() && parents < 7) {
                    clickable = clickable.getParent();
                    parents++;
                }
                if (clickable != null && clickable.isVisibleToUser()) {
                    Rect r = new Rect();
                    clickable.getBoundsInScreen(r);
                    if (!r.isEmpty()) {
                        double score;
                        if (preferBottom) {
                            score = r.bottom * 10.0 + r.centerY();
                        } else {
                            double dx = r.exactCenterX() - cx;
                            double dy = r.exactCenterY() - cy;
                            score = -(dx * dx + dy * dy);
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = clickable;
                        }
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean matches(CharSequence value, String... terms) {
        if (value == null) return false;
        String s = value.toString().trim();
        for (String term : terms) {
            if (term != null && !term.isEmpty() && s.contains(term)) return true;
        }
        return false;
    }
}
