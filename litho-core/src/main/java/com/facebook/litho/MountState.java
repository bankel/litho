/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho;

import static android.support.v4.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
import static com.facebook.litho.Component.isHostSpec;
import static com.facebook.litho.Component.isMountViewSpec;
import static com.facebook.litho.ComponentHostUtils.maybeInvalidateAccessibilityState;
import static com.facebook.litho.ComponentHostUtils.maybeSetDrawableState;
import static com.facebook.litho.FrameworkLogEvents.EVENT_MOUNT;
import static com.facebook.litho.FrameworkLogEvents.EVENT_PREPARE_MOUNT;
import static com.facebook.litho.FrameworkLogEvents.EVENT_SHOULD_UPDATE_REFERENCE_LAYOUT_MISMATCH;
import static com.facebook.litho.FrameworkLogEvents.PARAM_IS_DIRTY;
import static com.facebook.litho.FrameworkLogEvents.PARAM_LOG_TAG;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MESSAGE;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MOUNTED_CONTENT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MOUNTED_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MOUNTED_TIME;
import static com.facebook.litho.FrameworkLogEvents.PARAM_MOVED_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_NO_OP_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UNCHANGED_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UNMOUNTED_CONTENT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UNMOUNTED_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UNMOUNTED_TIME;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UPDATED_CONTENT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UPDATED_COUNT;
import static com.facebook.litho.FrameworkLogEvents.PARAM_UPDATED_TIME;
import static com.facebook.litho.FrameworkLogEvents.PARAM_VISIBILITY_HANDLER;
import static com.facebook.litho.FrameworkLogEvents.PARAM_VISIBILITY_HANDLERS_TOTAL_TIME;
import static com.facebook.litho.FrameworkLogEvents.PARAM_VISIBILITY_HANDLER_TIME;
import static com.facebook.litho.ThreadUtils.assertMainThread;

import android.animation.StateListAnimator;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.LongSparseArray;
import android.support.v4.util.SimpleArrayMap;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import com.facebook.infer.annotation.ThreadConfined;
import com.facebook.litho.config.ComponentsConfiguration;
import com.facebook.litho.reference.Reference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the mounted state of a {@link Component}. Provides APIs to update state
 * by recycling existing UI elements e.g. {@link Drawable}s.
 *
 * @see #mount(LayoutState, Rect)
 * @see LithoView
 * @see LayoutState
 */
@ThreadConfined(ThreadConfined.UI)
class MountState implements TransitionManager.OnAnimationCompleteListener {

  static final int ROOT_HOST_ID = 0;
  private static final double NS_IN_MS = 1000000.0;

  // Holds the current list of mounted items.
  // Should always be used within a draw lock.
  private final LongSparseArray<MountItem> mIndexToItemMap;

  // Holds a list with information about the components linked to the VisibilityOutputs that are
  // stored in LayoutState. An item is inserted in this map if its corresponding component is
  // visible. When the component exits the viewport, the item associated with it is removed from the
  // map.
  private final LongSparseArray<VisibilityItem> mVisibilityIdToItemMap;

  // Holds a list of MountItems that are currently mounted which can mount incrementally.
  private final LongSparseArray<MountItem> mCanMountIncrementallyMountItems;

  // A map from test key to a list of one or more `TestItem`s which is only allocated
  // and populated during test runs.
  private final Map<String, Deque<TestItem>> mTestItemMap;

  private @Nullable long[] mLayoutOutputsIds;

  // True if we are receiving a new LayoutState and we need to completely
  // refresh the content of the HostComponent. Always set from the main thread.
  private boolean mIsDirty;

  // Holds the list of known component hosts during a mount pass.
  private final LongSparseArray<ComponentHost> mHostsByMarker = new LongSparseArray<>();

  private static final Rect sTempRect = new Rect();

  private final ComponentContext mContext;
  private final LithoView mLithoView;
  private final Rect mPreviousLocalVisibleRect = new Rect();
  private final PrepareMountStats mPrepareMountStats = new PrepareMountStats();
  private final MountStats mMountStats = new MountStats();
  private TransitionManager mTransitionManager;
  private int mPreviousTopsIndex;
  private int mPreviousBottomsIndex;
  private int mLastMountedComponentTreeId = ComponentTree.INVALID_ID;
  private final HashMap<String, MountItem> mDisappearingMountItems = new HashMap<>();
  private final HashSet<String> mAnimatingTransitionKeys = new HashSet<>();
  private LayoutState mLastMountedLayoutState;
  private int[] mAnimationLockedIndices;
  private boolean mIsFirstMountOfComponentTree = false;
  private @Nullable ArrayList<Transition> mMountTimeTransitions;

  private final MountItem mRootHostMountItem;

  public MountState(LithoView view) {
    mIndexToItemMap = new LongSparseArray<>();
    mVisibilityIdToItemMap = new LongSparseArray<>();
    mCanMountIncrementallyMountItems = new LongSparseArray<>();
    mContext = (ComponentContext) view.getContext();
    mLithoView = view;
    mIsDirty = true;

    mTestItemMap = ComponentsConfiguration.isEndToEndTestRun
        ? new HashMap<String, Deque<TestItem>>()
        : null;

    // The mount item representing the top-level LithoView which
    // is always automatically mounted.
    mRootHostMountItem = ComponentsPools.acquireRootHostMountItem(
        HostComponent.create(),
        mLithoView,
        mLithoView);
  }

  /**
   * To be called whenever the components needs to start the mount process from scratch
   * e.g. when the component's props or layout change or when the components
   * gets attached to a host.
   */
  void setDirty() {
    assertMainThread();

    mIsDirty = true;
    mPreviousLocalVisibleRect.setEmpty();
  }

  boolean isDirty() {
    assertMainThread();

    return mIsDirty;
  }

  /**
   * Sets whether the next mount will be the first mount of this ComponentTree. This is used to
   * determine whether to run animations or not (we want animations to run on the first mount of
   * this ComponentTree, but not other times the mounted ComponentTree id changes). Ideally, we want
   * animations to only occur when the ComponentTree is updated on screen or is first inserted into
   * a list onscreen, but that requires more integration with the list controller, e.g. sections,
   * than we currently have.
   */
  void setIsFirstMountOfComponentTree() {
    assertMainThread();

    mIsFirstMountOfComponentTree = true;
  }

  /**
   * Mount the layoutState on the pre-set HostView.
   *
   * @param layoutState
   * @param localVisibleRect If this variable is null, then mount everything, since incremental
   *     mount is not enabled. Otherwise mount only what the rect (in local coordinates) contains
   * @param processVisibilityOutputs whether to process visibility outputs as part of the mount
   */
  void mount(LayoutState layoutState, Rect localVisibleRect, boolean processVisibilityOutputs) {
    assertMainThread();

    if (layoutState == null) {
      throw new IllegalStateException("Trying to mount a null layoutState");
    }

    ComponentsSystrace.beginSection("mount");

    final ComponentTree componentTree = mLithoView.getComponentTree();
    final ComponentsLogger logger = componentTree.getContext().getLogger();
    final int componentTreeId = layoutState.getComponentTreeId();
    if (componentTreeId != mLastMountedComponentTreeId) {
      // If we're mounting a new ComponentTree, don't keep around and use the previous LayoutState
      // since things like transition animations aren't relevant.
      releaseLastMountedLayoutState();
    }

    LogEvent mountEvent = null;
    if (logger != null) {
      mountEvent = logger.newPerformanceEvent(EVENT_MOUNT);
    }

    if (mIsDirty) {
      updateTransitions(layoutState);

      suppressInvalidationsOnHosts(true);

      // Prepare the data structure for the new LayoutState and removes mountItems
      // that are not present anymore if isUpdateMountInPlace is enabled.
      prepareMount(layoutState);
    }

    mMountStats.reset();
    if (logger != null && mountEvent != null && logger.isTracing(mountEvent)) {
      mMountStats.enableLogging();
    }

    final boolean isIncrementalMountEnabled = localVisibleRect != null;
    final boolean isTracing = ComponentsSystrace.isTracing();

    if (!isIncrementalMountEnabled
        || !performIncrementalMount(layoutState, localVisibleRect, processVisibilityOutputs)) {
      final MountItem rootMountItem = mIndexToItemMap.get(ROOT_HOST_ID);

      for (int i = 0, size = layoutState.getMountableOutputCount(); i < size; i++) {
        final LayoutOutput layoutOutput = layoutState.getMountableOutputAt(i);
        final Component component = layoutOutput.getComponent();
        if (isTracing) {
          ComponentsSystrace.beginSection(component.getSimpleName());
        }

        final MountItem currentMountItem = getItemAt(i);
        final boolean isMounted = currentMountItem != null;
        final boolean isMountable =
            !isIncrementalMountEnabled ||
                isMountedHostWithChildContent(currentMountItem) ||
                Rect.intersects(localVisibleRect, layoutOutput.getBounds()) ||
                isAnimationLocked(i) ||
                (currentMountItem != null && currentMountItem == rootMountItem);

        if (isMountable && !isMounted) {
          mountLayoutOutput(i, layoutOutput, layoutState);

          if (isAnimationLocked(i)
              && isIncrementalMountEnabled
              && canMountIncrementally(component)) {
            // If the component is locked for animation then we need to make sure that all the
            // children are also mounted.
            final Rect rect = ComponentsPools.acquireRect();
            final View view = (View) getItemAt(i).getContent();
            rect.set(0, 0, view.getWidth(), view.getHeight());
            mountViewIncrementally(view, rect, processVisibilityOutputs);
            ComponentsPools.release(rect);
          }
        } else if (!isMountable && isMounted) {
          unmountItem(mContext, i, mHostsByMarker);
        } else if (isMounted) {
          if (mIsDirty) {
            final boolean useUpdateValueFromLayoutOutput =
                (componentTreeId >= 0) && (componentTreeId == mLastMountedComponentTreeId);

            final long startTime = System.nanoTime();
            final boolean itemUpdated = updateMountItemIfNeeded(
                layoutOutput,
                currentMountItem,
                useUpdateValueFromLayoutOutput,
                logger,
                componentTreeId,
                i);

            if (mMountStats.isLoggingEnabled) {
              if (itemUpdated) {
                mMountStats.updatedNames.add(component.getSimpleName());
                mMountStats.updatedTimes.add((System.nanoTime() - startTime) / NS_IN_MS);
                mMountStats.updatedCount++;
              } else {
                mMountStats.noOpCount++;
              }
            }
          }

          if (isIncrementalMountEnabled && canMountIncrementally(component)) {
            mountItemIncrementally(
                currentMountItem,
                layoutOutput.getBounds(),
                localVisibleRect,
                processVisibilityOutputs);
          }
        }

        if (isTracing) {
          ComponentsSystrace.endSection();
        }
      }

      if (isIncrementalMountEnabled) {
        setupPreviousMountableOutputData(layoutState, localVisibleRect);
      }
    }

    if (shouldAnimateTransitions(layoutState) && hasTransitionsToAnimate(layoutState)) {
      mTransitionManager.runTransitions();
    }

    mMountTimeTransitions = null;
    mIsDirty = false;
    mIsFirstMountOfComponentTree = false;
    if (localVisibleRect != null) {
      mPreviousLocalVisibleRect.set(localVisibleRect);
    }

    releaseLastMountedLayoutState();
    mLastMountedComponentTreeId = componentTreeId;
    mLastMountedLayoutState = layoutState.acquireRef();

    if (processVisibilityOutputs) {
      ComponentsSystrace.beginSection("processVisibilityOutputs");
      processVisibilityOutputs(layoutState, localVisibleRect);
      ComponentsSystrace.endSection();
    }

    processTestOutputs(layoutState);

    suppressInvalidationsOnHosts(false);

    if (mMountStats.isLoggingEnabled) {
      mountEvent.addParam(PARAM_LOG_TAG, componentTree.getContext().getLogTag());
      mountEvent.addParam(PARAM_MOUNTED_COUNT, String.valueOf(mMountStats.mountedCount));
      mountEvent.addJsonParam(PARAM_MOUNTED_CONTENT, mMountStats.mountedNames);
      mountEvent.addJsonParam(PARAM_MOUNTED_TIME, mMountStats.mountTimes);

      mountEvent.addParam(PARAM_UNMOUNTED_COUNT, String.valueOf(mMountStats.unmountedCount));
      mountEvent.addJsonParam(PARAM_UNMOUNTED_CONTENT, mMountStats.unmountedNames);
      mountEvent.addJsonParam(PARAM_UNMOUNTED_TIME, mMountStats.unmountedTimes);

      mountEvent.addParam(PARAM_UPDATED_COUNT, String.valueOf(mMountStats.updatedCount));
      mountEvent.addJsonParam(PARAM_UPDATED_CONTENT, mMountStats.updatedNames);
      mountEvent.addJsonParam(PARAM_UPDATED_TIME, mMountStats.updatedTimes);

      mountEvent.addParam(
          PARAM_VISIBILITY_HANDLERS_TOTAL_TIME, mMountStats.visibilityHandlersTotalTime);
      mountEvent.addJsonParam(PARAM_VISIBILITY_HANDLER, mMountStats.visibilityHandlerNames);
      mountEvent.addJsonParam(PARAM_VISIBILITY_HANDLER_TIME, mMountStats.visibilityHandlerTimes);

      mountEvent.addParam(PARAM_NO_OP_COUNT, String.valueOf(mMountStats.noOpCount));
      mountEvent.addParam(PARAM_IS_DIRTY, String.valueOf(mIsDirty));

      logger.log(mountEvent);
    }

    ComponentsSystrace.endSection();
  }

