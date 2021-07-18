package com.android.server.display;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**@hide*/
final class OffScreenDisplayAdapter extends DisplayAdapter {
    static final String TAG="LENS";
    
    private static final String UNIQUE_ID_PREFIX = "OffScreen:";

    private final Handler mUIHandler;
    private boolean mDefaultVisible = false;
    private Context mContext;
    private int numApp = -1;
    private final ArrayList<OffScreenDisplayHandle> mOffScreens = 
        new ArrayList<OffScreenDisplayHandle>();

    public OffScreenDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, Handler uiHandler) {
        super(syncRoot, context, handler, listener, TAG);
        mContext = context;
        mUIHandler = uiHandler;
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
    }

    public void setDefaultVisibility(boolean visible) {
        mDefaultVisible = visible;
    }

    public int createOffScreenDisplay() {
        numApp++;
        updateOffScreenDisplayDevices();
        Slog.w(TAG, "createOffScreenDisplay");
        return mOffScreens.size();
    }

    public void hideOffScreenDisplay() {
        for (OffScreenDisplayHandle off : mOffScreens) {
            off.hideLocked();
        }
    }

    public void showOffScreenDisplay() {
        for (OffScreenDisplayHandle off : mOffScreens) {
            off.visualizeLocked();
        }
    }

    private void updateOffScreenDisplayDevices() {
        synchronized(getSyncRoot()){
            updateOffScreenDisplayDevicesLocked();
        }
    }

    private void updateOffScreenDisplayDevicesLocked() {
        int width = 1440;
        int height = 3040;

        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        //int width = metrics.widthPixels;
        //int height = metrics.heightPixels;

        int densityDpi = metrics.densityDpi;

        OffScreenMode mode = new OffScreenMode(width, height, densityDpi);
        String name = "OffScreen #" + numApp;
        Slog.w(TAG, "creating "+name);
        mOffScreens.add(new OffScreenDisplayHandle(name, mode, numApp));
    }

    public void dismissOffScreenDisplay() {
        for (OffScreenDisplayHandle off : mOffScreens) {
            off.dismissLocked();
        }
        numApp = -1;
    }

    private class OffScreenDisplayDevice extends DisplayDevice {
        private final String mName;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final OffScreenMode mRawMode;
        private final Display.Mode mMode;

        private int mState;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mActiveMode;

        public OffScreenDisplayDevice(IBinder displayToken, String name, OffScreenMode mode, 
                 float  refreshRate, long presentationDeadlineNanos, int state,
                SurfaceTexture surfaceTexture, int number) {
            super(OffScreenDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + number);
            mName = name;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mSurfaceTexture = surfaceTexture;
            mRawMode = mode;
            mMode = createMode(mode.mWidth, mode.mHeight, refreshRate);
            mState = state;    
        }

        public void destroyLocked() {
            mSurfaceTexture = null;
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
        }

        public void setStateLocked(int state) {
            mState = state;
            mInfo = null;
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public void performTraversalLocked(SurfaceControl.Transaction t) {
            if (mSurfaceTexture != null) {
                if (mSurface == null) {
                    mSurface = new Surface(mSurfaceTexture);
                }
                setSurfaceLocked(t, mSurface);
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                Display.Mode mode = mMode;
                OffScreenMode rawMode = mRawMode;
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mode.getPhysicalWidth();
                mInfo.height = mode.getPhysicalHeight();
                mInfo.modeId = mode.getModeId();
                mInfo.defaultModeId = mode.getModeId();
                mInfo.supportedModes = new Display.Mode[] {mMode};
                mInfo.densityDpi = rawMode.mDensityDpi;
                mInfo.xDpi = rawMode.mDensityDpi;
                mInfo.yDpi = rawMode.mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                    1000000000L / (int) mRefreshRate;
                mInfo.flags = DisplayDeviceInfo.FLAG_PRESENTATION;
                mInfo.type = Display.TYPE_OVERLAY;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.state = mState;
            }
            return mInfo;
        }
    }

    private static final class OffScreenMode {
        final int mWidth;
        final int mHeight;
        final int mDensityDpi;

        OffScreenMode(int width, int height, int densityDpi) {
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
        }
    }
    private final class OffScreenDisplayHandle implements OffScreenDisplayWindow.Listener {
        private static final int DEFAULT_MODE_INDEX = 0;

        private final String mName;
        private final OffScreenMode mMode;
        private final int mNumber;

        private OffScreenDisplayWindow mWindow;
        private OffScreenDisplayDevice mDevice;

        public OffScreenDisplayHandle(String name, OffScreenMode mode, int number) {
            mName = name;
            mMode = mode;
            mNumber = number;

            showLocked();
        }

        private void showLocked() {
            mUIHandler.post(mShowRunnable);
        }

        private void hideLocked() {
            mUIHandler.post(mHideRunnable);
        }

        private void visualizeLocked() {
            mUIHandler.post(mVisualizeRunnable);
        }

        private void dismissLocked() {
            mUIHandler.removeCallbacks(mShowRunnable);
            mUIHandler.post(mDismissRunnable);
        }

        @Override
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate, long presentationDeadlineNanos, int state){
            synchronized(getSyncRoot()) {
                IBinder displayToken = SurfaceControl.createDisplay(mName, false /* secure? */);
                mDevice = new OffScreenDisplayDevice(displayToken, mName, mMode, refreshRate, presentationDeadlineNanos,
                        state, surfaceTexture, mNumber);
               sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_ADDED); 
            }
        }

        @Override
        public void onWindowDestroyed() {
            synchronized (getSyncRoot()) {
                if (mDevice != null) {
                    mDevice.destroyLocked();
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_REMOVED);
                }
            }
        }

        @Override
        public void onStateChanged(int state) {
            synchronized (getSyncRoot()) {
                if (mDevice != null) {
                    mDevice.setStateLocked(state);
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_CHANGED);
                }
            }
        }

        private Runnable mVisualizeRunnable = new Runnable() {
            @Override
            public void run() {
                OffScreenDisplayWindow window;
                synchronized(getSyncRoot()) {
                    window = mWindow;
                    window.showOffScreenDisplay();
                }
            }
        };

        private final Runnable mHideRunnable = new Runnable() {
            @Override
            public void run() {
                OffScreenDisplayWindow window;
                synchronized(getSyncRoot()) {
                    window = mWindow;
                    try {
                        window.hideOffScreenDisplay();
                    } catch (NullPointerException e) {
                        Slog.w("LENS", "OffScreen not created yet!");
                        e.printStackTrace();
                    }
                }
            }
        };

        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                OffScreenMode mode = mMode;
                OffScreenDisplayWindow window = new OffScreenDisplayWindow(getContext(), mName, mode.mWidth, mode.mHeight,
                        mode.mDensityDpi, mDefaultVisible, false, OffScreenDisplayHandle.this);
                window.show();

                synchronized (getSyncRoot()) {
                    mWindow = window;
                }
            }
        };
        
        private final Runnable mDismissRunnable = new Runnable() {
            @Override
            public void run() {
                OffScreenDisplayWindow window;
                synchronized (getSyncRoot()) {
                    window = mWindow;
                    mWindow = null;
                }
                if (window != null) {
                    window.dismiss();
                }
            }
        };
   } 
}
