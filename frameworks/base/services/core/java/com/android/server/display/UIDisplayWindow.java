package com.android.server.display;

import com.android.internal.util.DumpUtils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.InputEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.TextView;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.os.SystemClock;

/**@hide*/
final class UIDisplayWindow {
    private static final String TAG = "UIDisplayWindow";
    private static final boolean DEBUG = true;

    private final float INITIAL_SCALE = 1.0f;
    private final float MIN_SCALE = 0.1f;
    private final float MAX_SCALE = 1.3f;
    private float WINDOW_ALPHA = 1.0f;

    // When true, disables support for moving and resizing the overlay.
    // The window is made non-touchable, which makes it possible to
    // directly interact with the content underneath.
    private final boolean DISABLE_MOVE_AND_RESIZE = false;

    private final Context mContext;
    private final String mName;
    private int mWidth;
    private int mHeight;
    private int mDensityDpi;
    private int mDisplayId;
    private final boolean mSecure;
    private final Listener mListener;
    private final DisplayManager mDisplayManager;
    private final WindowManager mWindowManager;
    private final InputManager mInputManager;

    private final Display mDefaultDisplay;
    private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    private View mWindowContent;
    private WindowManager.LayoutParams mWindowParams;
    private TextureView mTextureView;
    private TextView mTitleTextView;

    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleGestureDetector;

    private boolean mWindowVisible;
    private int mWindowX;
    private int mWindowY;
    private float mWindowScale;

    private boolean mIsDefSet = false;
    private int defWindowX;
    private int defWindowY;
    private float defWindowScaleX;
    private float defWindowScaleY;

    private float mLiveTranslationX;
    private float mLiveTranslationY;
    private float mLiveScale = 1.0f;
    private boolean mIsRight;
    private boolean mIsLoading;
    private boolean mResizeMode = true;

    public UIDisplayWindow(Context context, String name,
            int width, int height, int densityDpi, boolean mDefaultResize, boolean visible,boolean secure, boolean isRight, boolean isLoading,
            Listener listener) {
        ThreadedRenderer.disableVsync();
        mContext = context;
        mName = name;
        mSecure = secure;
        mListener = listener;

        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mInputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (!visible || isLoading) {
            WINDOW_ALPHA = 0.0f;
        } else {
            WINDOW_ALPHA = 1.0f;
        }

        mDefaultDisplay = mWindowManager.getDefaultDisplay();
        updateDefaultDisplayInfo();

        resize(width, height, densityDpi, false /* doLayout */);
        mIsRight = isRight;
        mIsLoading = isLoading;
        setResizeMode(mDefaultResize);
	createWindow();
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    public void setDisplayId(int displayId) {
        mDisplayId = displayId;
    }
    
    public void show() {
        if (!mWindowVisible) {
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
            if (!updateDefaultDisplayInfo()) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
                return;
            }

            clearLiveState();
            updateWindowParams();
            mWindowManager.addView(mWindowContent, mWindowParams);
            mWindowVisible = true;
        }
     }

