package android.applens;

import android.app.Presentation;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.Button;
import android.view.Display;
import android.view.Gravity;
import android.util.Log;
import android.view.Window;
import android.view.MotionEvent;
import android.graphics.PixelFormat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import android.webkit.WebView;

/** @hide */
public class UIDisplay extends Presentation {
    private static final String APPLENS_TAG = "APPLENS(UIDisplay)";
    
    private AppLensManager mAppLensManager;

    FrameLayout mContentView;
    Context mOuterContext;
    LinearLayout mLayout;
    ArrayList<View> mTargetViews;
    WindowManager.LayoutParams mWindowParams;
    View subtree;
    int mWidth;
    int mHeight;

    public UIDisplay(Context outerContext, Display display, ViewGroup subtree, int width, int height) {
        super(outerContext, display);
        this.mOuterContext = outerContext;
        this.subtree = (View)subtree;
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAppLensManager = AppLensManager.getInstance();
        mTargetViews = mAppLensManager.getTargetViews();

        if (subtree != null) {
            Window window = getWindow();
            mWindowParams = window.getAttributes();
            Log.d("sunjae", "windowparams: " +mWindowParams.width+":"+mWindowParams.height);
            window.setFormat(PixelFormat.TRANSLUCENT);
            window.getDecorView().setMigrated(true);
            mContentView = window.getDecorView().findViewById(android.R.id.content);
            ViewGroup.LayoutParams params = mContentView.getLayoutParams();
            params.width = mWidth;
            params.height = mHeight;
            mContentView.addView(subtree);
            subtree.invalidate();

            mTargetViews = new ArrayList<View>();
            Queue<ViewGroup> queue = new LinkedList<ViewGroup>();
            queue.add(mContentView);

            while (!queue.isEmpty()) {
                ViewGroup parent = queue.poll();
                int childCount = parent.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    View child = parent.getChildAt(i);
                    child.setMigrated(true);

                    if (child instanceof ViewGroup && !(child instanceof WebView))
                        queue.add((ViewGroup)child);
                    else
                        mTargetViews.add(child);
                    subtree.setFocusable(true);
                    subtree.setFocusableInTouchMode(true);
                    mAppLensManager.setMigratedViews(mTargetViews);
                    mAppLensManager.setProxyLayout(subtree);
                }
            }
/**
            for (View view : mTargetViews) {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    int index = parent.indexOfChild(view);
                    int left = view.getLeft();
                    int right = view.getRight();
                    int top = view.getTop();
                    int bottom = view.getBottom();
                    
                    parent.removeView(view);
                    parent.invalidate();
                }
                view.setMigrated(true);
//                mLayout.addView(view);
                mContentView.addView(view);              
                Log.d("sunjae", "view migrated!!");
                mAppLensManager.setProxyLayout(mContentView);
                view.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            **/
        }
    }
}
