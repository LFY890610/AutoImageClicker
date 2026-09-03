package com.lfy.autoimageclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
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
        // Screen recognition is handled by CaptureService.
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

    public static boolean clickTextIfPresent(String... terms) {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 500) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (matches(text, terms) || matches(desc, terms)) {
                AccessibilityNodeInfo clickable = node;
                int parents = 0;
                while (clickable != null && !clickable.isClickable() && parents < 6) {
                    clickable = clickable.getParent();
                    parents++;
                }
                if (clickable != null && clickable.isClickable()) {
                    return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
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
