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
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**@hide*/
final class UIDisplayAdapter extends DisplayAdapter {
    static final String TAG="AppLens";
    
    private static final String UNIQUE_ID_PREFIX = "UIDisplay";

    private final Handler mUIHandler;
    private Context mContext;
    private int numUi;
    private final SparseArray<UIDisplayHandle> mUIDisps = 
        new SparseArray<UIDisplayHandle>();

    public UIDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, Handler uiHandler) {
        super(syncRoot, context, handler, listener, TAG);
        mContext = context;
        mUIHandler = uiHandler;
        numUi = -1;
    }

    @Override
    public void registerLocked() {
        super.registerLocked();
    }

    public int createUIDisplay(int width, int height) {
        numUi++;
        updateUIDisplayDevices(width, height);
        // max numUi = 4
        Slog.w(TAG, "createUIDisplay");
        return numUi;
    }

    public int createRightUIDisplay(int width, int height) {
        updateRightUIDisplayDevices(width, height);
        return numUi + 4;
    }

    public int getIdFromName(String deviceName) {
        return Integer.parseInt(deviceName.substring(deviceName.length() -1));
    }

    public void setDisplayId(int displayId, int uiNum) {
        mUIDisps.get(uiNum).setDisplayId(displayId);
    }
    
    public int getLeftUIDisplay(String deviceName) {
        if (deviceName.length() > 8) {
            if (!deviceName.substring(0,8).equals("MirrorUI")) {
                return -1;
            }

            int uiNum = Integer.parseInt(deviceName.substring(deviceName.length() -1)) - 4;
            if (mUIDisps.indexOfKey(uiNum) >= 0) {
                return mUIDisps.get(uiNum).getDisplayId();
            }
        }
        return -1;
    }
/** 
    public int getLeftUIDisplayId(int rightDisplayId) {
        for (UIDisplayHandle uiDisp : mUIDisps) {
            if (uiDisp.getDisplayId() == rightDisplayId) {
                uiDisp.
            }    
        }
    }
*/
    private void updateUIDisplayDevices(int width, int height) {
        synchronized(getSyncRoot()){
            updateUIDisplayDevicesLocked(width, height);
        }
    }
    private void updateRightUIDisplayDevices(int width, int height) {
        synchronized(getSyncRoot()) {
            updateRightUIDisplayDevicesLocked(width, height);
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
        UIMode leftMode = new UIMode(width, height, densityDpi);
        modes.add(leftMode);


        String leftName = "UI #" + numUi;
        mUIDisps.put(numUi,new UIDisplayHandle(leftName, leftMode, numUi));
    }

    private void updateRightUIDisplayDevicesLocked(int width, int height) {
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        int densityDpi = metrics.densityDpi;

        UIMode rightMode = new UIMode(width, height, densityDpi);

        String rightName = "MirrorUI #" + numUi;
        mUIDisps.put(4+numUi, new UIDisplayHandle(rightName, rightMode, 4+numUi));
    }
/*
    public void relayoutUIDisplay(int x, int y, float scale) {
        synchronized(getSyncRoot()){
            relayoutUIDisplayLocked(x,y,scale);
        }
    }
*/
    public void relayoutUIDisplay(int x, int y, float scale, int num) {
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        mUIDisps.get(num).relayoutLocked(x,y,scale);
        if (mUIDisps.indexOfKey(num+4) >= 0) 
            mUIDisps.get((num) + 4).relayoutLocked(x+(int)(metrics.widthPixels/2), y, scale);
    }

    private void dismissUIDisplay() {
        for (int i = 0; i < numUi; i++) {
            mUIDisps.get(i).dismissLocked();
            mUIDisps.get(i+4).dismissLocked();
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

        private int mX;
        private int mY;
        private float mScale;
        private boolean mIsRight;


        public UIDisplayHandle(String name, UIMode mode, int number) {
            mName = name;
            mMode = mode;
            mNumber = number;
            mIsRight = number < 4 ? false : true;

            showLocked();
        }

        public int getNumber() {
            return mNumber;
        }
        public int getDisplayId() {
            return mWindow.getDisplayId();
        }

        public void setDisplayId(int displayId) {
            mWindow.setDisplayId(displayId);
        }

        public boolean isRight() {
            return mIsRight;
        }

        private void relayoutLocked(int x, int y, float scale) {
            mX = x;
            mY = y;
            mScale = scale;
            mUIHandler.post(mRelayoutRunnable);
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
                        mode.mDensityDpi,false, mIsRight, UIDisplayHandle.this);
                window.show();

                synchronized (getSyncRoot()) {
                    mWindow = window;
                }
            }
        };

        private final Runnable mRelayoutRunnable = new Runnable() {
            @Override
            public void run() {
                UIDisplayWindow window;
                synchronized (getSyncRoot()){
                    window = mWindow;
                    window.relayoutUIDisplay(mX, mY, mScale);
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