  private void processVisibilityOutputs(LayoutState layoutState, Rect localVisibleRect) {
    if (localVisibleRect == null) {
      return;
    }

    final boolean isDoingPerfLog = mMountStats.isLoggingEnabled;
    final boolean isTracing = ComponentsSystrace.isTracing();
    final long totalStartTime = isDoingPerfLog ? System.nanoTime() : 0;
    for (int j = 0, size = layoutState.getVisibilityOutputCount(); j < size; j++) {
      final VisibilityOutput visibilityOutput = layoutState.getVisibilityOutputAt(j);
      if (isTracing) {
        final String componentName =
            visibilityOutput.getComponent() != null
                ? visibilityOutput.getComponent().getSimpleName()
                : "Unknown";
        ComponentsSystrace.beginSection("visibilityHandlers:" + componentName);
      }
      final long handlerStartTime = isDoingPerfLog ? System.nanoTime() : 0;
      final EventHandler<VisibleEvent> visibleHandler = visibilityOutput.getVisibleEventHandler();
      final EventHandler<FocusedVisibleEvent> focusedHandler =
          visibilityOutput.getFocusedEventHandler();
      final EventHandler<UnfocusedVisibleEvent> unfocusedHandler =
          visibilityOutput.getUnfocusedEventHandler();
      final EventHandler<FullImpressionVisibleEvent> fullImpressionHandler =
          visibilityOutput.getFullImpressionEventHandler();
      final EventHandler<InvisibleEvent> invisibleHandler =
          visibilityOutput.getInvisibleEventHandler();
      final long visibilityOutputId = visibilityOutput.getId();
      final Rect visibilityOutputBounds = visibilityOutput.getBounds();

      sTempRect.set(visibilityOutputBounds);
      final boolean isCurrentlyVisible = sTempRect.intersect(localVisibleRect)
          && isInVisibleRange(visibilityOutput, visibilityOutputBounds, localVisibleRect);

      VisibilityItem visibilityItem = mVisibilityIdToItemMap.get(visibilityOutputId);
      if (visibilityItem != null) {
        final String previousGlobalKey = visibilityItem.getGlobalKey();
        final String currentGlobalKey =
            visibilityOutput.getComponent() != null
                ? visibilityOutput.getComponent().getGlobalKey()
                : null;
        final boolean hasGlobalKeyChanged =
            previousGlobalKey != null && !previousGlobalKey.equals(currentGlobalKey);

        if (!hasGlobalKeyChanged) {
          // If we did a relayout due to e.g. a state update then the handlers will have changed,
          // so we should keep them up to date.
          visibilityItem.setUnfocusedHandler(unfocusedHandler);
          visibilityItem.setInvisibleHandler(invisibleHandler);
        }

        if (!isCurrentlyVisible || hasGlobalKeyChanged) {
          // Either the component is invisible now, but used to be visible, or the key on the
          // component has changed so we should generate new visibility events for the new
          // component.
          if (visibilityItem.getInvisibleHandler() != null) {
            EventDispatcherUtils.dispatchOnInvisible(visibilityItem.getInvisibleHandler());
          }

          if (visibilityItem.isInFocusedRange()) {
            visibilityItem.setFocusedRange(false);
            if (visibilityItem.getUnfocusedHandler() != null) {
              EventDispatcherUtils.dispatchOnUnfocused(visibilityItem.getUnfocusedHandler());
            }
          }

          mVisibilityIdToItemMap.remove(visibilityOutputId);
          ComponentsPools.release(visibilityItem);
          visibilityItem = null;
        }
      }

      if (isCurrentlyVisible) {
        // The component is visible now, but used to be outside the viewport.
        if (visibilityItem == null) {
          final String globalKey =
              visibilityOutput.getComponent() != null
                  ? visibilityOutput.getComponent().getGlobalKey()
                  : null;
          visibilityItem =
              ComponentsPools.acquireVisibilityItem(globalKey, invisibleHandler, unfocusedHandler);
          mVisibilityIdToItemMap.put(visibilityOutputId, visibilityItem);

          if (visibleHandler != null) {
            EventDispatcherUtils.dispatchOnVisible(visibleHandler);
          }
        }

        // Check if the component has entered or exited the focused range.
        if (focusedHandler != null || unfocusedHandler != null) {
          if (isInFocusedRange(visibilityOutputBounds, sTempRect)) {
            if (!visibilityItem.isInFocusedRange()) {
              visibilityItem.setFocusedRange(true);
              if (focusedHandler != null) {
                EventDispatcherUtils.dispatchOnFocused(focusedHandler);
              }
            }
          } else {
            if (visibilityItem.isInFocusedRange()) {
              visibilityItem.setFocusedRange(false);
              if (unfocusedHandler != null) {
                EventDispatcherUtils.dispatchOnUnfocused(unfocusedHandler);
              }
            }
          }
        }
        // If the component has not entered the full impression range yet, make sure to update the
        // information about the visible edges.
        if (fullImpressionHandler != null && !visibilityItem.isInFullImpressionRange()) {
          visibilityItem.setVisibleEdges(visibilityOutputBounds, sTempRect);

          if (visibilityItem.isInFullImpressionRange()) {
            EventDispatcherUtils.dispatchOnFullImpression(fullImpressionHandler);
          }
        }
      }
      if (isDoingPerfLog) {
        final String componentName =
            visibilityOutput.getComponent() != null
                ? visibilityOutput.getComponent().getSimpleName()
                : "Unknown";
        mMountStats.visibilityHandlerTimes.add((System.nanoTime() - handlerStartTime) / NS_IN_MS);
        mMountStats.visibilityHandlerNames.add(componentName);
      }
      if (isTracing) {
        ComponentsSystrace.endSection();
      }
    }

    if (isDoingPerfLog) {
      mMountStats.visibilityHandlersTotalTime = (System.nanoTime() - totalStartTime) / NS_IN_MS;
    }
  }

  /**
   * Clears and re-populates the test item map if we are in e2e test mode.
   */
  private void processTestOutputs(LayoutState layoutState) {
    if (mTestItemMap == null) {
      return;
    }

    for (Collection<TestItem> items : mTestItemMap.values()) {
      for (TestItem item : items) {
        ComponentsPools.release(item);
      }
    }
    mTestItemMap.clear();

    for (int i = 0, size = layoutState.getTestOutputCount(); i < size; i++) {
      final TestOutput testOutput = layoutState.getTestOutputAt(i);
      final long hostMarker = testOutput.getHostMarker();
      final long layoutOutputId = testOutput.getLayoutOutputId();
      final MountItem mountItem =
          layoutOutputId == -1 ? null : mIndexToItemMap.get(layoutOutputId);
      final TestItem testItem = ComponentsPools.acquireTestItem();
      testItem.setHost(hostMarker == -1 ? null : mHostsByMarker.get(hostMarker));
      testItem.setBounds(testOutput.getBounds());
      testItem.setTestKey(testOutput.getTestKey());
      testItem.setContent(mountItem == null ? null : mountItem.getContent());

      final Deque<TestItem> items = mTestItemMap.get(testOutput.getTestKey());
      final Deque<TestItem> updatedItems =
          items == null ? new LinkedList<TestItem>() : items;
      updatedItems.add(testItem);
      mTestItemMap.put(testOutput.getTestKey(), updatedItems);
    }
  }

  private boolean isMountedHostWithChildContent(MountItem mountItem) {
    if (mountItem == null) {
      return false;
    }

    final Object content = mountItem.getContent();
    if (!(content instanceof ComponentHost)) {
      return false;
    }

    final ComponentHost host = (ComponentHost) content;
    return host.getMountItemCount() > 0;
  }

  private void setupPreviousMountableOutputData(LayoutState layoutState, Rect localVisibleRect) {
    if (localVisibleRect.isEmpty()) {
      return;
    }

    final ArrayList<LayoutOutput> layoutOutputTops = layoutState.getMountableOutputTops();
    final ArrayList<LayoutOutput> layoutOutputBottoms = layoutState.getMountableOutputBottoms();
    final int mountableOutputCount = layoutState.getMountableOutputCount();

    mPreviousTopsIndex = layoutState.getMountableOutputCount();
    for (int i = 0; i < mountableOutputCount; i++) {
      if (localVisibleRect.bottom <= layoutOutputTops.get(i).getBounds().top) {
        mPreviousTopsIndex = i;
        break;
      }
    }

    mPreviousBottomsIndex = layoutState.getMountableOutputCount();
    for (int i = 0; i < mountableOutputCount; i++) {
      if (localVisibleRect.top < layoutOutputBottoms.get(i).getBounds().bottom) {
        mPreviousBottomsIndex = i;
        break;
      }
    }
  }

  private void clearVisibilityItems() {
    for (int i = mVisibilityIdToItemMap.size() - 1; i >= 0; i--) {
      final VisibilityItem visibilityItem = mVisibilityIdToItemMap.valueAt(i);
      final EventHandler<InvisibleEvent> invisibleHandler = visibilityItem.getInvisibleHandler();
      final EventHandler<UnfocusedVisibleEvent> unfocusedHandler =
          visibilityItem.getUnfocusedHandler();

      if (invisibleHandler != null) {
        EventDispatcherUtils.dispatchOnInvisible(invisibleHandler);
      }

      if (visibilityItem.isInFocusedRange()) {
        visibilityItem.setFocusedRange(false);
        if (unfocusedHandler != null) {
          EventDispatcherUtils.dispatchOnUnfocused(unfocusedHandler);
        }
      }

      mVisibilityIdToItemMap.removeAt(i);
      ComponentsPools.release(visibilityItem);
    }
  }

  private void registerHost(long id, ComponentHost host) {
    host.suppressInvalidations(true);
    mHostsByMarker.put(id, host);
  }

  private boolean isInVisibleRange(
      VisibilityOutput visibilityOutput,
      Rect bounds,
      Rect visibleBounds) {
    float heightRatio = visibilityOutput.getVisibleHeightRatio();
    float widthRatio = visibilityOutput.getVisibleWidthRatio();

    if (heightRatio == 0 && widthRatio == 0) {
      return true;
    }

    return isInRatioRange(heightRatio, bounds.height(), visibleBounds.height())
        && isInRatioRange(widthRatio, bounds.width(), visibleBounds.width());
  }

