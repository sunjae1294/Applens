/*
 * Copyright 2019 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <android-base/stringprintf.h>
#include <android/native_window.h>
#include <compositionengine/CompositionEngine.h>
#include <compositionengine/Display.h>
#include <compositionengine/DisplaySurface.h>
#include <compositionengine/RenderSurfaceCreationArgs.h>
#include <compositionengine/impl/DumpHelpers.h>
#include <compositionengine/impl/RenderSurface.h>
#include <log/log.h>
#include <renderengine/RenderEngine.h>
#include <sync/sync.h>
#include <system/window.h>
#include <ui/GraphicBuffer.h>
#include <ui/Rect.h>
#include <utils/Trace.h>

#include "DisplayHardware/HWComposer.h"

/** applens: start */
#include <string.h>
#include <time.h>
/** applens: end */

namespace android::compositionengine {

RenderSurface::~RenderSurface() = default;

namespace impl {

std::unique_ptr<compositionengine::RenderSurface> createRenderSurface(
        const compositionengine::CompositionEngine& compositionEngine,
        compositionengine::Display& display, compositionengine::RenderSurfaceCreationArgs&& args) {
    return std::make_unique<RenderSurface>(compositionEngine, display, std::move(args));
}

RenderSurface::RenderSurface(const CompositionEngine& compositionEngine, Display& display,
                             RenderSurfaceCreationArgs&& args)
      : mCompositionEngine(compositionEngine),
        mDisplay(display),
        mNativeWindow(args.nativeWindow),
        mDisplaySurface(args.displaySurface),
        mSize(args.displayWidth, args.displayHeight) {
    LOG_ALWAYS_FATAL_IF(!mNativeWindow);
}

RenderSurface::~RenderSurface() {
    ANativeWindow* const window = mNativeWindow.get();
    native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL);
}

bool RenderSurface::isValid() const {
    return mSize.isValid();
}

void RenderSurface::initialize() {
    ANativeWindow* const window = mNativeWindow.get();

    int status = native_window_api_connect(window, NATIVE_WINDOW_API_EGL);
    ALOGE_IF(status != NO_ERROR, "Unable to connect BQ producer: %d", status);
    status = native_window_set_buffers_format(window, HAL_PIXEL_FORMAT_RGBA_8888);
    ALOGE_IF(status != NO_ERROR, "Unable to set BQ format to RGBA888: %d", status);
    status = native_window_set_usage(window, GRALLOC_USAGE_HW_RENDER);
    ALOGE_IF(status != NO_ERROR, "Unable to set BQ usage bits for GPU rendering: %d", status);
}

const ui::Size& RenderSurface::getSize() const {
    return mSize;
}

const sp<Fence>& RenderSurface::getClientTargetAcquireFence() const {
    return mDisplaySurface->getClientTargetAcquireFence();
}

void RenderSurface::setDisplaySize(const ui::Size& size) {
    mDisplaySurface->resizeBuffers(size.width, size.height);
    mSize = size;
}

void RenderSurface::setBufferDataspace(ui::Dataspace dataspace) {
    native_window_set_buffers_data_space(mNativeWindow.get(),
                                         static_cast<android_dataspace>(dataspace));
}

void RenderSurface::setProtected(bool useProtected) {
    uint64_t usageFlags = GRALLOC_USAGE_HW_RENDER;
    if (useProtected) {
        usageFlags |= GRALLOC_USAGE_PROTECTED;
    }
    const int status = native_window_set_usage(mNativeWindow.get(), usageFlags);
    ALOGE_IF(status != NO_ERROR, "Unable to set BQ usage bits for protected content: %d", status);
    if (status == NO_ERROR) {
        mProtected = useProtected;
    }
}

status_t RenderSurface::beginFrame(bool mustRecompose) {
    return mDisplaySurface->beginFrame(mustRecompose);
}

status_t RenderSurface::prepareFrame() {
    auto& hwc = mCompositionEngine.getHwComposer();
    const auto id = mDisplay.getId();
    if (id) {
        status_t error = hwc.prepare(*id, mDisplay);
        if (error != NO_ERROR) {
            return error;
        }
    }

    DisplaySurface::CompositionType compositionType;
    const bool hasClient = hwc.hasClientComposition(id);
    const bool hasDevice = hwc.hasDeviceComposition(id);
    if (hasClient && hasDevice) {
        compositionType = DisplaySurface::COMPOSITION_MIXED;
    } else if (hasClient) {
        compositionType = DisplaySurface::COMPOSITION_GLES;
    } else if (hasDevice) {
        compositionType = DisplaySurface::COMPOSITION_HWC;
    } else {
        // Nothing to do -- when turning the screen off we get a frame like
        // this. Call it a HWC frame since we won't be doing any GLES work but
        // will do a prepare/set cycle.
        compositionType = DisplaySurface::COMPOSITION_HWC;
    }
    return mDisplaySurface->prepareFrame(compositionType);
}

sp<GraphicBuffer> RenderSurface::dequeueBuffer(base::unique_fd* bufferFence) {
    ATRACE_CALL();
    int fd = -1;
    ANativeWindowBuffer* buffer = nullptr;

    status_t result = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buffer, &fd);

