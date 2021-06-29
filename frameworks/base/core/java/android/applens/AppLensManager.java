package android.applens;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.util.Log;
import android.content.Context;
import android.view.Display;
import android.content.Intent;
import android.app.Activity;

import java.util.ArrayList;
import java.util.Arrays;

/** @hide */
public class AppLensManager {
    private static final String APPLENS_TAG = "APPLENS(Manager)";

    private static AppLensManager sInstance = null;

    private ArrayList<View> mMigratedViews = null;
    private ArrayList<View> mTargetViews = new ArrayList<View>();


    private Context mContext;
    public int mUiWidth = -1;
    public int mUiHeight = -1;
    public int mUiDpi = -1;
    public int UiOrientation = 0;


    public int mNumDisplay = 0;
    private View mProxyLayout;
    public View mPrimaryTree = null;
    private ArrayList<ViewGroup> mSubtrees;
    public boolean mIsTraversing = false;
    public static int mRecursionDepth = 0;

    private String mPackageName = null;

    public AppLensManager() {
        mSubtrees = new ArrayList<ViewGroup>();
    }

    public static AppLensManager getInstance() {
        synchronized(AppLensManager.class) {
            if (sInstance == null) {
                sInstance = new AppLensManager();
            }
            return sInstance;
        }
    }

    public void setPackageName(String packageName) {
        mPackageName = packageName;
    }

    public ArrayList<ViewGroup> getSubtrees() {
        return mSubtrees;
    }

    public void setPrimaryTree(View root) {
        mPrimaryTree = root;
    }

    public void addTargetView(View view) {
        mTargetViews.add(view);        
    }

    public ArrayList<View> getTargetViews() {
        return mTargetViews;
    }

    public void setMigratedViews(ArrayList<View> migratedViews) {
        mMigratedViews = migratedViews;
    }

    public void setProxyLayout(View layout) {
        mProxyLayout = layout;
    }

    public ArrayList<View> getMigratedViews() {
        return mMigratedViews;
    }

    public View getProxyLayout() {
        return mProxyLayout;
    }


}