  private static boolean isInRatioRange(float ratio, int length, int visiblelength) {
    return visiblelength >= ratio * length;
  }

  /**
   * Returns true if the component is in the focused visible range.
   */
  private boolean isInFocusedRange(
      Rect componentBounds,
      Rect componentVisibleBounds) {
    final View parent = (View) mLithoView.getParent();
    if (parent == null) {
      return false;
    }

    final int halfViewportArea = parent.getWidth() * parent.getHeight() / 2;
    final int totalComponentArea = computeRectArea(componentBounds);
    final int visibleComponentArea = computeRectArea(componentVisibleBounds);

    // The component has entered the focused range either if it is larger than half of the viewport
    // and it occupies at least half of the viewport or if it is smaller than half of the viewport
    // and it is fully visible.
    return (totalComponentArea >= halfViewportArea)
        ? (visibleComponentArea >= halfViewportArea)
        : componentBounds.equals(componentVisibleBounds);
  }

  private static int computeRectArea(Rect rect) {
    return rect.isEmpty() ? 0 : (rect.width() * rect.height());
  }

  private void suppressInvalidationsOnHosts(boolean suppressInvalidations) {
    for (int i = mHostsByMarker.size() - 1; i >= 0; i--) {
      mHostsByMarker.valueAt(i).suppressInvalidations(suppressInvalidations);
    }
  }

  private boolean updateMountItemIfNeeded(
      LayoutOutput layoutOutput,
      MountItem currentMountItem,
      boolean useUpdateValueFromLayoutOutput,
      ComponentsLogger logger,
      int componentTreeId,
      int index) {
    final Component layoutOutputComponent = layoutOutput.getComponent();
    final Component itemComponent = currentMountItem.getComponent();

    // 1. Check if the mount item generated from the old component should be updated.
    final boolean shouldUpdate = shouldUpdateMountItem(
        layoutOutput,
        currentMountItem,
        useUpdateValueFromLayoutOutput,
        mIndexToItemMap,
        mLayoutOutputsIds,
        logger);

    // 2. Reset all the properties like click handler, content description and tags related to
    // this item if it needs to be updated. the update mount item will re-set the new ones.
    if (shouldUpdate) {
      // This mount content might be animating and we may be remounting it as a different component
      // in the same tree, or as a component in a totally different tree so we need to notify the
      // transition manager.
      maybeUpdateAnimatingMountContent(currentMountItem, null);

      // If we're remounting this ComponentHost for a new ComponentTree, remove all disappearing
      // mount content that was animating since those disappearing animations belong to the old
      // ComponentTree
      if (mLastMountedComponentTreeId != componentTreeId) {
        final Component component = currentMountItem.getComponent();

        if (isHostSpec(component)) {
          final ComponentHost componentHost = (ComponentHost) currentMountItem.getContent();
          removeDisappearingMountContentFromComponentHost(componentHost);
        }
      }

      unsetViewAttributes(currentMountItem);

      final ComponentHost host = currentMountItem.getHost();
      host.maybeUnregisterTouchExpansion(index, currentMountItem);
    }

    // 3. We will re-bind this later in 7 regardless so let's make sure it's currently unbound.
    if (currentMountItem.isBound()) {
      itemComponent.onUnbind(getContextForComponent(itemComponent), currentMountItem.getContent());
      currentMountItem.setIsBound(false);
    }

    // 4. Re initialize the MountItem internal state with the new attributes from LayoutOutput
    currentMountItem.init(layoutOutput, currentMountItem);

    // 5. If the mount item is not valid for this component update its content and view attributes.
    if (shouldUpdate) {
      final ComponentHost host = currentMountItem.getHost();
      host.maybeRegisterTouchExpansion(index, currentMountItem);

      updateMountedContent(currentMountItem, layoutOutput, itemComponent);
      setViewAttributes(currentMountItem);
    }

    final Object currentContent = currentMountItem.getContent();

    // 6. Set the mounted content on the Component and call the bind callback.
    layoutOutputComponent.bind(getContextForComponent(layoutOutputComponent), currentContent);
    currentMountItem.setIsBound(true);

    // 7. Update the bounds of the mounted content. This needs to be done regardless of whether
    // the component has been updated or not since the mounted item might might have the same
    // size and content but a different position.
    updateBoundsForMountedLayoutOutput(layoutOutput, currentMountItem);

    // This needs to happen after updating the bounds so that the right translateX/Y are applied to
    // the mount content
    maybeUpdateAnimatingMountContent(currentMountItem, currentMountItem.getContent());

    maybeInvalidateAccessibilityState(currentMountItem);
    if (currentMountItem.getContent() instanceof Drawable) {
      maybeSetDrawableState(
          currentMountItem.getHost(),
          (Drawable) currentMountItem.getContent(),
          currentMountItem.getFlags(),
          currentMountItem.getNodeInfo());
    }

    if (currentMountItem.getDisplayListDrawable() != null) {
      currentMountItem.getDisplayListDrawable().suppressInvalidations(false);
    }

    return shouldUpdate;
  }

  private static boolean shouldUpdateMountItem(
      LayoutOutput layoutOutput,
      MountItem currentMountItem,
      boolean useUpdateValueFromLayoutOutput,
      LongSparseArray<MountItem> indexToItemMap,
      long[] layoutOutputsIds,
      ComponentsLogger logger) {
    @LayoutOutput.UpdateState final int updateState = layoutOutput.getUpdateState();
    final Component currentComponent = currentMountItem.getComponent();
    final ComponentLifecycle currentLifecycle = currentComponent;
    final Component nextComponent = layoutOutput.getComponent();
    final ComponentLifecycle nextLifecycle = nextComponent;

    // If the two components have different sizes and the mounted content depends on the size we
    // just return true immediately.
    if (!sameSize(layoutOutput, currentMountItem) && nextLifecycle.isMountSizeDependent()) {
      return true;
    }

    if (useUpdateValueFromLayoutOutput) {
      if (updateState == LayoutOutput.STATE_UPDATED) {

        // Check for incompatible ReferenceLifecycle.
        if (currentLifecycle instanceof DrawableComponent
            && nextLifecycle instanceof DrawableComponent
            && currentLifecycle.shouldComponentUpdate(currentComponent, nextComponent)) {

          if (logger != null) {
            LayoutOutputLog logObj = new LayoutOutputLog();

            logObj.currentId = indexToItemMap.keyAt(
                indexToItemMap.indexOfValue(currentMountItem));
            logObj.currentLifecycle = currentLifecycle.toString();

            logObj.nextId = layoutOutput.getId();
            logObj.nextLifecycle = nextLifecycle.toString();

            for (int i = 0; i < layoutOutputsIds.length; i++) {
              if (layoutOutputsIds[i] == logObj.currentId) {
                if (logObj.currentIndex == -1) {
                  logObj.currentIndex = i;
                }

                logObj.currentLastDuplicatedIdIndex = i;
              }
            }

            if (logObj.nextId == logObj.currentId) {
              logObj.nextIndex = logObj.currentIndex;
              logObj.nextLastDuplicatedIdIndex = logObj.currentLastDuplicatedIdIndex;
            } else {
              for (int i = 0; i < layoutOutputsIds.length; i++) {
                if (layoutOutputsIds[i] == logObj.nextId) {
                  if (logObj.nextIndex == -1) {
                    logObj.nextIndex = i;
                  }

                  logObj.nextLastDuplicatedIdIndex = i;
                }
              }
            }

            final LogEvent mismatchEvent = logger.newEvent(EVENT_SHOULD_UPDATE_REFERENCE_LAYOUT_MISMATCH);
            mismatchEvent.addParam(PARAM_MESSAGE, logObj.toString());
            logger.log(mismatchEvent);
          }

          return true;
        }

        return false;
      } else if (updateState == LayoutOutput.STATE_DIRTY) {
        return true;
      }
    }

    if (!currentLifecycle.callsShouldUpdateOnMount()) {
      return true;
    }

    return currentLifecycle.shouldComponentUpdate(
        currentComponent,
        nextComponent);
  }

  private static boolean sameSize(LayoutOutput layoutOutput, MountItem item) {
    final Rect layoutOutputBounds = layoutOutput.getBounds();
    final Object mountedContent = item.getContent();

    return layoutOutputBounds.width() == getWidthForMountedContent(mountedContent) &&
        layoutOutputBounds.height() == getHeightForMountedContent(mountedContent);
  }

  private static int getWidthForMountedContent(Object content) {
    return content instanceof Drawable ?
        ((Drawable) content).getBounds().width() :
        ((View) content).getWidth();
  }

  private static int getHeightForMountedContent(Object content) {
    return content instanceof Drawable ?
        ((Drawable) content).getBounds().height() :
        ((View) content).getHeight();
  }

  private void updateBoundsForMountedLayoutOutput(LayoutOutput layoutOutput, MountItem item) {
    // MountState should never update the bounds of the top-level host as this
    // should be done by the ViewGroup containing the LithoView.
    if (layoutOutput.getId() == ROOT_HOST_ID) {
      return;
    }

    layoutOutput.getMountBounds(sTempRect);

    final boolean forceTraversal = Component.isMountViewSpec(layoutOutput.getComponent())
        && ((View) item.getContent()).isLayoutRequested();

    applyBoundsToMountContent(
        item.getContent(),
        sTempRect.left,
        sTempRect.top,
        sTempRect.right,
        sTempRect.bottom,
        forceTraversal /* force */);
  }

  /**
   * Prepare the {@link MountState} to mount a new {@link LayoutState}.
   */
  @SuppressWarnings("unchecked")
  private void prepareMount(LayoutState layoutState) {
    final ComponentTree component = mLithoView.getComponentTree();
    final ComponentsLogger logger = component.getContext().getLogger();
    final String logTag = component.getContext().getLogTag();

    LogEvent prepareEvent = null;
    if (logger != null) {
      prepareEvent = logger.newPerformanceEvent(EVENT_PREPARE_MOUNT);
    }

    PrepareMountStats stats = unmountOrMoveOldItems(layoutState);

    if (logger != null) {
      prepareEvent.addParam(PARAM_LOG_TAG, logTag);
      prepareEvent.addParam(PARAM_UNMOUNTED_COUNT, String.valueOf(stats.unmountedCount));
      prepareEvent.addParam(PARAM_MOVED_COUNT, String.valueOf(stats.movedCount));
      prepareEvent.addParam(PARAM_UNCHANGED_COUNT, String.valueOf(stats.unchangedCount));
    }

    if (mHostsByMarker.get(ROOT_HOST_ID) == null) {
      // Mounting always starts with the root host.
      registerHost(ROOT_HOST_ID, mLithoView);

      // Root host is implicitly marked as mounted.
      mIndexToItemMap.put(ROOT_HOST_ID, mRootHostMountItem);
    }

    int outputCount = layoutState.getMountableOutputCount();
    if (mLayoutOutputsIds == null || outputCount != mLayoutOutputsIds.length) {
      mLayoutOutputsIds = new long[layoutState.getMountableOutputCount()];
    }

    for (int i = 0; i < outputCount; i++) {
      mLayoutOutputsIds[i] = layoutState.getMountableOutputAt(i).getId();
    }

    if (logger != null) {
      logger.log(prepareEvent);
    }
  }

  /**
   * Determine whether to apply disappear animation to the given {@link MountItem}
   */
  private boolean isItemDisappearing(LayoutState layoutState, int index) {
    if (!shouldAnimateTransitions(layoutState) || !hasTransitionsToAnimate(layoutState)) {
      return false;
    }

    if (mTransitionManager == null || mLastMountedLayoutState == null) {
      return false;
    }

    final LayoutOutput layoutOutput = mLastMountedLayoutState.getMountableOutputAt(index);
    final String key = layoutOutput.getTransitionKey();
    if (key == null) {
      return false;
    }

    return mTransitionManager.isKeyDisappearing(key);
  }