    if (result != NO_ERROR) {
        ALOGE("ANativeWindow::dequeueBuffer failed for display [%s] with error: %d",
              mDisplay.getName().c_str(), result);
        // Return fast here as we can't do much more - any rendering we do
        // now will just be wrong.
        return mGraphicBuffer;
    }

    ALOGW_IF(mGraphicBuffer != nullptr, "Clobbering a non-null pointer to a buffer [%p].",
             mGraphicBuffer->getNativeBuffer()->handle);
    mGraphicBuffer = GraphicBuffer::from(buffer);

    *bufferFence = base::unique_fd(fd);

    return mGraphicBuffer;
}

void RenderSurface::queueBuffer(base::unique_fd&& readyFence) {
    auto& hwc = mCompositionEngine.getHwComposer();
    const auto id = mDisplay.getId();

    if (hwc.hasClientComposition(id) || hwc.hasFlipClientTargetRequest(id)) {
        // hasFlipClientTargetRequest could return true even if we haven't
        // dequeued a buffer before. Try dequeueing one if we don't have a
        // buffer ready.
        if (mGraphicBuffer == nullptr) {
            ALOGI("Attempting to queue a client composited buffer without one "
                  "previously dequeued for display [%s]. Attempting to dequeue "
                  "a scratch buffer now",
                  mDisplay.getName().c_str());
            // We shouldn't deadlock here, since mGraphicBuffer == nullptr only
            // after a successful call to queueBuffer, or if dequeueBuffer has
            // never been called.
            base::unique_fd unused;
            dequeueBuffer(&unused);
        }

        if (mGraphicBuffer == nullptr) {
            ALOGE("No buffer is ready for display [%s]", mDisplay.getName().c_str());
        } else {


            status_t result =
                    mNativeWindow->queueBuffer(mNativeWindow.get(),
                                               mGraphicBuffer->getNativeBuffer(), dup(readyFence));
            if (result != NO_ERROR) {
                ALOGE("Error when queueing buffer for display [%s]: %d", mDisplay.getName().c_str(),
                      result);
                // We risk blocking on dequeueBuffer if the primary display failed
                // to queue up its buffer, so crash here.
                if (!mDisplay.isVirtual()) {
                    LOG_ALWAYS_FATAL("ANativeWindow::queueBuffer failed with error: %d", result);
                } else {
                    mNativeWindow->cancelBuffer(mNativeWindow.get(),
                                                mGraphicBuffer->getNativeBuffer(), dup(readyFence));
                }
            }

            mGraphicBuffer = nullptr;
        }
    }

    status_t result = mDisplaySurface->advanceFrame();
    if (result != NO_ERROR) {
        ALOGE("[%s] failed pushing new frame to HWC: %d", mDisplay.getName().c_str(), result);
    }
    /** applens: start */
//    else if (!mDisplay.isVirtual()){
	else if (mDisplay.isVirtual()){    
	    struct timespec aft;
	    clock_gettime(CLOCK_REALTIME, &aft);
	  //  long after = (long)(1000.0 * aft.tv_sec + (double) aft.tv_nsec / 1e6);
//	    ALOGW("vuitton test render	virtual :	end=%ld", after);

		/* applens: start 
	    // save to file.
		int width = mGraphicBuffer->getWidth();
		int height = mGraphicBuffer->getHeight();
		int stride = mGraphicBuffer->getStride();
		
		char filePath[100];
		sprintf(filePath, "/data/misc/pic_%d", after);
		int fd = open(filePath, O_WRONLY | O_CREAT | O_APPEND, 0644);

		uint8_t* bufferImg;
		mGraphicBuffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, (void**)(&bufferImg));
		for (uint32_t y = 0; y < height; y++) {
			for (uint32_t x = 0; x < width; x++) {
				uint32_t pixel = getPixel(x,y,stride,bufferImg);
				write(fd, &pixel, sizeof(pixel));
			}
		}

		mGraphicBuffer->unlock();
	 	applens: end */	
    } else if (!mDisplay.isVirtual()) {
	    struct timespec aft;
	    clock_gettime(CLOCK_REALTIME, &aft);
	 //   long after = (long)(1000.0 * aft.tv_sec + (double) aft.tv_nsec / 1e6);
	//    ALOGW("vuitton test render default	:	end=%ld", after);

    }
    /**  applens: end*/
    
}

