package com.suyashsrijan.autoscrollr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class ScrollrService extends AccessibilityService {

    private static final String TAG = "AutoScrollr";
    private static final String TIKTOK_PACKAGE = "com.zhiliaoapp.musically";
    private static final String TIKTOK_PACKAGE_ALT = "com.ss.android.ugc.trill";

    private static final long CHECK_INTERVAL = 1000;
    private static final long SAFE_SCROLL_TIME = 20000;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable monitorRunnable;

    private boolean running = false;
    private boolean scrolling = false;

    private long lastScrollTime = 0;
    private String lastVideoUser = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if (event.getPackageName() == null) return;

        String pkg = event.getPackageName().toString();

        if (!pkg.equals(TIKTOK_PACKAGE) &&
            !pkg.equals(TIKTOK_PACKAGE_ALT)) return;

        if (!running) {
            running = true;
            startMonitor();
        }
    }

    @Override
    public void onInterrupt() {}

    // ================= MONITOR =================

    private void startMonitor() {

        monitorRunnable = new Runnable() {

            @Override
            public void run() {

                if (scrolling) {
                    handler.postDelayed(this, CHECK_INTERVAL);
                    return;
                }

                // 1️⃣ seekbar progress
                float[] progress = getSeekbarProgress();

                if (progress != null) {

                    float percent = (progress[0] / progress[1]) * 100f;

                    if (percent >= 99f) {
                        scrollVideo();
                        return;
                    }
                }

                // 2️⃣ timestamp
                long[] time = readTimestamp();

                if (time != null) {

                    long remaining = time[1] - time[0];

                    if (remaining <= 1) {
                        scrollVideo();
                        return;
                    }
                }

                // 3️⃣ video baru
                if (isNewVideo()) {
                    lastScrollTime = System.currentTimeMillis();
                }

                // 4️⃣ fallback timer
                if (System.currentTimeMillis() - lastScrollTime > SAFE_SCROLL_TIME) {
                    scrollVideo();
                    return;
                }

                handler.postDelayed(this, CHECK_INTERVAL);
            }
        };

        handler.post(monitorRunnable);
    }

    // ================= SCROLL =================

    private void scrollVideo() {

        scrolling = true;

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;

        Path path = new Path();
        path.moveTo(w / 2f, h * 0.85f);
        path.lineTo(w / 2f, h * 0.15f);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path,0,600))
                        .build();

        dispatchGesture(gesture,null,null);

        lastScrollTime = System.currentTimeMillis();

        handler.postDelayed(() -> scrolling = false,1500);
    }

    // ================= TIMESTAMP =================

    private long[] readTimestamp() {

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        List<String> texts = new ArrayList<>();
        collectText(root,texts);

        root.recycle();

        for (String t : texts) {

            if (t.contains("/")) {

                String[] parts = t.split("/");

                if (parts.length == 2) {

                    long current = parseTime(parts[0]);
                    long total = parseTime(parts[1]);

                    if (current >= 0 && total > 0)
                        return new long[]{current,total};
                }
            }
        }

        return null;
    }

    private void collectText(AccessibilityNodeInfo node,List<String> list){

        if (node == null) return;

        if (node.getText()!=null)
            list.add(node.getText().toString());

        for (int i=0;i<node.getChildCount();i++){

            AccessibilityNodeInfo child=node.getChild(i);

            collectText(child,list);

            if (child!=null) child.recycle();
        }
    }

    private long parseTime(String s){

        s=s.trim();

        try{

            if(s.contains(":")){

                String[] p=s.split(":");

                return Long.parseLong(p[0])*60+
                        Long.parseLong(p[1]);
            }

            return Long.parseLong(s);

        }catch(Exception e){}

        return -1;
    }

    // ================= SEEKBAR =================

    private float[] getSeekbarProgress(){

        AccessibilityNodeInfo root=getRootInActiveWindow();

        if(root==null) return null;

        if(root.getRangeInfo()!=null){

            float c=root.getRangeInfo().getCurrent();
            float m=root.getRangeInfo().getMax();

            if(m>0) return new float[]{c,m};
        }

        for(int i=0;i<root.getChildCount();i++){

            AccessibilityNodeInfo child=root.getChild(i);

            float[] r=getSeekbarProgressNode(child);

            if(child!=null) child.recycle();

            if(r!=null) return r;
        }

        root.recycle();

        return null;
    }

    private float[] getSeekbarProgressNode(AccessibilityNodeInfo node){

        if(node==null) return null;

        if(node.getRangeInfo()!=null){

            float c=node.getRangeInfo().getCurrent();
            float m=node.getRangeInfo().getMax();

            if(m>0) return new float[]{c,m};
        }

        for(int i=0;i<node.getChildCount();i++){

            AccessibilityNodeInfo child=node.getChild(i);

            float[] r=getSeekbarProgressNode(child);

            if(child!=null) child.recycle();

            if(r!=null) return r;
        }

        return null;
    }

    // ================= VIDEO BARU =================

    private boolean isNewVideo(){

        AccessibilityNodeInfo root=getRootInActiveWindow();

        if(root==null) return false;

        List<AccessibilityNodeInfo> nodes=
                root.findAccessibilityNodeInfosByText("@");

        for(AccessibilityNodeInfo n:nodes){

            if(n.getText()!=null){

                String user=n.getText().toString();

                if(!user.equals(lastVideoUser)){

                    lastVideoUser=user;

                    root.recycle();

                    return true;
                }
            }
        }

        root.recycle();

        return false;
    }
}
