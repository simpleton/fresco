/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.backends.pipeline.info.internal;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.common.time.MonotonicClock;
import com.facebook.fresco.ui.common.BaseControllerListener2;
import com.facebook.fresco.ui.common.ControllerListener2;
import com.facebook.fresco.ui.common.DimensionsInfo;
import com.facebook.fresco.ui.common.ImageLoadStatus;
import com.facebook.fresco.ui.common.ImagePerfNotifier;
import com.facebook.fresco.ui.common.ImagePerfState;
import com.facebook.fresco.ui.common.OnDrawControllerListener;
import com.facebook.fresco.ui.common.VisibilityState;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.infer.annotation.Nullsafe;
import java.io.Closeable;
import javax.annotation.Nullable;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class ImagePerfControllerListener2 extends BaseControllerListener2<ImageInfo>
    implements OnDrawControllerListener<ImageInfo>, Closeable {

  private static final int WHAT_STATUS = 1;
  private static final int WHAT_VISIBILITY = 2;

  private final MonotonicClock mClock;
  private final ImagePerfState mImagePerfState;
  private final ImagePerfNotifier mImagePerfNotifier;
  private final Supplier<Boolean> mAsyncLogging;

  private static @Nullable Handler sHandler;

  static class LogHandler extends Handler {

    private final ImagePerfNotifier mNotifier;

    public LogHandler(@NonNull Looper looper, @NonNull ImagePerfNotifier notifier) {
      super(looper);
      mNotifier = notifier;
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
      ImagePerfState state = (ImagePerfState) Preconditions.checkNotNull(msg.obj);
      switch (msg.what) {
        case WHAT_STATUS:
          ImageLoadStatus imageLoadStatusFromVal = ImageLoadStatus.Companion.fromInt(msg.arg1);
          if (imageLoadStatusFromVal == null) {
            throw new IllegalArgumentException("Invalid ImageLoadStatus value: " + msg.arg1);
          }
          mNotifier.notifyStatusUpdated(state, imageLoadStatusFromVal);
          break;
        case WHAT_VISIBILITY:
          VisibilityState visibilityStateFromVal = VisibilityState.Companion.fromInt(msg.arg1);
          if (visibilityStateFromVal == null) {
            throw new IllegalArgumentException("Invalid VisibilityState value: " + msg.arg1);
          }
          mNotifier.notifyListenersOfVisibilityStateUpdate(state, visibilityStateFromVal);
          break;
      }
    }
  }

  public ImagePerfControllerListener2(
      MonotonicClock clock,
      ImagePerfState imagePerfState,
      ImagePerfNotifier imagePerfNotifier,
      Supplier<Boolean> asyncLogging) {
    mClock = clock;
    mImagePerfState = imagePerfState;
    mImagePerfNotifier = imagePerfNotifier;

    mAsyncLogging = asyncLogging;
  }

  @Override
  public void onSubmit(
      String id, @Nullable Object callerContext, @Nullable ControllerListener2.Extras extraData) {
    final long now = mClock.now();

    ImagePerfState state = mImagePerfState;
    state.resetPointsTimestamps();

    state.setControllerSubmitTimeMs(now);
    state.setControllerId(id);
    state.setCallerContext(callerContext);

    state.setExtraData(extraData);

    updateStatus(state, ImageLoadStatus.REQUESTED);
    reportViewVisible(state, now);
  }

  @Override
  public void onIntermediateImageSet(String id, @Nullable ImageInfo imageInfo) {
    final long now = mClock.now();

    ImagePerfState state = mImagePerfState;

    state.setControllerIntermediateImageSetTimeMs(now);
    state.setControllerId(id);
    state.setImageInfo(imageInfo);

    updateStatus(state, ImageLoadStatus.INTERMEDIATE_AVAILABLE);
  }

  @Override
  public void onFinalImageSet(
      String id, @Nullable ImageInfo imageInfo, @Nullable ControllerListener2.Extras extraData) {
    final long now = mClock.now();

    ImagePerfState state = mImagePerfState;

    state.setExtraData(extraData);

    state.setControllerFinalImageSetTimeMs(now);
    state.setImageRequestEndTimeMs(now);
    state.setControllerId(id);
    state.setImageInfo(imageInfo);

    updateStatus(state, ImageLoadStatus.SUCCESS);
  }

  @Override
  public void onFailure(
      String id, @Nullable Throwable throwable, @Nullable ControllerListener2.Extras extras) {
    final long now = mClock.now();

    ImagePerfState state = mImagePerfState;

    state.setExtraData(extras);

    state.setControllerFailureTimeMs(now);
    state.setControllerId(id);
    state.setErrorThrowable(throwable);

    updateStatus(state, ImageLoadStatus.ERROR);

    reportViewInvisible(state, now);
  }

  @Override
  public void onRelease(String id, @Nullable ControllerListener2.Extras extras) {
    final long now = mClock.now();

    ImagePerfState state = mImagePerfState;

    state.setExtraData(extras);
    state.setControllerId(id);

    ImageLoadStatus lastImageLoadStatus = state.getImageLoadStatus();
    if (lastImageLoadStatus != ImageLoadStatus.SUCCESS
        && lastImageLoadStatus != ImageLoadStatus.ERROR
        && lastImageLoadStatus != ImageLoadStatus.DRAW) {
      state.setControllerCancelTimeMs(now);
      // The image request was canceled
      updateStatus(state, ImageLoadStatus.CANCELED);
    }

    reportViewInvisible(state, now);
  }

  @Override
  public void onImageDrawn(String id, ImageInfo info, DimensionsInfo dimensionsInfo) {
    ImagePerfState state = mImagePerfState;

    state.setControllerId(id);
    state.setImageDrawTimeMs(mClock.now());
    state.setDimensionsInfo(dimensionsInfo);
    updateStatus(state, ImageLoadStatus.DRAW);
  }

  @VisibleForTesting
  public void reportViewVisible(ImagePerfState state, long now) {
    state.setVisible(true);
    state.setVisibilityEventTimeMs(now);

    updateVisibility(state, VisibilityState.VISIBLE);
  }

  public void resetState() {
    mImagePerfState.reset();
  }

  @Override
  public void close() {
    resetState();
  }

  @VisibleForTesting
  private void reportViewInvisible(ImagePerfState state, long time) {
    state.setVisible(false);
    state.setInvisibilityEventTimeMs(time);

    updateVisibility(state, VisibilityState.INVISIBLE);
  }

  private void updateStatus(ImagePerfState state, ImageLoadStatus imageLoadStatus) {
    if (shouldDispatchAsync()) {
      Message msg = Preconditions.checkNotNull(sHandler).obtainMessage();
      msg.what = WHAT_STATUS;
      msg.arg1 = imageLoadStatus.getValue();
      msg.obj = state;
      sHandler.sendMessage(msg);
    } else {
      mImagePerfNotifier.notifyStatusUpdated(state, imageLoadStatus);
    }
  }

  private void updateVisibility(ImagePerfState state, VisibilityState visibilityState) {
    if (shouldDispatchAsync()) {
      Message msg = Preconditions.checkNotNull(sHandler).obtainMessage();
      msg.what = WHAT_VISIBILITY;
      msg.arg1 = visibilityState.getValue();
      msg.obj = state;
      sHandler.sendMessage(msg);
    } else {
      // sync
      mImagePerfNotifier.notifyListenersOfVisibilityStateUpdate(state, visibilityState);
    }
  }

  private synchronized void initHandler() {
    if (sHandler != null) {
      return;
    }
    HandlerThread handlerThread = new HandlerThread("ImagePerfControllerListener2Thread");
    handlerThread.start();
    Looper looper = Preconditions.checkNotNull(handlerThread.getLooper());
    sHandler = new LogHandler(looper, mImagePerfNotifier);
  }

  private boolean shouldDispatchAsync() {
    boolean enabled = mAsyncLogging.get();
    if (enabled && sHandler == null) {
      initHandler();
    }
    return enabled;
  }

  @Override
  public void onEmptyEvent(@androidx.annotation.Nullable Object callerContext) {
    mImagePerfNotifier.notifyStatusUpdated(mImagePerfState, ImageLoadStatus.EMPTY_EVENT);
  }
}