/** applens: start */
uint32_t RenderSurface::getPixel(int32_t x, int32_t y, uint32_t stride, uint8_t* img) {
    uint32_t r = img[(y * stride + x) * 4 + 0];
    uint32_t g = img[(y * stride + x) * 4 + 1];
    uint32_t b = img[(y * stride + x) * 4 + 2];
    uint32_t a = img[(y * stride + x) * 4 + 3];


    uint32_t pixel = 0;
    pixel |= r;
    pixel |= g << 8;
    pixel |= b << 16;
    pixel |= a << 24;
    return pixel;
}
/** applens: end */

void RenderSurface::onPresentDisplayCompleted() {
    mDisplaySurface->onFrameCommitted();
}

void RenderSurface::setViewportAndProjection() {
    auto& renderEngine = mCompositionEngine.getRenderEngine();
    Rect sourceCrop = Rect(mSize);
    renderEngine.setViewportAndProjection(mSize.width, mSize.height, sourceCrop,
                                          ui::Transform::ROT_0);
}

void RenderSurface::flip() {
    mPageFlipCount++;
}

void RenderSurface::dump(std::string& out) const {
    using android::base::StringAppendF;

    out.append("   Composition RenderSurface State:");

    out.append("\n   ");

    dumpVal(out, "size", mSize);
    StringAppendF(&out, "ANativeWindow=%p (format %d) ", mNativeWindow.get(),
                  ANativeWindow_getFormat(mNativeWindow.get()));
    dumpVal(out, "flips", mPageFlipCount);
    out.append("\n");

    String8 surfaceDump;
    mDisplaySurface->dumpAsString(surfaceDump);
    out.append(surfaceDump);
}

std::uint32_t RenderSurface::getPageFlipCount() const {
    return mPageFlipCount;
}

void RenderSurface::setPageFlipCountForTest(std::uint32_t count) {
    mPageFlipCount = count;
}

void RenderSurface::setSizeForTest(const ui::Size& size) {
    mSize = size;
}

sp<GraphicBuffer>& RenderSurface::mutableGraphicBufferForTest() {
    return mGraphicBuffer;
}

} // namespace impl
} // namespace android::compositionengine