  /**
   * Go over all the mounted items from the leaves to the root and unmount only the items that are
   * not present in the new LayoutOutputs.
   * If an item is still present but in a new position move the item inside its host.
   * The condition where an item changed host doesn't need any special treatment here since we
   * mark them as removed and re-added when calculating the new LayoutOutputs
   */
  private PrepareMountStats unmountOrMoveOldItems(LayoutState newLayoutState) {
    mPrepareMountStats.reset();

    if (mLayoutOutputsIds == null) {
      return mPrepareMountStats;
    }

    // Traversing from the beginning since mLayoutOutputsIds unmounting won't remove entries there
    // but only from mIndexToItemMap. If an host changes we're going to unmount it and recursively
    // all its mounted children.
    for (int i = 0; i < mLayoutOutputsIds.length; i++) {
      final int newPosition = newLayoutState.getLayoutOutputPositionForId(mLayoutOutputsIds[i]);
      final MountItem oldItem = getItemAt(i);

      // If an item is being unmounted and is doing a disappearing animation, don't actually unmount
      // so that we can perform the disappear animation.
      if (isItemDisappearing(newLayoutState, i)) {

        final int lastDescendantOfItem = findLastDescendantIndex(mLastMountedLayoutState, i);

        for (int j = i; j <= lastDescendantOfItem; j++) {
          MountItem item = getItemAt(j);
          if (item == null) {
            final LayoutOutput layoutOutput = mLastMountedLayoutState.getMountableOutputAt(j);
            mountLayoutOutput(j, layoutOutput, mLastMountedLayoutState);
            item = getItemAt(j);
          }

          final String key = item.getTransitionKey();
          if (key != null && mTransitionManager.isKeyDisappearing(key)) {
            startUnmountDisappearingItem(j, key);
          }
        }

        // Disassociate disappearing items from current mounted items. The layout tree will not
        // contain disappearing items anymore, however they are kept separately in their hosts.
        removeDisappearingItemMappings(i, lastDescendantOfItem);

        // Skip this disappearing item and all its descendants. Do not unmount or move them yet.
        // We will unmount them after animation is completed.
        i = lastDescendantOfItem;
        continue;
      }

      if (newPosition == -1) {
        unmountItem(mContext, i, mHostsByMarker);
        mPrepareMountStats.unmountedCount++;
      } else {
        final long newHostMarker = newLayoutState.getMountableOutputAt(newPosition).getHostMarker();

        if (oldItem == null) {
          // This was previously unmounted.
          mPrepareMountStats.unmountedCount++;
        } else if (oldItem.getHost() != mHostsByMarker.get(newHostMarker)) {
          // If the id is the same but the parent host is different we simply unmount the item and
          // re-mount it later. If the item to unmount is a ComponentHost, all the children will be
          // recursively unmounted.
          unmountItem(mContext, i, mHostsByMarker);
          mPrepareMountStats.unmountedCount++;
        } else if (newPosition != i) {
          // If a MountItem for this id exists and the hostMarker has not changed but its position
          // in the outputs array has changed we need to update the position in the Host to ensure
          // the z-ordering.
          oldItem.getHost().moveItem(oldItem, i, newPosition);
          mPrepareMountStats.movedCount++;
        } else {
          mPrepareMountStats.unchangedCount++;
        }
      }
    }

    return mPrepareMountStats;
  }

  private void removeDisappearingItemMappings(int fromIndex, int toIndex) {
    for (int i = fromIndex; i <= toIndex; i++) {
      final MountItem item = getItemAt(i);

      // We do not need this mapping for disappearing items.
      mIndexToItemMap.remove(mLayoutOutputsIds[i]);

      // Likewise we no longer need host mapping for disappearing items.
      if (isHostSpec(item.getComponent())) {
        mHostsByMarker
            .removeAt(mHostsByMarker.indexOfValue((ComponentHost) item.getContent()));
      }
    }
  }

  private void updateMountedContent(
      MountItem item,
      LayoutOutput layoutOutput,
      Component previousComponent) {
    final Component newComponent = layoutOutput.getComponent();
    if (isHostSpec(newComponent)) {
      return;
    }

    final Object previousContent = item.getContent();

    // Call unmount and mount in sequence to make sure all the the resources are correctly
    // de-allocated. It's possible for previousContent to equal null - when the root is
    // interactive we create a LayoutOutput without content in order to set up click handling.
    previousComponent.unmount(
        getContextForComponent(previousComponent), previousContent);
    newComponent.mount(getContextForComponent(newComponent), previousContent);
  }

  private void mountLayoutOutput(int index, LayoutOutput layoutOutput, LayoutState layoutState) {
    // 1. Resolve the correct host to mount our content to.
    final long startTime = System.nanoTime();
    ComponentHost host = resolveComponentHost(layoutOutput, mHostsByMarker);

    if (host == null) {
      // Host has not yet been mounted - mount it now.
      for (int hostMountIndex = 0, size = mLayoutOutputsIds.length;
           hostMountIndex < size;
           hostMountIndex++) {
        if (mLayoutOutputsIds[hostMountIndex] == layoutOutput.getHostMarker()) {
          final LayoutOutput hostLayoutOutput = layoutState.getMountableOutputAt(hostMountIndex);
          mountLayoutOutput(hostMountIndex, hostLayoutOutput, layoutState);
          break;
        }
      }

      host = resolveComponentHost(layoutOutput, mHostsByMarker);
    }

    // 2. Generate the component's mount state (this might also be a ComponentHost View).
    final Component component = layoutOutput.getComponent();
    final Object content =
        (ComponentsConfiguration.scrapHostRecyclingForComponentHosts && isHostSpec(component))
            ? acquireComponentHost(mContext, component, host)
            : ComponentsPools.acquireMountContent(mContext, component);

    final ComponentContext context = getContextForComponent(component);
    component.mount(
        context,
        content);

    // 3. If it's a ComponentHost, add the mounted View to the list of Hosts.
    if (isHostSpec(component)) {
      ComponentHost componentHost = (ComponentHost) content;
      componentHost.setParentHostMarker(layoutOutput.getHostMarker());
      registerHost(layoutOutput.getId(), componentHost);
    }

    // 4. Mount the content into the selected host.
    final MountItem item = mountContent(index, component, content, host, layoutOutput);

    // 5. Notify the component that mounting has completed
    component.bind(context, content);
    item.setIsBound(true);

    // 6. Apply the bounds to the Mount content now. It's important to do so after bind as calling
    // bind might have triggered a layout request within a View.
    layoutOutput.getMountBounds(sTempRect);
    applyBoundsToMountContent(
        content,
        sTempRect.left,
        sTempRect.top,
        sTempRect.right,
        sTempRect.bottom,
        true /* force */);

    if (item.getDisplayListDrawable() != null) {
      item.getDisplayListDrawable().suppressInvalidations(false);
    }

    // 6. Update the mount stats
    if (mMountStats.isLoggingEnabled) {
      mMountStats.mountTimes.add((System.nanoTime() - startTime) / NS_IN_MS);
      mMountStats.mountedNames.add(component.getSimpleName());
      mMountStats.mountedCount++;
    }
  }

  // The content might be null because it's the LayoutSpec for the root host
  // (the very first LayoutOutput).
  private MountItem mountContent(
      int index,
      Component component,
      Object content,
      ComponentHost host,
      LayoutOutput layoutOutput) {

    final MountItem item = ComponentsPools.acquireMountItem(
        component,
        host,
        content,
        layoutOutput);

    // Create and keep a MountItem even for the layoutSpec with null content
    // that sets the root host interactions.
    mIndexToItemMap.put(mLayoutOutputsIds[index], item);

    if (component.canMountIncrementally()) {
      mCanMountIncrementallyMountItems.put(mLayoutOutputsIds[index], item);
    }

    layoutOutput.getMountBounds(sTempRect);

    host.mount(index, item, sTempRect);

    setViewAttributes(item);

    maybeUpdateAnimatingMountContent(item, content);

    return item;
  }

  private static Object acquireComponentHost(
      ComponentContext context, ComponentLifecycle hostLifecycle, ComponentHost host) {
    final ComponentHost recycled = host.recycleHost();
    return recycled != null ? recycled : hostLifecycle.createMountContent(context);
  }

  private static void applyBoundsToMountContent(
      Object content,
      int left,
      int top,
      int right,
      int bottom,
      boolean force) {
    assertMainThread();

    if (content instanceof View) {
      BoundsHelper.applyBoundsToView((View) content, left, top, right, bottom, force);
    } else if (content instanceof Drawable) {
      ((Drawable) content).setBounds(left, top, right, bottom);
    } else {
      throw new IllegalStateException("Unsupported mounted content " + content);
    }
  }

  private static boolean canMountIncrementally(Component component) {
    return component.canMountIncrementally();
  }

  /**
   * Resolves the component host that will be used for the given layout output
   * being mounted.
   */
  private static ComponentHost resolveComponentHost(
      LayoutOutput layoutOutput,
      LongSparseArray<ComponentHost> hostsByMarker) {
    final long hostMarker = layoutOutput.getHostMarker();

    return hostsByMarker.get(hostMarker);
  }

  private static void setViewAttributes(MountItem item) {
    final Component component = item.getComponent();
    if (!isMountViewSpec(component)) {
      return;
    }

    final View view = (View) item.getContent();
    final NodeInfo nodeInfo = item.getNodeInfo();

    if (nodeInfo != null) {
      setClickHandler(nodeInfo.getClickHandler(), view);
      setLongClickHandler(nodeInfo.getLongClickHandler(), view);
      setFocusChangeHandler(nodeInfo.getFocusChangeHandler(), view);
      setTouchHandler(nodeInfo.getTouchHandler(), view);
      setInterceptTouchHandler(nodeInfo.getInterceptTouchHandler(), view);

      setAccessibilityDelegate(view, nodeInfo);

      setViewTag(view, nodeInfo.getViewTag());
      setViewTags(view, nodeInfo.getViewTags());

      setShadowElevation(view, nodeInfo.getShadowElevation());
      setOutlineProvider(view, nodeInfo.getOutlineProvider());
      setClipToOutline(view, nodeInfo.getClipToOutline());

      setContentDescription(view, nodeInfo.getContentDescription());

      setFocusable(view, nodeInfo.getFocusState());
      setEnabled(view, nodeInfo.getEnabledState());
      setSelected(view, nodeInfo.getSelectedState());
      setScale(view, nodeInfo);
      setAlpha(view, nodeInfo);
    }

    setImportantForAccessibility(view, item.getImportantForAccessibility());

    final ViewNodeInfo viewNodeInfo = item.getViewNodeInfo();
    if (viewNodeInfo != null) {
      setViewClipChildren(view, viewNodeInfo);
      setViewStateListAnimator(view, viewNodeInfo);
      if (!isHostSpec(component)) {
        // Set view background, if applicable.  Do this before padding
        // as it otherwise overrides the padding.
        setViewBackground(view, viewNodeInfo);

        setViewPadding(view, viewNodeInfo);

        setViewForeground(view, viewNodeInfo);

        setViewLayoutDirection(view, viewNodeInfo);
      }
    }
  }

