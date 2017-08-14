/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho.dataflow;

import android.support.v4.util.Pair;
import java.util.ArrayList;

/** TimingSource and Choreographer implementation that allows manual stepping by frame in tests. */
public class MockTimingSource implements TimingSource, ChoreographerCompat {

  public static int FRAME_TIME_MS = 16;

  private static final long FRAME_TIME_NANOS = (long) (FRAME_TIME_MS * 1e6);

  private final ArrayList<Pair<FrameCallback, Long>> mChoreographerCallbacksToStartTimes =
      new ArrayList<>();
  private DataFlowGraph mDataFlowGraph;
  private boolean mIsRunning = false;
  private long mCurrentTimeNanos = 0;

  @Override
  public void setDataFlowGraph(DataFlowGraph dataFlowGraph) {
    mDataFlowGraph = dataFlowGraph;
  }

  @Override
  public void start() {
    mIsRunning = true;
  }

  @Override
  public void stop() {
    mIsRunning = false;
  }

  public void step(int numFrames) {
    for (int i = 0; i < numFrames; i++) {
      if (!mIsRunning) {
        return;
      }
      mCurrentTimeNanos += FRAME_TIME_NANOS;
      mDataFlowGraph.doFrame(mCurrentTimeNanos);
      fireChoreographerCallbacks();
    }
  }

  private void fireChoreographerCallbacks() {
    for (int i = 0; i < mChoreographerCallbacksToStartTimes.size(); i++) {
      final Pair<FrameCallback, Long> entry = mChoreographerCallbacksToStartTimes.get(i);
      if (entry.second <= mCurrentTimeNanos) {
        entry.first.doFrame(mCurrentTimeNanos);
        mChoreographerCallbacksToStartTimes.remove(i);
        i--;
      }
    }
  }

  @Override
  public void postFrameCallback(FrameCallback callbackWrapper) {
    postFrameCallbackDelayed(callbackWrapper, 0);
  }

  @Override
  public void postFrameCallbackDelayed(FrameCallback callbackWrapper, long delayMillis) {
    mChoreographerCallbacksToStartTimes.add(
        new Pair<>(callbackWrapper, (long) (mCurrentTimeNanos + delayMillis * 1e6)));
  }

  @Override
  public void removeFrameCallback(FrameCallback callbackWrapper) {
    for (int i = mChoreographerCallbacksToStartTimes.size() - 1; i >= 0; i--) {
      final Pair<FrameCallback, Long> entry = mChoreographerCallbacksToStartTimes.get(i);
      if (entry.first == callbackWrapper) {
        mChoreographerCallbacksToStartTimes.remove(i);
      }
    }
  }
}
