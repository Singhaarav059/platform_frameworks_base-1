/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.app.ActivityManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.ColorUtils;
import android.content.res.Configuration;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.omni.StatusBarHeaderMachine;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.Dependency;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link BaseStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout implements
        StatusBarHeaderMachine.IStatusBarHeaderMachineObserver {

    private final Point mSizePoint = new Point();

    private int mHeightOverride = -1;
    private QSPanel mQSPanel;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private View mQSFooter;

    private View mBackground;
    private View mBackgroundGradient;
    private View mStatusBarBackground;

    private int mSideMargins;
    private boolean mQsDisabled;
    private Drawable mQsBackGround;

    private boolean mHeaderImageEnabled;
    private ImageView mBackgroundImage;
    private StatusBarHeaderMachine mStatusBarHeaderMachine;
    private Drawable mCurrentBackground;
    private boolean mLandscape;
    private int mQsBackGroundAlpha;
    private boolean mQsBackgroundAlpha;
    private int mQsBackGroundColor;
    private int mQsBackGroundColorWall;
    private int mCurrentColor;
    private boolean mSetQsFromWall;
    private boolean mSetQsFromAccent;
    private boolean mSetQsFromResources;
    private SysuiColorExtractor mColorExtractor;

    private IOverlayManager mOverlayManager;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        Handler mHandler = new Handler();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        mStatusBarHeaderMachine = new StatusBarHeaderMachine(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = (QSCustomizer) findViewById(R.id.qs_customize);
        mQSFooter = findViewById(R.id.qs_footer);
        mBackground = findViewById(R.id.quick_settings_background);
        mStatusBarBackground = findViewById(R.id.quick_settings_status_bar_background);
        mBackgroundGradient = findViewById(R.id.quick_settings_gradient_view);
        mSideMargins = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);
        mBackgroundImage = findViewById(R.id.qs_header_image_view);
        mBackgroundImage.setClipToOutline(true);
        mColorExtractor = Dependency.get(SysuiColorExtractor.class);
        updateSettings();

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMargins();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarHeaderMachine.addObserver(this);
        mStatusBarHeaderMachine.updateEnablement();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStatusBarHeaderMachine.removeObserver(this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setBackgroundGradientVisibility(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

        // Hide the backgrounds when in landscape mode.
        if (mLandscape) {
            mBackgroundGradient.setVisibility(View.INVISIBLE);
        } else if (!mQsBackgroundAlpha || !mLandscape) {
            mBackgroundGradient.setVisibility(View.VISIBLE);
        }

        updateResources();
        updateStatusbarVisibility();
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.OMNI_QS_PANEL_BG_ALPHA), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_COLOR), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_COLOR_WALL), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_WALL), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_ACCENT), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_PANEL_BG_USE_FW), false,
                    this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        ContentResolver resolver = getContext().getContentResolver();
        int userQsWallColorSetting = Settings.System.getIntForUser(resolver,
                    Settings.System.QS_PANEL_BG_USE_WALL, 0, UserHandle.USER_CURRENT);
        mSetQsFromWall = userQsWallColorSetting == 1;
        int userQsFwSetting = Settings.System.getIntForUser(resolver,
                    Settings.System.QS_PANEL_BG_USE_FW, 1, UserHandle.USER_CURRENT);
        mSetQsFromResources = userQsFwSetting == 1;
        mSetQsFromAccent = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.QS_PANEL_BG_USE_ACCENT, 0, UserHandle.USER_CURRENT) == 1;
        mQsBackGroundAlpha = Settings.System.getIntForUser(resolver,
                Settings.System.OMNI_QS_PANEL_BG_ALPHA, 255,
                UserHandle.USER_CURRENT);
        mQsBackGroundColor = ColorUtils.getValidQsColor(Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_COLOR, ColorUtils.genRandomQsColor(),
                UserHandle.USER_CURRENT));
        mQsBackGroundColorWall = ColorUtils.getValidQsColor(Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_PANEL_BG_COLOR_WALL, ColorUtils.genRandomQsColor(),
                UserHandle.USER_CURRENT));
        WallpaperColors systemColors = null;
        if (mColorExtractor != null) {
            systemColors = mColorExtractor.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        }
        mCurrentColor = mSetQsFromAccent
                ? getContext().getResources().getColor(R.color.accent_device_default_light)
                : mSetQsFromWall ? mQsBackGroundColorWall : mQsBackGroundColor;
        setQsBackground();
    }

    private void setQsBackground() {
        if (mSetQsFromResources) {
            mQsBackGround = getContext().getDrawable(R.drawable.qs_background_primary);
            try {
                mOverlayManager.setEnabled("com.android.systemui.qstheme.color",
                        false, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w("QSContainerImpl", "Can't change qs theme", e);
            }
        } else {
            if (mQsBackGround != null) {
                mQsBackGround.setColorFilter(mCurrentColor, PorterDuff.Mode.SRC_ATOP);
            }
            try {
                mOverlayManager.setEnabled("com.android.systemui.qstheme.color",
                        true, ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Log.w("QSContainerImpl", "Can't change qs theme", e);
            }
        }
        if (mQsBackGround != null)
            mQsBackGround.setAlpha(mQsBackGroundAlpha);
        if (mQsBackGround != null && mBackground != null)
            mBackground.setBackground(mQsBackGround);
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        Configuration config = getResources().getConfiguration();
        boolean navBelow = config.smallestScreenWidthDp >= 600
                || config.orientation != Configuration.ORIENTATION_LANDSCAPE;
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanel.getLayoutParams();

        // The footer is pinned to the bottom of QSPanel (same bottoms), therefore we don't need to
        // subtract its height. We do not care if the collapsed notifications fit in the screen.
        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();
        if (navBelow) {
            maxQs -= getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
        }
        // Measure with EXACTLY. That way, PagedTileLayout will only use excess height and will be
        // measured last, after other views and padding is accounted for.
        mQSPanel.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.EXACTLY));
        int width = mQSPanel.getMeasuredWidth();
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanel.getMeasuredHeight() + getPaddingBottom();
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }


    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanel) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        setBackgroundGradientVisibility(getResources().getConfiguration());
        mBackground.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
    }

    private void updateResources() {
        int topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (mHeaderImageEnabled ?
                mContext.getResources().getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);

        int statusBarSideMargin = mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_header_image_side_margin) : 0;

        int gradientTopMargin = !mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) : 0;

        ((LayoutParams) mQSPanel.getLayoutParams()).topMargin = topMargin;
        mQSPanel.setLayoutParams(mQSPanel.getLayoutParams());

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mStatusBarBackground.getLayoutParams();
        lp.height = topMargin;
        lp.setMargins(statusBarSideMargin, 0, statusBarSideMargin, 0);
        mStatusBarBackground.setLayoutParams(lp);

        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) mBackgroundGradient.getLayoutParams();
        mlp.setMargins(0, gradientTopMargin, 0, 0);
        mBackgroundGradient.setLayoutParams(mlp);

        if (mHeaderImageEnabled) {
            mQsBackgroundAlpha = false;
            mStatusBarBackground.setBackgroundColor(Color.TRANSPARENT);
        } else {
            mQsBackgroundAlpha = true;
            mStatusBarBackground.setBackgroundColor(getResources().getColor(R.color.quick_settings_status_bar_background_color));
        }
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        int height = calculateContainerHeight();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + height);
        // Pin QS Footer to the bottom of the panel.
        mQSFooter.setTranslationY(height - mQSFooter.getHeight());
        mBackground.setTop(mQSPanel.getTop());
        mBackground.setBottom(height);
    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    private void setBackgroundGradientVisibility(Configuration newConfig) {
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) {
            mBackgroundGradient.setVisibility(View.INVISIBLE);
            mStatusBarBackground.setVisibility(View.INVISIBLE);
        } else {
            mBackgroundGradient.setVisibility(mQsDisabled ? View.INVISIBLE : View.VISIBLE);
            mStatusBarBackground.setVisibility(View.VISIBLE);
        }
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        updateExpansion();
    }

    private void setMargins() {
        setMargins(mQSDetail);
        setMargins(mBackground);
        setMargins(mQSFooter);
        mQSPanel.setMargins(mSideMargins);
        mHeader.setMargins(mSideMargins);
    }

    private void setMargins(View view) {
        FrameLayout.LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.rightMargin = mSideMargins;
        lp.leftMargin = mSideMargins;
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    @Override
    public void updateHeader(final Drawable headerImage, final boolean force) {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(headerImage, force);
            }
        });
    }

    @Override
    public void disableHeader() {
        post(new Runnable() {
            public void run() {
                mCurrentBackground = null;
                mBackgroundImage.setVisibility(View.GONE);
                mHeaderImageEnabled = false;
                updateResources();
                updateStatusbarVisibility();
            }
        });
    }

    @Override
    public void refreshHeader() {
        post(new Runnable() {
            public void run() {
                doUpdateStatusBarCustomHeader(mCurrentBackground, true);
            }
        });
    }

    private void doUpdateStatusBarCustomHeader(final Drawable next, final boolean force) {
        if (next != null) {
            mBackgroundImage.setVisibility(View.VISIBLE);
            mCurrentBackground = next;
            setNotificationPanelHeaderBackground(next, force);
            mHeaderImageEnabled = true;
            updateResources();
            updateStatusbarVisibility();
        } else {
            mCurrentBackground = null;
            mBackgroundImage.setVisibility(View.GONE);
            mHeaderImageEnabled = false;
            updateResources();
            updateStatusbarVisibility();
        }
    }

    private void setNotificationPanelHeaderBackground(final Drawable dw, final boolean force) {
        if (mBackgroundImage.getDrawable() != null && !force) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = mBackgroundImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            mBackgroundImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            mBackgroundImage.setImageDrawable(dw);
        }
        applyHeaderBackgroundShadow();
    }

    private void applyHeaderBackgroundShadow() {
        final int headerShadow = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER_SHADOW, 0,
                UserHandle.USER_CURRENT);

        if (mCurrentBackground != null) {
            float shadow = headerShadow;
            mBackgroundImage.setImageAlpha(255-headerShadow);
        }
    }

    private void updateStatusbarVisibility() {
        boolean shouldHideStatusbar = mLandscape && !mHeaderImageEnabled;
        mStatusBarBackground.setVisibility(shouldHideStatusbar ? View.INVISIBLE : View.VISIBLE);
    }
}