    public void dismiss() {
        if (mWindowVisible) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            mWindowManager.removeView(mWindowContent);
            mWindowVisible = false;
        }
    }
   public void setResizeMode(boolean mode) {
    mResizeMode = mode;
   }  
    
    public void resize(int width, int height, int densityDpi) {
        resize(width, height, densityDpi, true);
    }

    private void resize(int width, int height, int densityDpi, boolean doLayout) {
        mWidth = width;
        mHeight = height;
        mDensityDpi = densityDpi;
        if (doLayout) {
            relayout();
        }
    }

    public void relayout() {
        if (mWindowVisible) {
            updateWindowParams();
            mWindowManager.updateViewLayout(mWindowContent, mWindowParams);
            mWindowContent.invalidate();
        }
    }
    
    private boolean updateDefaultDisplayInfo() {
        if (!mDefaultDisplay.getDisplayInfo(mDefaultDisplayInfo)) {
            Slog.w(TAG, "no default display to draw on");
            return false;
        }
        return true;
    }

    private void createWindow() {
        LayoutInflater inflater = LayoutInflater.from(mContext);

        mWindowContent = inflater.inflate(
                com.android.internal.R.layout.overlay_display_window, null);
        mWindowContent.setOnTouchListener(mOnTouchListener);

        mTextureView = (TextureView)mWindowContent.findViewById(
                com.android.internal.R.id.overlay_display_window_texture);
        mTextureView.setPivotX(0);
        mTextureView.setPivotY(0);
        mTextureView.getLayoutParams().width = mWidth;
        mTextureView.getLayoutParams().height = mHeight;
        mTextureView.setOpaque(true); //false
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

        mWindowParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY, 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN 
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                ^ WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
              PixelFormat.TRANSLUCENT);
        if (mIsLoading)
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        if (mSecure) {
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        if (mIsRight || mIsLoading) {
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        mWindowParams.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        mWindowParams.alpha = WINDOW_ALPHA;
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;

        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        if (mIsLoading) {
            mWindowX = (int)(metrics.widthPixels/2) - (int)(mWidth/2);
            mWindowY = (int)(metrics.heightPixels/2) - (int)(mHeight/2);
        } else {
            mWindowX = mIsRight ? (int)(metrics.widthPixels/2) : 0;
            mWindowY = 0;
        }

        mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        mScaleGestureDetector = new ScaleGestureDetector(mContext, mOnScaleGestureListener);

        mWindowScale = INITIAL_SCALE;
	relayoutUIDisplay(100,1500, 1.0f);
    }

    public void relayoutUIDisplay(float x, float y, float scale, float scaleY) {
        mWindowScale = scale;
        mWindowX = (int)x;
        mWindowY = (int)y;
        relayout();
    }

    public void relayoutUIDisplay(float x, float y, float scale) {
        if(!mIsDefSet) {
            defWindowScaleX = scale;
            defWindowScaleY = scale;
            defWindowX = (int)x;
            defWindowY = (int)y;
            mIsDefSet = true;

            mWindowScale = scale;
            mWindowX = (int)x;
            mWindowY = (int)y;
        }
        mLiveTranslationX =  ((int)x) - defWindowX;
        mLiveTranslationY = ((int)y) - defWindowY;
        mLiveScale = scale / defWindowScaleX;
/*
        defWindowScaleX = scale;
        defWindowScalY = scale;
        defWindowX = (int)x;
        defWindowY = (int)y;
        
        mWindowScaleX = scale;
        mWindowScaleY = scale;
        mWindowX = (int)x;
        mWindowY = (int)y;*/
        relayout();

    }

    public void hideUIDisplay() {
        Slog.w("sunjae", "hide ui displays");
        WINDOW_ALPHA = 0.0f;
        mWindowParams.alpha = WINDOW_ALPHA;
        mWindowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        relayout();
        mWindowVisible = false;

    }

    public void showUIDisplay() {
        WINDOW_ALPHA = 1.0f;
        mWindowParams.alpha = WINDOW_ALPHA;
        mWindowParams.flags ^= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
	mWindowVisible = true;
        relayout();
    }

    private void updateWindowParams() {
        float scale = mWindowScale * mLiveScale;
        scale = Math.min(scale, (float)mDefaultDisplayInfo.logicalWidth / mWidth);
        scale = Math.min(scale, (float)mDefaultDisplayInfo.logicalHeight / mHeight);
	scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        
	float offsetScale = (scale / mWindowScale - 1.0f) * 0.5f;
	int width = (int)(mWidth * scale);
	int height = (int)(mHeight * scale);
	int x = (int)(mWindowX + mLiveTranslationX - width * offsetScale);	
	int y = (int)(mWindowY + mLiveTranslationY - width * offsetScale);	
//	x = Math.max(0, Math.min(x, mDefaultDisplayInfo.logicalWidth - width));
//	y = Math.max(0, Math.min(y, mDefaultDisplayInfo.logicalHeight - height));


	/*
	
        mTextureView.getLayoutParams().width = mWidth;
        mTextureView.getLayoutParams().height = mHeight;
        */
        mTextureView.setScaleX(scale);
        mTextureView.setScaleY(scale);

        mWindowParams.x = x;
        mWindowParams.y = y;
        mWindowParams.width = width;
        mWindowParams.height = height;
        // Not use
    }

    private void saveWindowParams() {
        mWindowX = mWindowParams.x;
        mWindowY = mWindowParams.y;
        mWindowScale = mTextureView.getScaleX();
        clearLiveState();
    }

    private void clearLiveState(){
        mLiveTranslationX = 0f;
        mLiveTranslationY = 0f;
        mLiveScale = 1.0f;
    }

    private final DisplayManager.DisplayListener mDisplayListener =
        new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }
            
            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == mDefaultDisplay.getDisplayId()) {
                    if (updateDefaultDisplayInfo()){
                        relayout();
                        mListener.onStateChanged(mDefaultDisplayInfo.state);                    
                    } else {
                        dismiss();
                    }
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == mDefaultDisplay.getDisplayId()) {
                    dismiss();
                }
            }
        };

    private final SurfaceTextureListener mSurfaceTextureListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            mListener.onWindowCreated(surfaceTexture,
                    mDefaultDisplayInfo.getMode().getRefreshRate(),
                    mDefaultDisplayInfo.presentationDeadlineNanos, mDefaultDisplayInfo.state);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            mListener.onWindowDestroyed();
            return true;
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };

    private long lastTouchDown;

    private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (!mResizeMode) {
                float oldX = event.getX();
                float oldY = event.getY();
                float newX = oldX / mWindowScale;
                float newY = oldY / mWindowScale;
    //            event.setLocation(newX, newY);


                long now = SystemClock.uptimeMillis();
                int action = event.getAction();

                if (action == MotionEvent.ACTION_DOWN) {
                    lastTouchDown = now;
                } 
                
    //            MotionEvent newEvent = MotionEvent.obtain(lastTouchDown, now, action, newX, newY,0);
                MotionEvent newEvent = MotionEvent.obtainNoHistory(event);
                newEvent.setLocation(newX, newY);            
                ((InputEvent)newEvent).setDisplayId(mDisplayId);
                mInputManager.injectInputEvent(newEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                
    //            mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                
                return true;
            } else {
                final float oldX = event.getX();
                final float oldY = event.getY();
                event.setLocation(event.getRawX(), event.getRawY());

                mGestureDetector.onTouchEvent(event);
                mScaleGestureDetector.onTouchEvent(event);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveWindowParams();
                        break;
                }
                event.setLocation(oldX,oldY);
                return true;
            }
        }
    };

    private final GestureDetector.OnGestureListener mOnGestureListener = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            mLiveTranslationX -= distanceX;
            mLiveTranslationY -= distanceY;
            relayout();
            return true;
        }
    };

    private final ScaleGestureDetector.OnScaleGestureListener mOnScaleGestureListener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!Float.isNaN(detector.getScaleFactor())) {
                mLiveScale *= detector.getScaleFactor();
                relayout();
            }
            return true;
        }
    };

    public interface Listener {
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate, long presentationDeadlineNanos, int state);
        public void onWindowDestroyed();
        public void onStateChanged(int state);
    }

}




















