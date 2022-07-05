/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy;

import android.content.AutofillOptions;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.contentcapture.ContentCaptureManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

/**applens: start */
import android.app.Activity;
import android.view.View;
import android.view.MotionEvent;
import android.util.Log;
/**applens: end */

/**
 * Context for decor views which can be seeded with pure application context and not depend on the
 * activity, but still provide some of the facilities that Activity has,
 * e.g. themes, activity-based resources, etc.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class DecorContext extends ContextThemeWrapper {
    private PhoneWindow mPhoneWindow;
    private WindowManager mWindowManager;
    private Resources mActivityResources;
    private ContentCaptureManager mContentCaptureManager;

    private WeakReference<Context> mActivityContext;



    @VisibleForTesting
    public DecorContext(Context context, Context activityContext) {
        super(context.createDisplayContext(activityContext.getDisplay()), null);
        mActivityContext = new WeakReference<>(activityContext);
        mActivityResources = activityContext.getResources();
    }

    void setPhoneWindow(PhoneWindow phoneWindow) {
        mPhoneWindow = phoneWindow;
        mWindowManager = null;
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.WINDOW_SERVICE.equals(name)) {
            if (mWindowManager == null) {
                WindowManagerImpl wm =
                        (WindowManagerImpl) super.getSystemService(Context.WINDOW_SERVICE);
                mWindowManager = wm.createLocalWindowManager(mPhoneWindow);
            }
            return mWindowManager;
        }
        if (Context.CONTENT_CAPTURE_MANAGER_SERVICE.equals(name)) {
            if (mContentCaptureManager == null) {
                Context activityContext = mActivityContext.get();
                if (activityContext != null) {
                    mContentCaptureManager = (ContentCaptureManager) activityContext
                            .getSystemService(name);
                }
            }
            return mContentCaptureManager;
        }
        return super.getSystemService(name);
    }

    @Override
    public Resources getResources() {
        Context activityContext = mActivityContext.get();
        // Attempt to update the local cached Resources from the activity context. If the activity
        // is no longer around, return the old cached values.
        if (activityContext != null) {
            mActivityResources = activityContext.getResources();
        }

        return mActivityResources;
    }

    @Override
    public AssetManager getAssets() {
        return mActivityResources.getAssets();
    }

    @Override
    public AutofillOptions getAutofillOptions() {
        Context activityContext = mActivityContext.get();
        if (activityContext != null) {
            return activityContext.getAutofillOptions();
        }
        return null;
    }

    @Override
    public ContentCaptureOptions getContentCaptureOptions() {
        Context activityContext = mActivityContext.get();
        if (activityContext != null) {
            return activityContext.getContentCaptureOptions();
        }
        return null;
    }

    /**applens: start */
    /** @hide */
    public boolean createOffScreenDisplay(MotionEvent event) {
        Context context = mActivityContext.get();
        if (context instanceof Activity) {
            return ((Activity)context).createOffScreenDisplay(event);
        }
        return false;
    }

    /** @hide */
    public void fetchSubtree(View view) {
        Context context = mActivityContext.get();
        if (context instanceof Activity) {
            if (((Activity)context).getWatchUpdate()){
                ((Activity)context).fetchSubtree(false, view);
            }
        }
    }

    /** @hide */
    public void parseMacro(View view) {
        Context context = mActivityContext.get();
        if (context instanceof Activity) {
            if (((Activity)context).getMacroUpdate()) {
                ((Activity)context).parseTouch(false, view);
            }
        }
    }

    public boolean bringToFront(MotionEvent ev) {
        Context context = mActivityContext.get();
        if (context instanceof Activity) {
            return ((Activity)context).bringToFront(ev);
        }
        return false;
    }

    /**@hide */
    public boolean triggerUISelection(MotionEvent ev) {
        Context context = mActivityContext.get();
        if (context instanceof Activity) {
            return ((Activity)context).triggerUISelection(ev);
        }
        return false;
    }

    /**applens: end */
}
