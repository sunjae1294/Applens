package com.android.server.display;

import android.app.Activity;
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
final class UIDisplayAdapter extends DisplayAdapter {
    static final String TAG="AppLens";
    
    private static final String UNIQUE_ID_PREFIX = "UIDisplay";

    private final Handler mUIHandler;
    private Context mContext;
    private final ArrayList<UIDisplayHandle> mUIDisps = 
        new ArrayList<UIDisplayHandle>();

    public UIDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, Handler uiHandler) {
        super(syncRoot, context, handler, listener, TAG);
        mContext = context;
        mUIHandler = uiHandler;
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
    }

    public int createUIDisplay(int width, int height) {
        updateUIDisplayDevices(width, height);
        Slog.w(TAG, "createUIDisplay");
        return mUIDisps.size();
    }

    private void updateUIDisplayDevices(int width, int height) {
        synchronized(getSyncRoot()){
            updateUIDisplayDevicesLocked(width, height);
        }
    }

    private void updateUIDisplayDevicesLocked(int width, int height) {
        //iint width = 1440;
        //int height = 3040;

        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        //int width = metrics.widthPixels;
        //int height = metrics.heightPixels;
//        int width = metrics.widthPixels;
//        int height = metrics.heightPixels;

        int densityDpi = metrics.densityDpi;

        ArrayList<UIMode> modes = new ArrayList<>();
        UIMode mode = new UIMode(width, height, densityDpi);
        modes.add(mode);
        String name = "UI #" + modes.size();
        mUIDisps.add(new UIDisplayHandle(name, mode, modes.size()));
    }

    private void dismissUIDisplay() {
        for (UIDisplayHandle ui : mUIDisps) {
            ui.dismissLocked();
        }
    }

    private class UIDisplayDevice extends DisplayDevice {
        private final String mName;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final UIMode mRawMode;
        private final Display.Mode mMode;

        private int mState;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mActiveMode;

        public UIDisplayDevice(IBinder displayToken, String name, UIMode mode, 
                 float  refreshRate, long presentationDeadlineNanos, int state,
                SurfaceTexture surfaceTexture, int number) {
            super(UIDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + number);
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
                UIMode rawMode = mRawMode;
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

    private static final class UIMode {
        final int mWidth;
        final int mHeight;
        final int mDensityDpi;

        UIMode(int width, int height, int densityDpi) {
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
        }
    }
    private final class UIDisplayHandle implements UIDisplayWindow.Listener {
        private static final int DEFAULT_MODE_INDEX = 0;

        private final String mName;
        private final UIMode mMode;
        private final int mNumber;

        private UIDisplayWindow mWindow;
        private UIDisplayDevice mDevice;

        public UIDisplayHandle(String name, UIMode mode, int number) {
            mName = name;
            mMode = mode;
            mNumber = number;

            showLocked();
        }

        private void showLocked() {
            mUIHandler.post(mShowRunnable);
        }

        private void dismissLocked() {
            mUIHandler.removeCallbacks(mShowRunnable);
            mUIHandler.post(mDismissRunnable);
        }

        @Override
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate, long presentationDeadlineNanos, int state){
            synchronized(getSyncRoot()) {
                IBinder displayToken = SurfaceControl.createDisplay(mName, false /* secure? */);
                mDevice = new UIDisplayDevice(displayToken, mName, mMode, refreshRate, presentationDeadlineNanos,
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

        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                UIMode mode = mMode;
                UIDisplayWindow window = new UIDisplayWindow(getContext(), mName, mode.mWidth, mode.mHeight,
                        mode.mDensityDpi,false, UIDisplayHandle.this);
                window.show();

                synchronized (getSyncRoot()) {
                    mWindow = window;
                }
            }
        };
        
        private final Runnable mDismissRunnable = new Runnable() {
            @Override
            public void run() {
                UIDisplayWindow window;
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
