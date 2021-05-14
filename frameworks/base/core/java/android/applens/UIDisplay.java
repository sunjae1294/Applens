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

/** @hide */
public class UIDisplay extends Presentation {
    private static final String APPLENS_TAG = "APPLENS(UIDisplay)";
    
    private AppLensManager mAppLensManager;

    ViewGroup mContentView;
    Context mOuterContext;
    LinearLayout mLayout;
    ArrayList<View> mTargetViews;
    WindowManager.LayoutParams mWindowParams;

    public UIDisplay(Context outerContext, Display display) {
        super(outerContext, display);
        this.mOuterContext = outerContext;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAppLensManager = AppLensManager.getInstance();
        mTargetViews = mAppLensManager.getTargetViews();
        if (mTargetViews != null) {
            Window window = getWindow();
            mWindowParams = window.getAttributes();
            window.setFormat(PixelFormat.TRANSLUCENT);
            mContentView = (ViewGroup)window.getDecorView().findViewById(android.R.id.content);
//            mLayout = new LinearLayout(getContext());
//            mLayout.setBackgroundColor(0x00000000);
//            mContentView.setBackgroundColor(0x00000000);
//            mContentView.setAlpha(0.0f);
//            mLayout.setAlpha(0.0f);
//            mLayout.setOrientation(LinearLayout.VERTICAL);
//            mContentView.addView(mLayout);

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

                view.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }
    }
}