  private static void unsetViewAttributes(MountItem item) {
    final Component component = item.getComponent();
    if (!isMountViewSpec(component)) {
      return;
    }

    final View view = (View) item.getContent();
    final NodeInfo nodeInfo = item.getNodeInfo();

    if (nodeInfo != null) {
      if (nodeInfo.getClickHandler() != null) {
        unsetClickHandler(view);
      }

      if (nodeInfo.getLongClickHandler() != null) {
        unsetLongClickHandler(view);
      }

      if (nodeInfo.getFocusChangeHandler() != null) {
        unsetFocusChangeHandler(view);
      }

      if (nodeInfo.getTouchHandler() != null) {
        unsetTouchHandler(view);
      }

      if (nodeInfo.getInterceptTouchHandler() != null) {
        unsetInterceptTouchEventHandler(view);
      }

      unsetViewTag(view);
      unsetViewTags(view, nodeInfo.getViewTags());

      unsetShadowElevation(view, nodeInfo.getShadowElevation());
      unsetOutlineProvider(view, nodeInfo.getOutlineProvider());
      unsetClipToOutline(view, nodeInfo.getClipToOutline());

      if (!TextUtils.isEmpty(nodeInfo.getContentDescription())) {
        unsetContentDescription(view);
      }

      unsetScale(view, nodeInfo);
      unsetAlpha(view, nodeInfo);
    }

    view.setClickable(MountItem.isViewClickable(item.getFlags()));
    view.setLongClickable(MountItem.isViewLongClickable(item.getFlags()));

    unsetFocusable(view, item);
    unsetEnabled(view, item);
    unsetSelected(view, item);

    if (item.getImportantForAccessibility() != IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
      unsetImportantForAccessibility(view);
    }

    unsetAccessibilityDelegate(view);

    final ViewNodeInfo viewNodeInfo = item.getViewNodeInfo();
    if (viewNodeInfo != null) {
      unsetViewClipChildren(view);
      unsetViewStateListAnimator(view, viewNodeInfo);
      if (!isHostSpec(component)) {
        unsetViewPadding(view, viewNodeInfo);
        unsetViewBackground(view, viewNodeInfo);
        unsetViewForeground(view, viewNodeInfo);
        unsetViewLayoutDirection(view, viewNodeInfo);
      }
    }
  }

  /**
   * Store a {@link ComponentAccessibilityDelegate} as a tag in {@code view}. {@link LithoView}
   * contains the logic for setting/unsetting it whenever accessibility is enabled/disabled
   *
   * For non {@link ComponentHost}s
   * this is only done if any {@link EventHandler}s for accessibility events have been implemented,
   * we want to preserve the original behaviour since {@code view} might have had
   * a default delegate.
   */
  private static void setAccessibilityDelegate(View view, NodeInfo nodeInfo) {
    if (!(view instanceof ComponentHost) && !nodeInfo.needsAccessibilityDelegate()) {
      return;
    }

    view.setTag(
        R.id.component_node_info,
        nodeInfo);
  }

  private static void unsetAccessibilityDelegate(View view) {
    if (!(view instanceof ComponentHost)
        && view.getTag(R.id.component_node_info) == null) {
      return;
    }
    view.setTag(R.id.component_node_info, null);
    if (!(view instanceof ComponentHost)) {
      ViewCompat.setAccessibilityDelegate(view, null);
    }
  }

  /**
   * Installs the click listeners that will dispatch the click handler
   * defined in the component's props. Unconditionally set the clickable
   * flag on the view.
   */
  private static void setClickHandler(EventHandler<ClickEvent> clickHandler, View view) {
    if (clickHandler == null) {
      return;
    }

    ComponentClickListener listener = getComponentClickListener(view);

    if (listener == null) {
      listener = new ComponentClickListener();
      setComponentClickListener(view, listener);
    }

    listener.setEventHandler(clickHandler);
    view.setClickable(true);
  }

  private static void unsetClickHandler(View view) {
    final ComponentClickListener listener = getComponentClickListener(view);

    if (listener != null) {
      listener.setEventHandler(null);
    }
  }

  static ComponentClickListener getComponentClickListener(View v) {
    if (v instanceof ComponentHost) {
      return ((ComponentHost) v).getComponentClickListener();
    } else {
      return (ComponentClickListener) v.getTag(R.id.component_click_listener);
    }
  }

  static void setComponentClickListener(View v, ComponentClickListener listener) {
    if (v instanceof ComponentHost) {
      ((ComponentHost) v).setComponentClickListener(listener);
    } else {
      v.setOnClickListener(listener);
      v.setTag(R.id.component_click_listener, listener);
    }
  }

  /**
   * Installs the long click listeners that will dispatch the click handler
   * defined in the component's props. Unconditionally set the clickable
   * flag on the view.
   */
  private static void setLongClickHandler(
      EventHandler<LongClickEvent> longClickHandler, View view) {
    if (longClickHandler != null) {
      ComponentLongClickListener listener = getComponentLongClickListener(view);

      if (listener == null) {
        listener = new ComponentLongClickListener();
        setComponentLongClickListener(view, listener);
      }

      listener.setEventHandler(longClickHandler);

      view.setLongClickable(true);
    }
  }

  private static void unsetLongClickHandler(View view) {
    final ComponentLongClickListener listener = getComponentLongClickListener(view);

    if (listener != null) {
      listener.setEventHandler(null);
    }
  }

  static ComponentLongClickListener getComponentLongClickListener(View v) {
    if (v instanceof ComponentHost) {
      return ((ComponentHost) v).getComponentLongClickListener();
    } else {
      return (ComponentLongClickListener) v.getTag(R.id.component_long_click_listener);
    }
  }

  static void setComponentLongClickListener(View v, ComponentLongClickListener listener) {
    if (v instanceof ComponentHost) {
      ((ComponentHost) v).setComponentLongClickListener(listener);
    } else {
      v.setOnLongClickListener(listener);
      v.setTag(R.id.component_long_click_listener, listener);
    }
  }

  /**
   * Installs the on focus change listeners that will dispatch the click handler
   * defined in the component's props. Unconditionally set the clickable
   * flag on the view.
   */
  private static void setFocusChangeHandler(
      EventHandler<FocusChangedEvent> focusChangeHandler, View view) {
    if (focusChangeHandler == null) {
      return;
    }

    ComponentFocusChangeListener listener = getComponentFocusChangeListener(view);

    if (listener == null) {
      listener = new ComponentFocusChangeListener();
      setComponentFocusChangeListener(view, listener);
    }

    listener.setEventHandler(focusChangeHandler);
  }

  private static void unsetFocusChangeHandler(View view) {
    final ComponentFocusChangeListener listener = getComponentFocusChangeListener(view);

    if (listener != null) {
      listener.setEventHandler(null);
    }
  }

  static ComponentFocusChangeListener getComponentFocusChangeListener(View v) {
    if (v instanceof ComponentHost) {
      return ((ComponentHost) v).getComponentFocusChangeListener();
    } else {
      return (ComponentFocusChangeListener) v.getTag(R.id.component_focus_change_listener);
    }
  }

  static void setComponentFocusChangeListener(View v, ComponentFocusChangeListener listener) {
    if (v instanceof ComponentHost) {
      ((ComponentHost) v).setComponentFocusChangeListener(listener);
    } else {
      v.setOnFocusChangeListener(listener);
      v.setTag(R.id.component_focus_change_listener, listener);
    }
  }

  /**
   * Installs the touch listeners that will dispatch the touch handler
   * defined in the component's props.
   */
  private static void setTouchHandler(EventHandler<TouchEvent> touchHandler, View view) {
    if (touchHandler != null) {
      ComponentTouchListener listener = getComponentTouchListener(view);

      if (listener == null) {
        listener = new ComponentTouchListener();
        setComponentTouchListener(view, listener);
      }

      listener.setEventHandler(touchHandler);
    }
  }

  private static void unsetTouchHandler(View view) {
    final ComponentTouchListener listener = getComponentTouchListener(view);

    if (listener != null) {
      listener.setEventHandler(null);
    }
  }

  /**
   * Sets the intercept touch handler defined in the component's props.
   */
  private static void setInterceptTouchHandler(
      EventHandler<InterceptTouchEvent> interceptTouchHandler,
      View view) {
    if (interceptTouchHandler == null) {
      return;
    }

    if (view instanceof ComponentHost) {
      ((ComponentHost) view).setInterceptTouchEventHandler(interceptTouchHandler);
    }
  }

  private static void unsetInterceptTouchEventHandler(View view) {
    if (view instanceof ComponentHost) {
      ((ComponentHost) view).setInterceptTouchEventHandler(null);
    }
  }

  static ComponentTouchListener getComponentTouchListener(View v) {
    if (v instanceof ComponentHost) {
      return ((ComponentHost) v).getComponentTouchListener();
    } else {
      return (ComponentTouchListener) v.getTag(R.id.component_touch_listener);
    }
  }

  static void setComponentTouchListener(View v, ComponentTouchListener listener) {
    if (v instanceof ComponentHost) {
      ((ComponentHost) v).setComponentTouchListener(listener);
    } else {
      v.setOnTouchListener(listener);
      v.setTag(R.id.component_touch_listener, listener);
    }
  }

  private static void setViewTag(View view, Object viewTag) {
    if (view instanceof ComponentHost) {
      final ComponentHost host = (ComponentHost) view;
      host.setViewTag(viewTag);
    } else {
      view.setTag(viewTag);
    }
  }

  private static void setViewTags(View view, SparseArray<Object> viewTags) {
    if (viewTags == null) {
      return;
    }

    if (view instanceof ComponentHost) {
      final ComponentHost host = (ComponentHost) view;
      host.setViewTags(viewTags);
    } else {
      for (int i = 0, size = viewTags.size(); i < size; i++) {
        view.setTag(viewTags.keyAt(i), viewTags.valueAt(i));
      }
    }
  }

  private static void unsetViewTag(View view) {
    if (view instanceof ComponentHost) {
      final ComponentHost host = (ComponentHost) view;
      host.setViewTag(null);
    } else {
      view.setTag(null);
    }
  }

  private static void unsetViewTags(View view, SparseArray<Object> viewTags) {
    if (view instanceof ComponentHost) {
      final ComponentHost host = (ComponentHost) view;
      host.setViewTags(null);
    } else {
      if (viewTags != null) {
        for (int i = 0, size = viewTags.size(); i < size; i++) {
          view.setTag(viewTags.keyAt(i), null);
        }
      }
    }
  }

  private static void setShadowElevation(View view, float shadowElevation) {
    if (shadowElevation != 0) {
      ViewCompat.setElevation(view, shadowElevation);
    }
  }

  private static void unsetShadowElevation(View view, float shadowElevation) {
    if (shadowElevation != 0) {
      ViewCompat.setElevation(view, 0);
    }
  }

  private static void setOutlineProvider(View view, ViewOutlineProvider outlineProvider) {
    if (outlineProvider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.setOutlineProvider(outlineProvider);
    }
  }

  private static void unsetOutlineProvider(View view, ViewOutlineProvider outlineProvider) {
    if (outlineProvider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }
  }

  private static void setClipToOutline(View view, boolean clipToOutline) {
    if (clipToOutline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.setClipToOutline(clipToOutline);
    }
  }

  private static void unsetClipToOutline(View view, boolean clipToOutline) {
    if (clipToOutline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.setClipToOutline(false);
    }
  }

  private static void setContentDescription(View view, CharSequence contentDescription) {
    if (TextUtils.isEmpty(contentDescription)) {
      return;
    }

    view.setContentDescription(contentDescription);
  }

  private static void unsetContentDescription(View view) {
    view.setContentDescription(null);
  }

  private static void setImportantForAccessibility(View view, int importantForAccessibility) {
    if (importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
      return;
    }

    ViewCompat.setImportantForAccessibility(view, importantForAccessibility);
  }

  private static void unsetImportantForAccessibility(View view) {
    ViewCompat.setImportantForAccessibility(view, IMPORTANT_FOR_ACCESSIBILITY_AUTO);
  }

  private static void setFocusable(View view, @NodeInfo.FocusState short focusState) {
    if (focusState == NodeInfo.FOCUS_SET_TRUE) {
      view.setFocusable(true);
    } else if (focusState == NodeInfo.FOCUS_SET_FALSE) {
      view.setFocusable(false);
    }
  }

  private static void unsetFocusable(View view, MountItem mountItem) {
    view.setFocusable(MountItem.isViewFocusable(mountItem.getFlags()));
  }

