/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho.widget;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.v4.widget.SwipeRefreshLayout.OnRefreshListener;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ItemAnimator;
import android.support.v7.widget.RecyclerView.OnScrollListener;
import android.support.v7.widget.SnapHelper;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.Output;
import com.facebook.litho.testing.testrunner.ComponentsTestRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;

/**
 * Tests for {@link RecyclerSpec}
 */
@RunWith(ComponentsTestRunner.class)
public class RecyclerSpecTest {

  private ComponentContext mComponentContext;
  private SectionsRecyclerView mSectionsRecyclerView;
  private LithoRecylerView mRecyclerView;
  private ItemAnimator mAnimator;

  @Before
  public void setup() {
    mComponentContext = new ComponentContext(RuntimeEnvironment.application);
    mSectionsRecyclerView = mock(SectionsRecyclerView.class);
    mRecyclerView = mock(LithoRecylerView.class);
    when(mSectionsRecyclerView.getRecyclerView()).thenReturn(mRecyclerView);
    when(mSectionsRecyclerView.hasBeenDetachedFromWindow()).thenReturn(true);

    mAnimator = mock(RecyclerView.ItemAnimator.class);
    when(mRecyclerView.getItemAnimator()).thenReturn(mAnimator);
  }

  @Test
  public void testRecyclerSpecOnBind() {
    OnRefreshListener onRefreshListener = mock(OnRefreshListener.class);
    Binder<RecyclerView> binder = mock(Binder.class);

    Output<ItemAnimator> oldAnimator = mock(Output.class);

    SnapHelper snapHelper = mock(SnapHelper.class);

    final int size = 3;
    List<RecyclerView.OnScrollListener> scrollListeners = createListOfScrollListeners(size);

    LithoRecylerView.TouchInterceptor touchInterceptor =
        mock(LithoRecylerView.TouchInterceptor.class);

    RecyclerSpec.onBind(
        mComponentContext,
        mSectionsRecyclerView,
        mAnimator,
        binder,
        null,
        scrollListeners,
        snapHelper,
        true,
        touchInterceptor,
        onRefreshListener,
        oldAnimator);

    verify(mSectionsRecyclerView).setEnabled(true);
    verify(mSectionsRecyclerView).setOnRefreshListener(onRefreshListener);
    verify(mSectionsRecyclerView, times(1)).getRecyclerView();
    verify(oldAnimator).set(mAnimator);
    verify(mRecyclerView).setItemAnimator(any(ItemAnimator.class));
    verify(mRecyclerView, times(size)).addOnScrollListener(any(OnScrollListener.class));
    verify(mRecyclerView).setTouchInterceptor(touchInterceptor);
    verify(binder).bind(mRecyclerView);
    verify(mRecyclerView, times(1)).requestLayout();
    verify(mSectionsRecyclerView).setHasBeenDetachedFromWindow(false);
  }

  @Test
  public void testRecyclerSpecOnUnbind() {
    when(mSectionsRecyclerView.hasBeenDetachedFromWindow()).thenReturn(true);

    Binder<RecyclerView> binder = mock(Binder.class);

    SnapHelper snapHelper = mock(SnapHelper.class);

    final int size = 3;
    List<RecyclerView.OnScrollListener> scrollListeners = createListOfScrollListeners(size);

    RecyclerSpec.onUnbind(
        mComponentContext,
        mSectionsRecyclerView,
        binder,
        null,
        scrollListeners,
        mAnimator);

    verify(mRecyclerView).setItemAnimator(mAnimator);
    verify(binder).unbind(mRecyclerView);
    verify(mRecyclerView, times(size)).removeOnScrollListener(any(OnScrollListener.class));
    verify(mSectionsRecyclerView).setOnRefreshListener(null);
  }

  private static List<RecyclerView.OnScrollListener> createListOfScrollListeners(int size) {
    List<RecyclerView.OnScrollListener> onScrollListeners = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      onScrollListeners.add(mock(RecyclerView.OnScrollListener.class));
    }

    return onScrollListeners;
  }
}