  private static void setEnabled(View view, @NodeInfo.EnabledState short enabledState) {
    if (enabledState == NodeInfo.ENABLED_SET_TRUE) {
      view.setEnabled(true);
    } else if (enabledState == NodeInfo.ENABLED_SET_FALSE) {
      view.setEnabled(false);
    }
  }

  private static void unsetEnabled(View view, MountItem mountItem) {
    view.setEnabled(MountItem.isViewEnabled(mountItem.getFlags()));
  }

  private static void setSelected(View view, @NodeInfo.SelectedState short selectedState) {
    if (selectedState == NodeInfo.SELECTED_SET_TRUE) {
      view.setSelected(true);
    } else if (selectedState == NodeInfo.SELECTED_SET_FALSE) {
      view.setSelected(false);
    }
  }

  private static void unsetSelected(View view, MountItem mountItem) {
    view.setSelected(MountItem.isViewSelected(mountItem.getFlags()));
  }

  private static void setScale(View view, NodeInfo nodeInfo) {
    if (Build.VERSION.SDK_INT >= 11) {
      if (nodeInfo.isScaleSet()) {
        final float scale = nodeInfo.getScale();
        view.setScaleX(scale);
        view.setScaleY(scale);
      }
    }
  }

  private static void unsetScale(View view, NodeInfo nodeInfo) {
    if (Build.VERSION.SDK_INT >= 11) {
      if (nodeInfo.isScaleSet()) {
        if (view.getScaleX() != 1) {
          view.setScaleX(1);
        }
        if (view.getScaleY() != 1) {
          view.setScaleY(1);
        }
      }
    }
  }

  private static void setAlpha(View view, NodeInfo nodeInfo) {
    if (Build.VERSION.SDK_INT >= 11) {
      if (nodeInfo.isAlphaSet()) {
        view.setAlpha(nodeInfo.getAlpha());
      }
    }
  }

  private static void unsetAlpha(View view, NodeInfo nodeInfo) {
    if (Build.VERSION.SDK_INT >= 11) {
      if (nodeInfo.isAlphaSet() && view.getAlpha() != 1) {
        view.setAlpha(1);
      }
    }
  }

  private static void setViewPadding(View view, ViewNodeInfo viewNodeInfo) {
    if (!viewNodeInfo.hasPadding()) {
      return;
    }

    view.setPadding(
        viewNodeInfo.getPaddingLeft(),
        viewNodeInfo.getPaddingTop(),
        viewNodeInfo.getPaddingRight(),
        viewNodeInfo.getPaddingBottom());
  }

  private static void unsetViewPadding(View view, ViewNodeInfo viewNodeInfo) {
    if (!viewNodeInfo.hasPadding()) {
      return;
    }

    view.setPadding(0, 0, 0, 0);
  }

  private static void setViewBackground(View view, ViewNodeInfo viewNodeInfo) {
    final Reference<Drawable> backgroundReference = viewNodeInfo.getBackground();
    if (backgroundReference != null) {
      setBackgroundCompat(
          view,
          Reference.acquire((ComponentContext) view.getContext(), backgroundReference));
    }
  }

  private static void unsetViewBackground(View view, ViewNodeInfo viewNodeInfo) {
    final Reference<Drawable> backgroundReference = viewNodeInfo.getBackground();
    if (backgroundReference != null) {
      Reference.release(
          (ComponentContext) view.getContext(),
          view.getBackground(),
          backgroundReference);
      setBackgroundCompat(view, null);
    }
  }

  @SuppressWarnings("deprecation")
  private static void setBackgroundCompat(View view, Drawable drawable) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
      view.setBackgroundDrawable(drawable);
    } else {
      view.setBackground(drawable);
    }
  }

  private static void setViewForeground(View view, ViewNodeInfo viewNodeInfo) {
    final Drawable foreground = viewNodeInfo.getForeground();
    if (foreground != null) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        throw new IllegalStateException("MountState has a ViewNodeInfo with foreground however " +
            "the current Android version doesn't support foreground on Views");
      }

      view.setForeground(foreground);
    }
  }

  private static void unsetViewForeground(View view, ViewNodeInfo viewNodeInfo) {
    final Drawable foreground = viewNodeInfo.getForeground();
    if (foreground != null) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        throw new IllegalStateException("MountState has a ViewNodeInfo with foreground however " +
            "the current Android version doesn't support foreground on Views");
      }

      view.setForeground(null);
    }
  }

  private static void setViewLayoutDirection(View view, ViewNodeInfo viewNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
      return;
    }

    final int viewLayoutDirection;
    switch (viewNodeInfo.getLayoutDirection()) {
      case LTR:
        viewLayoutDirection = View.LAYOUT_DIRECTION_LTR;
        break;
      case RTL:
        viewLayoutDirection = View.LAYOUT_DIRECTION_RTL;
        break;
      default:
        viewLayoutDirection = View.LAYOUT_DIRECTION_INHERIT;
    }

    view.setLayoutDirection(viewLayoutDirection);
  }

  private static void unsetViewLayoutDirection(View view, ViewNodeInfo viewNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
      return;
    }

    view.setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
  }

  private static void setViewClipChildren(View view, ViewNodeInfo viewNodeInfo) {
    if (view instanceof ViewGroup) {
      ((ViewGroup) view).setClipChildren(viewNodeInfo.getClipChildren());
    }
  }

  private static void unsetViewClipChildren(View view) {
    if (view instanceof ViewGroup) {
      // Default value for clipChildren is 'true'.
      ((ViewGroup) view).setClipChildren(true);
    }
  }

  private static void setViewStateListAnimator(View view, ViewNodeInfo viewNodeInfo) {
    StateListAnimator stateListAnimator = viewNodeInfo.getStateListAnimator();
    if (stateListAnimator != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        view.setStateListAnimator(stateListAnimator);
      } else {
        throw new IllegalStateException(
            "MountState has a ViewNodeInfo with stateListAnimator, "
                + "however the current Android version doesn't support foreground on Views");
      }
    }
  }

  private static void unsetViewStateListAnimator(View view, ViewNodeInfo viewNodeInfo) {
    StateListAnimator stateListAnimator = viewNodeInfo.getStateListAnimator();
    if (stateListAnimator != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        view.setStateListAnimator(null);
      } else {
        throw new IllegalStateException(
            "MountState has a ViewNodeInfo with stateListAnimator, "
                + "however the current Android version doesn't support foreground on Views");
      }
    }
  }

  private static void mountItemIncrementally(
      MountItem item, Rect itemBounds, Rect localVisibleRect, boolean processVisibilityOutputs) {
    final Component component = item.getComponent();

    if (!isMountViewSpec(component)) {
      return;
    }

    // We can't just use the bounds of the View since we need the bounds relative to the
    // hosting LithoView (which is what the localVisibleRect is measured relative to).
    final View view = (View) item.getContent();
    final Rect rect = ComponentsPools.acquireRect();
    rect.set(
        Math.max(0, localVisibleRect.left - itemBounds.left),
        Math.max(0, localVisibleRect.top - itemBounds.top),
        itemBounds.width() - Math.max(0, itemBounds.right - localVisibleRect.right),
        itemBounds.height() - Math.max(0, itemBounds.bottom - localVisibleRect.bottom));

    mountViewIncrementally(view, rect, processVisibilityOutputs);

    ComponentsPools.release(rect);
  }

  private static void mountViewIncrementally(
      View view, Rect localVisibleRect, boolean processVisibilityOutputs) {
    assertMainThread();

    if (view instanceof LithoView) {
      final LithoView lithoView = (LithoView) view;
      if (lithoView.isIncrementalMountEnabled()) {
        lithoView.performIncrementalMount(localVisibleRect, processVisibilityOutputs);
      }
    } else if (view instanceof ViewGroup) {
      final ViewGroup viewGroup = (ViewGroup) view;

      for (int i = 0; i < viewGroup.getChildCount(); i++) {
        final View childView = viewGroup.getChildAt(i);

        if (localVisibleRect.intersects(
            childView.getLeft(),
            childView.getTop(),
            childView.getRight(),
            childView.getBottom())) {
          final Rect rect = ComponentsPools.acquireRect();
          rect.set(
              Math.max(0, localVisibleRect.left - childView.getLeft()),
              Math.max(0, localVisibleRect.top - childView.getTop()),
              childView.getWidth() - Math.max(0, childView.getRight() - localVisibleRect.right),
              childView.getHeight() - Math.max(0, childView.getBottom() - localVisibleRect.bottom));

          mountViewIncrementally(childView, rect, processVisibilityOutputs);

          ComponentsPools.release(rect);
        }
      }
    }
  }

  private void unmountDisappearingItemChild(ComponentContext context, MountItem item) {

    final Object content = item.getContent();

    // Recursively unmount mounted children items.
    if (content instanceof ComponentHost) {
      final ComponentHost host = (ComponentHost) content;

      for (int i = host.getMountItemCount() - 1; i >= 0; i--) {
        final MountItem mountItem = host.getMountItemAt(i);
        unmountDisappearingItemChild(context, mountItem);
      }

      if (host.getMountItemCount() > 0) {
        throw new IllegalStateException("Recursively unmounting items from a ComponentHost, left" +
            " some items behind maybe because not tracked by its MountState");
      }
    }

    final ComponentHost host = item.getHost();
    host.unmount(item);

    unsetViewAttributes(item);

    unbindAndUnmountLifecycle(context, item);

    if (item.getComponent().canMountIncrementally()) {
      final int index = mCanMountIncrementallyMountItems.indexOfValue(item);
      if (index > 0) {
        mCanMountIncrementallyMountItems.removeAt(index);
      }
    }
    ComponentsPools.release(context, item);
  }

  void unmountAllItems() {
    if (mLayoutOutputsIds == null) {
      return;
    }
    for (int i = mLayoutOutputsIds.length - 1; i >= 0; i--) {
      unmountItem(mContext, i, mHostsByMarker);
    }
  }

  private void unmountItem(
      ComponentContext context,
      int index,
      LongSparseArray<ComponentHost> hostsByMarker) {
    final MountItem item = getItemAt(index);
    final long startTime = System.nanoTime();

    // The root host item should never be unmounted as it's a reference
    // to the top-level LithoView.
    if (item == null || mLayoutOutputsIds[index] == ROOT_HOST_ID) {
      return;
    }

    final Object content = item.getContent();

    // Recursively unmount mounted children items.
    // This is the case when mountDiffing is enabled and unmountOrMoveOldItems() has a matching
    // sub tree. However, traversing the tree bottom-up, it needs to unmount a node holding that
    // sub tree, that will still have mounted items. (Different sequence number on LayoutOutput id)
    if ((content instanceof ComponentHost) && !(content instanceof LithoView)) {
      final ComponentHost host = (ComponentHost) content;

      // Concurrently remove items therefore traverse backwards.
      for (int i = host.getMountItemCount() - 1; i >= 0; i--) {
        final MountItem mountItem = host.getMountItemAt(i);
        final long layoutOutputId = mIndexToItemMap.keyAt(mIndexToItemMap.indexOfValue(mountItem));

        for (int mountIndex = mLayoutOutputsIds.length - 1; mountIndex >= 0; mountIndex--) {
          if (mLayoutOutputsIds[mountIndex] == layoutOutputId) {
            unmountItem(context, mountIndex, hostsByMarker);
            break;
          }
        }
      }

      if (host.getMountItemCount() > 0) {
        throw new IllegalStateException("Recursively unmounting items from a ComponentHost, left" +
            " some items behind maybe because not tracked by its MountState");
      }
    }

    /*
     * The mounted content might contain other LithoViews which are not reachable from
     * this MountState. If that content contains other LithoViews, we need to unmount them as well,
     * so that their contents are recycled and reused next time.
     */
    if (content instanceof HasLithoViewChildren) {
      final ArrayList<LithoView> lithoViews = ComponentsPools.acquireLithoViewArrayList();
      ((HasLithoViewChildren) content).obtainLithoViewChildren(lithoViews);

      for (int i = lithoViews.size() - 1; i >= 0; i--) {
        final LithoView lithoView = lithoViews.get(i);
        lithoView.unmountAllItems();
      }
      ComponentsPools.release(lithoViews);
    }

    final ComponentHost host = item.getHost();
    host.unmount(index, item);

    unsetViewAttributes(item);

    final Component component = item.getComponent();

    if (isHostSpec(component)) {
      final ComponentHost componentHost = (ComponentHost) content;
      hostsByMarker.removeAt(hostsByMarker.indexOfValue(componentHost));
      removeDisappearingMountContentFromComponentHost(componentHost);
    }

    unbindAndUnmountLifecycle(context, item);

    mIndexToItemMap.remove(mLayoutOutputsIds[index]);
    maybeUpdateAnimatingMountContent(item, null);

    if (component.canMountIncrementally()) {
      mCanMountIncrementallyMountItems.delete(mLayoutOutputsIds[index]);
    }

    ComponentsPools.release(context, item);

    if (mMountStats.isLoggingEnabled) {
      mMountStats.unmountedTimes.add((System.nanoTime() - startTime) / NS_IN_MS);
      mMountStats.unmountedNames.add(component.getSimpleName());
      mMountStats.unmountedCount++;
    }
  }

  private void unbindAndUnmountLifecycle(
      ComponentContext context,
      MountItem item) {
    final Component component = item.getComponent();
    final Object content = item.getContent();

    // Call the component's unmount() method.
    if (item.isBound()) {
      component.onUnbind(context, content);
      item.setIsBound(false);
    }
    component.unmount(context, content);
  }

  private void startUnmountDisappearingItem(int index, String key) {
    final MountItem item = getItemAt(index);

    if (item == null) {
      throw new RuntimeException("Item at index=" + index +" does not exist");
    }

    if (mDisappearingMountItems.put(key, item) != null) {
      throw new RuntimeException("Disappearing the same key twice!");
    }

    final ComponentHost host = item.getHost();
    host.startUnmountDisappearingItem(index, item);

    mTransitionManager.setMountContent(key, item.getContent());
  }

  private void endUnmountDisappearingItem(MountItem item) {
    if (item.getContent() instanceof ComponentHost) {
      final ComponentHost content = (ComponentHost) item.getContent();

      // Unmount descendant items in reverse order.
      for (int i = content.getMountItemCount() - 1; i >= 0; i--) {
        final MountItem mountItem = content.getMountItemAt(i);
        unmountDisappearingItemChild(mContext, mountItem);
      }

      if (content.getMountItemCount() > 0) {
        throw new IllegalStateException(
            "Recursively unmounting items from a ComponentHost, left"
                + " some items behind maybe because not tracked by its MountState");
      }
    }

    final ComponentHost host = item.getHost();
    host.unmountDisappearingItem(item);
    unsetViewAttributes(item);

    unbindAndUnmountLifecycle(mContext, item);

    if (item.getComponent().canMountIncrementally()) {
      final int index = mCanMountIncrementallyMountItems.indexOfValue(item);
      if (index > 0) {
        mCanMountIncrementallyMountItems.removeAt(index);
      }
    }
    ComponentsPools.release(mContext, item);
  }

  int getItemCount() {
    return mLayoutOutputsIds == null ? 0 : mLayoutOutputsIds.length;
  }

  MountItem getItemAt(int i) {
    return mIndexToItemMap.get(mLayoutOutputsIds[i]);
  }

  /**
   * Creates and updates transitions for a new LayoutState. The steps are as follows
   *
   * 1. Disappearing items: Update disappearing mount items that are no longer disappearing (e.g.
   *    because they came back). This means canceling the animation and cleaning up the
   *    corresponding ComponentHost.
   * 2. New transitions: Use the transition manager to create new animations.
   * 3. Update locked indices: Based on running/new animations, there are some mount items we want
   *    to make sure are not unmounted due to incremental mount and being outside of visibility
   *    bounds.
   */
  private void updateTransitions(LayoutState layoutState) {
    if (!mIsDirty) {
      throw new RuntimeException("Should only process transitions on dirty mounts");
    }

    // If this is a new component tree but isn't the first time it's been mounted, then we shouldn't
    // do any transition animations for changed mount content as it's just being remounted on a
    // new LithoView.
    final int componentTreeId = layoutState.getComponentTreeId();
    if (mLastMountedComponentTreeId != componentTreeId) {
      resetAnimationState();
      if (!mIsFirstMountOfComponentTree) {
        return;
      }
    }

    if (!mDisappearingMountItems.isEmpty()) {
      updateDisappearingMountItems(layoutState);
    }

    if (shouldAnimateTransitions(layoutState)) {
      mMountTimeTransitions = collectMountTimeTransitions(layoutState);
      if (hasTransitionsToAnimate(layoutState)) {
        createNewTransitions(layoutState);
      }
    }

    mAnimationLockedIndices = null;
    if (!mAnimatingTransitionKeys.isEmpty()) {
      regenerateAnimationLockedIndices(layoutState);
    }
  }

  private void resetAnimationState() {
    if (mTransitionManager == null) {
      return;
    }
    for (MountItem item : mDisappearingMountItems.values()) {
      endUnmountDisappearingItem(item);
    }
    mDisappearingMountItems.clear();
    mAnimatingTransitionKeys.clear();
    mTransitionManager.reset();
    mAnimationLockedIndices = null;
  }

  private void updateDisappearingMountItems(LayoutState newLayoutState) {
    SimpleArrayMap<String, LayoutOutput> nextMountedTransitionKeys =
        newLayoutState.getTransitionKeyMapping();
    for (int i = 0, size = nextMountedTransitionKeys.size(); i < size; i++) {
      final String transitionKey = nextMountedTransitionKeys.keyAt(i);
      final MountItem disappearingItem = mDisappearingMountItems.remove(transitionKey);
      if (disappearingItem != null) {
        endUnmountDisappearingItem(disappearingItem);
      }
    }
  }

  private void createNewTransitions(LayoutState newLayoutState) {
    prepareTransitionManager(newLayoutState);

    mTransitionManager.setupTransitions(
        mLastMountedLayoutState, newLayoutState, mMountTimeTransitions);

    SimpleArrayMap<String, LayoutOutput> nextTransitionKeys =
        newLayoutState.getTransitionKeyMapping();
    for (int i = 0, size = nextTransitionKeys.size(); i < size; i++) {
      final String transitionKey = nextTransitionKeys.keyAt(i);
      if (mTransitionManager.isKeyAnimating(transitionKey)) {
        mAnimatingTransitionKeys.add(transitionKey);
      }
    }
  }

  private void regenerateAnimationLockedIndices(LayoutState newLayoutState) {
    mAnimationLockedIndices = null;
    for (int i = 0, size = newLayoutState.getMountableOutputCount(); i < size; i++) {
      final LayoutOutput output = newLayoutState.getMountableOutputAt(i);
      final String transitionKey = output.getTransitionKey();
      if (transitionKey == null) {
        continue;
      }

      if (mAnimatingTransitionKeys.contains(transitionKey)) {
        lockLayoutOutputForAnimation(newLayoutState, i);
      }
    }

    if (AnimationsDebug.ENABLED) {
      AnimationsDebug.debugPrintAnimationLockedIndices(newLayoutState, mAnimationLockedIndices);
    }
  }

  private int findLastDescendantIndex(LayoutState layoutState, int index) {
    final LayoutOutput host = layoutState.getMountableOutputAt(index);
    final long hostId = host.getId();

    for (int i = index + 1, size = layoutState.getMountableOutputCount(); i < size; i++) {
      final LayoutOutput layoutOutput = layoutState.getMountableOutputAt(i);

      // Walk up the parents looking for the host's id: if we find it, it's a descendant. If we
      // reach the root, then it's not a descendant and we can stop.
      long curentHostId = layoutOutput.getHostMarker();
      while (curentHostId != hostId) {
        if (curentHostId == ROOT_HOST_ID) {
          return i - 1;
        }

        final int parentIndex = layoutState.getLayoutOutputPositionForId(curentHostId);
        final LayoutOutput parent = layoutState.getMountableOutputAt(parentIndex);
        curentHostId = parent.getHostMarker();
      }
    }

    return layoutState.getMountableOutputCount() - 1;
  }

  private void lockLayoutOutputForAnimation(LayoutState layoutState, int index) {
    if (mAnimationLockedIndices == null) {
      mAnimationLockedIndices = new int[layoutState.getMountableOutputCount()];
    }

    updateAnimationLockCount(layoutState, index, true);
  }

  private void unlockLayoutOutputForAnimation(LayoutState layoutState, int index) {
    updateAnimationLockCount(layoutState, index, false);
  }

  /**
   * Update the animation locked count for all children and each parent of the animating item.
   * Mount items that have a lock count > 0 will not be unmounted during incremental mount.
   */
  private void updateAnimationLockCount(LayoutState layoutState, int index, boolean increment) {
    // Update children
    final int lastDescendantIndex = findLastDescendantIndex(layoutState, index);
    for (int i = index; i <= lastDescendantIndex; i++) {
      if (increment) {
        mAnimationLockedIndices[i]++;
      } else {
        if (--mAnimationLockedIndices[i] < 0) {
          throw new RuntimeException("Decremented animation lock count below 0!");
        }
      }
    }

    // Update parents
    long hostId = layoutState.getMountableOutputAt(index).getHostMarker();
    while (hostId != ROOT_HOST_ID) {
      int hostIndex = layoutState.getLayoutOutputPositionForId(hostId);
      if (increment) {
        mAnimationLockedIndices[hostIndex]++;
      } else {
        if (--mAnimationLockedIndices[hostIndex] < 0) {
          throw new RuntimeException("Decremented animation lock count below 0!");
        }
      }
      hostId = layoutState.getMountableOutputAt(hostIndex).getHostMarker();
    }
  }

  /**
   * @return whether we should animate transitions if we have any when mounting the new LayoutState.
   */
  private boolean shouldAnimateTransitions(LayoutState newLayoutState) {
    return mIsDirty
        && (mLastMountedComponentTreeId == newLayoutState.getComponentTreeId()
            || mIsFirstMountOfComponentTree);
  }

  /**
   * @return whether we have any transitions to animate for the current mount of the given
   *     LayoutState
   */
  private boolean hasTransitionsToAnimate(LayoutState newLayoutState) {
    final boolean hasMountTimeTransitions =
        mMountTimeTransitions != null && !mMountTimeTransitions.isEmpty();
    return hasMountTimeTransitions || newLayoutState.hasTransitionContext();
  }

  @Override
  public void onAnimationComplete(String transitionKey) {
    final MountItem disappearingItem = mDisappearingMountItems.remove(transitionKey);
    if (disappearingItem != null) {
      endUnmountDisappearingItem(disappearingItem);
    } else {
      if (!mAnimatingTransitionKeys.remove(transitionKey)) {
        throw new RuntimeException(
            "Ending animation for key " + transitionKey + " but it wasn't recorded as animating!");
      }

      final LayoutOutput layoutOutput = mLastMountedLayoutState.getLayoutOutputForTransitionKey(
          transitionKey);
      if (layoutOutput == null) {
        // This can happen if the component was unmounted without animation or the transitionKey
        // was removed from the component.
        return;
      }

      unlockLayoutOutputForAnimation(
          mLastMountedLayoutState,
          mLastMountedLayoutState.getLayoutOutputPositionForId(layoutOutput.getId()));

      if (ComponentsConfiguration.isDebugModeEnabled && mAnimatingTransitionKeys.isEmpty()) {
        for (int i = 0, size = mAnimationLockedIndices.length; i < size; i++) {
          if (mAnimationLockedIndices[i] != 0) {
            throw new RuntimeException(
                "No running animations but index " + i + " is still animation locked!");
          }
        }
      }
    }
  }

  private static class PrepareMountStats {
    private int unmountedCount = 0;
    private int movedCount = 0;
    private int unchangedCount = 0;

    private PrepareMountStats() {}

    private void reset() {
      unchangedCount = 0;
      movedCount = 0;
      unmountedCount = 0;
    }
  }

  private static class MountStats {
    private List<String> mountedNames;
    private List<String> unmountedNames;
    private List<String> updatedNames;
    private List<String> visibilityHandlerNames;

    private List<Double> mountTimes;
    private List<Double> unmountedTimes;
    private List<Double> updatedTimes;
    private List<Double> visibilityHandlerTimes;

    private int mountedCount;
    private int unmountedCount;
    private int updatedCount;
    private int noOpCount;

    private double visibilityHandlersTotalTime;

    private boolean isLoggingEnabled;
    private boolean isInitialized;

    private void enableLogging() {
      isLoggingEnabled = true;

      if (!isInitialized) {
        isInitialized = true;
        mountedNames = new ArrayList<>();
        unmountedNames = new ArrayList<>();
        updatedNames = new ArrayList<>();
        visibilityHandlerNames = new ArrayList<>();

        mountTimes = new ArrayList<>();
        unmountedTimes = new ArrayList<>();
        updatedTimes = new ArrayList<>();
        visibilityHandlerTimes = new ArrayList<>();
      }
    }

    private void reset() {
      mountedCount = 0;
      unmountedCount = 0;
      updatedCount = 0;
      noOpCount = 0;
      visibilityHandlersTotalTime = 0;

      if (isInitialized) {
        mountedNames.clear();
        unmountedNames.clear();
        updatedNames.clear();
        visibilityHandlerNames.clear();

        mountTimes.clear();
        unmountedTimes.clear();
        updatedTimes.clear();
        visibilityHandlerTimes.clear();
      }

      isLoggingEnabled = false;
    }
  }

  /**
   * Unbinds all the MountItems currently mounted on this MountState. Unbinding a MountItem means
   * calling unbind on its {@link Component}. The MountItem is not yet unmounted after unbind is
   * called and can be re-used in place to re-mount another {@link Component} with the same
   * {@link ComponentLifecycle}.
   */
  void unbind() {
    if (mLayoutOutputsIds == null) {
      return;
    }

    for (int i = 0, size = mLayoutOutputsIds.length; i < size; i++) {
      MountItem mountItem = getItemAt(i);

      if (mountItem == null || !mountItem.isBound()) {
        continue;
      }

      final Component component = mountItem.getComponent();
      component.unbind(getContextForComponent(component), mountItem.getContent());
      mountItem.setIsBound(false);
    }

    clearVisibilityItems();
  }

  void detach() {
    unbind();
  }

  /**
   * This is called when the {@link MountItem}s mounted on this {@link MountState} need to be
   * re-bound with the same component. The common case here is a detach/attach happens on the
   * {@link LithoView} that owns the MountState.
   */
  void rebind() {
    if (mLayoutOutputsIds == null) {
      return;
    }

    for (int i = 0, size = mLayoutOutputsIds.length; i < size; i++) {
      final MountItem mountItem = getItemAt(i);
      if (mountItem == null || mountItem.isBound()) {
        continue;
      }

      final Component component = mountItem.getComponent();
      final Object content = mountItem.getContent();

      component.bind(getContextForComponent(component), content);
      mountItem.setIsBound(true);

      if (content instanceof View &&
          !(content instanceof ComponentHost) &&
          ((View) content).isLayoutRequested()) {
        final View view = (View) content;
        applyBoundsToMountContent(
            view,
            view.getLeft(),
            view.getTop(),
            view.getRight(),
            view.getBottom(),
            true);
      }
    }
  }

  /**
   * Whether the item at this index (or one of its parents) are animating. In that case, we don't
   * want to unmount this index for visibility reasons (e.g. incremental mount). The reason for this
   * is that this item (or it's parent) may have a translation X/Y that actually shows it on the
   * screen, even though the non-translated bounds are off the screen.
   */
  private boolean isAnimationLocked(int index) {
    if (mAnimationLockedIndices == null) {
      return false;
    }
    return mAnimationLockedIndices[index] > 0;
  }

  /**
   * @return true if this method did all the work that was necessary and there is no other content
   *     that needs mounting/unmounting in this mount step. If false then a full mount step should
   *     take place.
   */
  private boolean performIncrementalMount(
      LayoutState layoutState, Rect localVisibleRect, boolean processVisibilityOutputs) {
    if (mPreviousLocalVisibleRect.isEmpty()) {
      return false;
    }

    if (localVisibleRect.left != mPreviousLocalVisibleRect.left ||
        localVisibleRect.right != mPreviousLocalVisibleRect.right) {
      return false;
    }

    final ArrayList<LayoutOutput> layoutOutputTops = layoutState.getMountableOutputTops();
    final ArrayList<LayoutOutput> layoutOutputBottoms = layoutState.getMountableOutputBottoms();
    final int count = layoutState.getMountableOutputCount();

    if (localVisibleRect.top > 0 || mPreviousLocalVisibleRect.top > 0) {
      // View is going on/off the top of the screen. Check the bottoms to see if there is anything
      // that has moved on/off the top of the screen.
      while (mPreviousBottomsIndex < count &&
          localVisibleRect.top >=
              layoutOutputBottoms.get(mPreviousBottomsIndex).getBounds().bottom) {
        final long id = layoutOutputBottoms.get(mPreviousBottomsIndex).getId();
        final int layoutOutputIndex = layoutState.getLayoutOutputPositionForId(id);
        if (!isAnimationLocked(layoutOutputIndex)) {
          unmountItem(mContext, layoutOutputIndex, mHostsByMarker);
        }
        mPreviousBottomsIndex++;
      }

      while (mPreviousBottomsIndex > 0 &&
          localVisibleRect.top <
              layoutOutputBottoms.get(mPreviousBottomsIndex - 1).getBounds().bottom) {
        mPreviousBottomsIndex--;
        final LayoutOutput layoutOutput = layoutOutputBottoms.get(mPreviousBottomsIndex);
        final int layoutOutputIndex =
            layoutState.getLayoutOutputPositionForId(layoutOutput.getId());
        if (getItemAt(layoutOutputIndex) == null) {
          mountLayoutOutput(
              layoutState.getLayoutOutputPositionForId(layoutOutput.getId()),
              layoutOutput,
              layoutState);
        }
      }
    }

    final int height = mLithoView.getHeight();
    if (localVisibleRect.bottom < height || mPreviousLocalVisibleRect.bottom < height) {
      // View is going on/off the bottom of the screen. Check the tops to see if there is anything
      // that has changed.
      while (mPreviousTopsIndex < count &&
          localVisibleRect.bottom > layoutOutputTops.get(mPreviousTopsIndex).getBounds().top) {
        final LayoutOutput layoutOutput = layoutOutputTops.get(mPreviousTopsIndex);
        final int layoutOutputIndex =
            layoutState.getLayoutOutputPositionForId(layoutOutput.getId());
        if (getItemAt(layoutOutputIndex) == null) {
          mountLayoutOutput(
              layoutState.getLayoutOutputPositionForId(layoutOutput.getId()),
              layoutOutput,
              layoutState);
        }
        mPreviousTopsIndex++;
      }

      while (mPreviousTopsIndex > 0 &&
          localVisibleRect.bottom <=
              layoutOutputTops.get(mPreviousTopsIndex - 1).getBounds().top) {
        mPreviousTopsIndex--;
        final long id = layoutOutputTops.get(mPreviousTopsIndex).getId();
        final int layoutOutputIndex = layoutState.getLayoutOutputPositionForId(id);
        if (!isAnimationLocked(layoutOutputIndex)) {
          unmountItem(mContext, layoutOutputIndex, mHostsByMarker);
        }
      }
    }

    for (int i = 0, size = mCanMountIncrementallyMountItems.size(); i < size; i++) {
      final MountItem mountItem = mCanMountIncrementallyMountItems.valueAt(i);
      final int layoutOutputPosition =
          layoutState.getLayoutOutputPositionForId(mCanMountIncrementallyMountItems.keyAt(i));
      mountItemIncrementally(
          mountItem,
          layoutState.getMountableOutputAt(layoutOutputPosition).getBounds(),
          localVisibleRect,
          processVisibilityOutputs);
    }

    return true;
  }

  LithoView getLithoView() {
    return mLithoView;
  }

  private void prepareTransitionManager(LayoutState layoutState) {
    if (mTransitionManager == null) {
      mTransitionManager = new TransitionManager(this, this);
    }
  }

  private void removeDisappearingMountContentFromComponentHost(ComponentHost componentHost) {
    if (componentHost.hasDisappearingItems()) {
      List<String> disappearingKeys = componentHost.getDisappearingItemKeys();
      for (int i = 0, size = disappearingKeys.size(); i < size; i++) {
        mTransitionManager.setMountContent(disappearingKeys.get(i), null);
      }
    }
  }

  private void maybeUpdateAnimatingMountContent(MountItem mountItem, Object mountContent) {
    if (mTransitionManager == null || !mountItem.hasTransitionKey()) {
      return;
    }
    mTransitionManager.setMountContent(mountItem.getTransitionKey(), mountContent);
  }

  private static @Nullable ArrayList<Transition> collectMountTimeTransitions(
      LayoutState layoutState) {
    final List<Component> componentsNeedingPreviousRenderData =
        layoutState.getComponentsNeedingPreviousRenderData();

    if (componentsNeedingPreviousRenderData == null) {
      return null;
    }

    ArrayList<Transition> result = null;
    for (int i = 0, size = componentsNeedingPreviousRenderData.size(); i < size; i++) {
      final Component component = componentsNeedingPreviousRenderData.get(i);
      final Transition transition =
          component.onCreateTransition(component.getScopedContext());

      if (transition != null) {
        if (result == null) {
          result = new ArrayList<>();
        }
        result.add(transition);
      }
    }

    return result;
  }

  /**
   * @see LithoViewTestHelper#findTestItems(LithoView, String)
   */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  Deque<TestItem> findTestItems(String testKey) {
    if (mTestItemMap == null) {
      throw new UnsupportedOperationException("Trying to access TestItems while " +
          "ComponentsConfiguration.isEndToEndTestRun is false.");
    }

    final Deque<TestItem> items = mTestItemMap.get(testKey);
    return items == null ? new LinkedList<TestItem>() : items;
  }

  /**
   * For HostComponents, we don't set a scoped context during layout calculation because we don't
   * need one, as we could never call a state update on it. Instead it's okay to use the context
   * that is passed to MountState from the LithoView, which is not scoped.
   */
  private ComponentContext getContextForComponent(Component component) {
    final ComponentContext c = component.getScopedContext();
    return c == null ? mContext : c;
  }

  private void releaseLastMountedLayoutState() {
    if (mLastMountedLayoutState != null) {
      mLastMountedLayoutState.releaseRef();
      mLastMountedLayoutState = null;
    }
  }

  private static class LayoutOutputLog {

    long currentId = -1;
    String currentLifecycle;
    int currentIndex = -1;
    int currentLastDuplicatedIdIndex = -1;

    long nextId = -1;
    String nextLifecycle;
    int nextIndex = -1;
    int nextLastDuplicatedIdIndex = -1;

    @Override
    public String toString() {
      return "id: [" + currentId + " - " + nextId + "], "
          + "lifecycle: [" + currentLifecycle + " - " + nextLifecycle + "], "
          + "index: [" + currentIndex + " - " + nextIndex + "], "
          + "lastDuplicatedIdIndex: [" + currentLastDuplicatedIdIndex +
          " - " + nextLastDuplicatedIdIndex + "]";
    }
  }
}
