/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.DISABLE_CLOCK;
import static android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS;
import static android.app.StatusBarManager.DISABLE_SYSTEM_INFO;

import android.annotation.Nullable;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.ImageSwitcher;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.phone.TickerView;
import com.android.systemui.statusbar.policy.EncryptionHelper;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import android.widget.ImageView;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;


/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_IN_DELAY = 50;
    private PhoneStatusBarView mStatusBar;
    private StatusBarStateController mStatusBarStateController;
    private KeyguardMonitor mKeyguardMonitor;
    private NetworkController mNetworkController;
    private LinearLayout mSystemIconArea;
    private View mClockView;
    private int mClockStyle;
    private View mNotificationIconAreaInner;
    private View mCenteredIconArea;
    private int mDisabled1;
    private StatusBar mStatusBarComponent;
    private DarkIconManager mDarkIconManager;
    private View mOperatorNameFrame;
    private CommandQueue mCommandQueue;
    private LinearLayout mCenterClockLayout;
    private View mRightClock;
    private boolean mShowClock = true;
    private final Handler mHandler = new Handler();
    private ContentResolver mContentResolver;

    // custom carrier label
    private View mCustomCarrierLabel;
    private int mShowCarrierLabel;
    private boolean mHasCarrierLabel;

    private ImageView mXtendedLogo;
    private ImageView mXtendedLogoRight;
    private int mLogoStyle;
    private int mShowLogo;
    private int mLogoColor;
    // Statusbar Weather Image
    private View mWeatherImageView;
    private View mWeatherTextView;
    private int mShowWeather;
    private boolean mWeatherInHeaderView;

    private class SettingsObserver extends ContentObserver {
       SettingsObserver(Handler handler) {
           super(handler);
       }

       void observe() {
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_CARRIER),
	            false, this, UserHandle.USER_ALL);
	 mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO),
                    false, this, UserHandle.USER_ALL);
	 mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO_STYLE),
		    false, this, UserHandle.USER_ALL);
	 mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_LOGO_COLOR),
		    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP),
                    false, this, UserHandle.USER_ALL);
         mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION),
                    false, this, UserHandle.USER_ALL);
       }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if ((uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO))) ||
                (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_STYLE))) ||
                (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_LOGO_COLOR)))){
                 updateLogoSettings(true);
            } else if ((uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP))) ||
                (uri.equals(Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION)))){
	    }
            updateSettings(true);
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    // Notification ticker
    private int mTickerEnabled;
    private View mTickerViewFromStub;
    // AICP additions
    private View mBatteryBars[] = new View[2];

    private SignalCallback mSignalCallback = new SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            mCommandQueue.recomputeDisableFlags(getContext().getDisplayId(), true /* animate */);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContentResolver = getContext().getContentResolver();
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mNetworkController = Dependency.get(NetworkController.class);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mStatusBarComponent = SysUiServiceProvider.getComponent(getContext(), StatusBar.class);
        mCommandQueue = SysUiServiceProvider.getComponent(getContext(), CommandQueue.class);
        mSettingsObserver.observe();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {

         if(isDualStatusbarEnabled()) {
            return inflater.inflate(R.layout.status_bar_dual, container, false);
         } else {
             return inflater.inflate(R.layout.status_bar, container, false);
         }
    }

    private boolean isDualStatusbarEnabled() {
        return Settings.System.getInt(getContext().getContentResolver(),
            Settings.System.STATUSBAR_DUAL_ROW, 0) == 1;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStatusBar = (PhoneStatusBarView) view;
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager = new DarkIconManager(view.findViewById(R.id.statusIcons));
        mDarkIconManager.setShouldLog(true);
        Dependency.get(StatusBarIconController.class).addIconGroup(mDarkIconManager);
        mSystemIconArea = mStatusBar.findViewById(R.id.system_icon_area);
        mClockView = mStatusBar.findViewById(R.id.clock);
        mCenterClockLayout = (LinearLayout) mStatusBar.findViewById(R.id.center_clock_layout);
        mRightClock = mStatusBar.findViewById(R.id.right_clock);
        mBatteryBars[0] = mStatusBar.findViewById(R.id.battery_bar);
        mBatteryBars[1] = mStatusBar.findViewById(R.id.battery_bar_1);
        mCustomCarrierLabel = mStatusBar.findViewById(R.id.statusbar_carrier_text);
	mXtendedLogo = mStatusBar.findViewById(R.id.status_bar_logo);
	mXtendedLogoRight = mStatusBar.findViewById(R.id.status_bar_logo_right);
        mWeatherTextView = mStatusBar.findViewById(R.id.weather_temp);
        mWeatherImageView = mStatusBar.findViewById(R.id.weather_image);
	updateLogoSettings(false);
        showSystemIconArea(false);
        initEmergencyCryptkeeperText();
        animateHide(mClockView, false, false);
        initOperatorName();
        mSettingsObserver.observe();
        updateSettings(false);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Dependency.get(StatusBarIconController.class).removeIconGroup(mDarkIconManager);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            mNetworkController.removeCallback(mSignalCallback);
        }
    }

    public void initNotificationIconArea(NotificationIconAreaController
            notificationIconAreaController) {
        ViewGroup notificationIconArea = mStatusBar.findViewById(R.id.notification_icon_area);
        mNotificationIconAreaInner =
                notificationIconAreaController.getNotificationInnerAreaView();
        if (mNotificationIconAreaInner.getParent() != null) {
            ((ViewGroup) mNotificationIconAreaInner.getParent())
                    .removeView(mNotificationIconAreaInner);
        }
        notificationIconArea.addView(mNotificationIconAreaInner);

        ViewGroup statusBarCenteredIconArea = mStatusBar.findViewById(R.id.centered_icon_area);
        mCenteredIconArea = notificationIconAreaController.getCenteredNotificationAreaView();
        if (mCenteredIconArea.getParent() != null) {
            ((ViewGroup) mCenteredIconArea.getParent())
                    .removeView(mCenteredIconArea);
        }
        statusBarCenteredIconArea.addView(mCenteredIconArea);

        // Default to showing until we know otherwise.
        showNotificationIconArea(false);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        state1 = adjustDisableFlags(state1);
        final int old1 = mDisabled1;
        final int diff1 = state1 ^ old1;
        mDisabled1 = state1;
        if ((diff1 & DISABLE_SYSTEM_INFO) != 0) {
            if ((state1 & DISABLE_SYSTEM_INFO) != 0) {
                hideSystemIconArea(animate);
                hideOperatorName(animate);
                hideSbWeather(animate);
            } else {
                showSystemIconArea(animate);
                showOperatorName(animate);
                showSbWeather(animate);
            }
        }
        if ((diff1 & DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((state1 & DISABLE_NOTIFICATION_ICONS) != 0) {
                hideNotificationIconArea(animate);
                hideCarrierName(animate);
                animateHide(mClockView, animate, mClockStyle == 0);
            } else {
                showNotificationIconArea(animate);
                updateClockStyle(animate);
                showCarrierName(animate);
            }
        }
    }

    protected int adjustDisableFlags(int state) {
        boolean headsUpVisible = mStatusBarComponent.headsUpShouldBeVisible();
        if (headsUpVisible) {
            state |= DISABLE_CLOCK;
        }

        if (!mKeyguardMonitor.isLaunchTransitionFadingAway()
                && !mKeyguardMonitor.isKeyguardFadingAway()
                && shouldHideNotificationIcons()
                && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && headsUpVisible)) {
            state |= DISABLE_NOTIFICATION_ICONS;
            state |= DISABLE_SYSTEM_INFO;
            state |= DISABLE_CLOCK;
        }


        if (mNetworkController != null && EncryptionHelper.IS_DATA_ENCRYPTED) {
            if (mNetworkController.hasEmergencyCryptKeeperText()) {
                state |= DISABLE_NOTIFICATION_ICONS;
            }
            if (!mNetworkController.isRadioOn()) {
                state |= DISABLE_SYSTEM_INFO;
            }
        }

        // The shelf will be hidden when dozing with a custom clock, we must show notification
        // icons in this occasion.
        if (mStatusBarStateController.isDozing()
                && mStatusBarComponent.getPanel().hasCustomClock()) {
            state |= DISABLE_CLOCK | DISABLE_SYSTEM_INFO;
        }

        return state;
    }

    private boolean shouldHideNotificationIcons() {
        if (!mStatusBar.isClosed() && mStatusBarComponent.hideStatusBarIconsWhenExpanded()) {
            return true;
        }
        if (mStatusBarComponent.hideStatusBarIconsForBouncer()) {
            return true;
        }
        return false;
    }

    public void hideSystemIconArea(boolean animate) {
        animateHide(mCenterClockLayout, animate, true);
        if (mClockStyle == 2) {
            animateHide(mRightClock, animate, true);
        }
        animateHide(mSystemIconArea, animate, true);
        if (mShowLogo == 2) {
            animateHide(mXtendedLogoRight, animate, false);
        }
        for (View batteryBar: mBatteryBars) {
            animateHide(batteryBar, animate, false);
        }
    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mCenterClockLayout, animate);
        if (mClockStyle == 2) {
            animateShow(mRightClock, animate);
        }
        animateShow(mSystemIconArea, animate);
        if (mShowLogo == 2) {
            animateShow(mXtendedLogoRight, animate);
        }
        for (View batteryBar: mBatteryBars) {
            animateShow(batteryBar, animate);
        }
    }

/*    public void hideClock(boolean animate) {
        animateHide(mClockView, animate, false);
    }

    public void showClock(boolean animate) {
        animateShow(mClockView, animate);
    }
*/
    /**
     * If panel is expanded/expanding it usually means QS shade is opening, so
     * don't set the clock GONE otherwise it'll mess up the animation.
    private int clockHiddenMode() {
        if (!mStatusBar.isClosed() && !mKeyguardMonitor.isShowing()
                && !mStatusBarStateController.isDozing()) {
            return View.INVISIBLE;
        }
        return View.GONE;
    }*/

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate, true);
        animateHide(mCenteredIconArea, animate, true);
	animateHide(mCenterClockLayout, animate, true);
        if (mShowLogo == 1) {
            animateHide(mXtendedLogo, animate, false);
        }
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenteredIconArea, animate);
        animateShow(mCenterClockLayout, animate);
         if (mShowLogo == 1) {
             animateShow(mXtendedLogo, animate);
         }
    }

    public void hideOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateHide(mOperatorNameFrame, animate, true);
        }
    }

    public void showOperatorName(boolean animate) {
        if (mOperatorNameFrame != null) {
            animateShow(mOperatorNameFrame, animate);
        }
    }

    public void hideCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            animateHide(mCustomCarrierLabel, animate, mHasCarrierLabel);
        }
    }

    public void showCarrierName(boolean animate) {
        if (mCustomCarrierLabel != null) {
            setCarrierLabel(animate);
        }
    }

    public void hideSbWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0
	    && mWeatherTextView != null && mWeatherImageView != null) {
            animateHide(mWeatherTextView, animate, false);
            animateHide(mWeatherImageView, animate, false);
	}
    }

    public void showSbWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0) {
	    updateSBWeather(animate);
	}
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate, final boolean invisible) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(invisible ? View.INVISIBLE : View.GONE);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(invisible ? View.INVISIBLE : View.GONE));
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        if (v instanceof Clock && !((Clock)v).isClockVisible()) {
            return;
        }
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardMonitor.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardMonitor.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardMonitor.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initEmergencyCryptkeeperText() {
        View emergencyViewStub = mStatusBar.findViewById(R.id.emergency_cryptkeeper_text);
        if (mNetworkController.hasEmergencyCryptKeeperText()) {
            if (emergencyViewStub != null) {
                ((ViewStub) emergencyViewStub).inflate();
            }
            mNetworkController.addCallback(mSignalCallback);
        } else if (emergencyViewStub != null) {
            ViewGroup parent = (ViewGroup) emergencyViewStub.getParent();
            parent.removeView(emergencyViewStub);
        }
    }

    private void initOperatorName() {
        if (getResources().getBoolean(R.bool.config_showOperatorNameInStatusBar)) {
            ViewStub stub = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameFrame = stub.inflate();
        }
    }

    @Override
    public void onStateChanged(int newState) {

    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        disable(getContext().getDisplayId(), mDisabled1, mDisabled1, false /* animate */);
    }

    public void updateSettings(boolean animate) {
        if (mStatusBar == null) return;

        if (getContext() == null) {
            return;
        }

        try {
        mShowClock = Settings.System.getIntForUser(mContentResolver,
                Settings.System.STATUS_BAR_CLOCK, 1,
                UserHandle.USER_CURRENT) == 1;
        if (!mShowClock) {
            mClockStyle = 1; // internally switch to centered clock layout because
                             // left & right will show up again after QS pulldown
        } else {
            mClockStyle = Settings.System.getIntForUser(mContentResolver,
                    Settings.System.STATUSBAR_CLOCK_STYLE, 0,
                    UserHandle.USER_CURRENT);
        }
        mShowCarrierLabel = Settings.System.getIntForUser(mContentResolver,
                Settings.System.STATUS_BAR_SHOW_CARRIER, 0, UserHandle.USER_CURRENT);
        mHasCarrierLabel = (mShowCarrierLabel == 2 || mShowCarrierLabel == 3);
        mShowWeather = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0, UserHandle.USER_CURRENT);
        mWeatherInHeaderView = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION, 0, UserHandle.USER_CURRENT) == 1;
        mTickerEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_TICKER, 0, UserHandle.USER_CURRENT);
        } catch (Exception e) {
        }
	updateClockStyle(animate);
        setCarrierLabel(animate);
        updateSBWeather(animate);
        initTickerView();
    }

    public void updateLogoSettings(boolean animate) {
        Drawable logo = null;

        if (mStatusBar == null) return;

        if (getContext() == null) {
            return;
        }

        mShowLogo = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO, 0,
                UserHandle.USER_CURRENT);
        mLogoColor = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO_COLOR, 0xffff8800,
                UserHandle.USER_CURRENT);
        mLogoStyle = Settings.System.getIntForUser(
                getContext().getContentResolver(), Settings.System.STATUS_BAR_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);

        switch(mLogoStyle) {
                // Xtnd Old
            case 1:
                logo = getContext().getDrawable(R.drawable.ic_xtnd_logo);
                break;
                // XTND Short
            case 2:
                logo = getContext().getDrawable(R.drawable.ic_xtnd_short);
                break;
                // GZR Skull
            case 3:
                logo = getContext().getResources().getDrawable(R.drawable.status_bar_gzr_skull_logo);
                break;
                // GZR Circle
            case 4:
                logo = getContext().getResources().getDrawable(R.drawable.status_bar_gzr_circle_logo);
                break;
                // Batman
            case 5:
                logo = getContext().getDrawable(R.drawable.ic_batman_logo);
                break;
                // Deadpool
            case 6:
                logo = getContext().getDrawable(R.drawable.ic_deadpool_logo);
                break;
                // Superman
            case 7:
                logo = getContext().getDrawable(R.drawable.ic_superman_logo);
                break;
                // Ironman
            case 8:
                logo = getContext().getDrawable(R.drawable.ic_ironman_logo);
                break;
                // Spiderman
            case 9:
                logo = getContext().getDrawable(R.drawable.ic_spiderman_logo);
                break;
                // Decepticons
            case 10:
                logo = getContext().getDrawable(R.drawable.ic_decpeticons_logo);
                break;
                // Minions
            case 11:
                logo = getContext().getDrawable(R.drawable.ic_minions_logo);
                break;
            case 12:
                logo = getContext().getDrawable(R.drawable.ic_android_logo);
                break;
                // Shit
            case 13:
                logo = getContext().getDrawable(R.drawable.ic_apple_logo);
                break;
                // Shitty Logo
            case 14:
                logo = getContext().getDrawable(R.drawable.ic_ios_logo);
                break;
                // Others
            case 15:
                logo = getContext().getDrawable(R.drawable.ic_blackberry);
                break;
            case 16:
                logo = getContext().getDrawable(R.drawable.ic_cake);
                break;
            case 17:
                logo = getContext().getDrawable(R.drawable.ic_blogger);
                break;
            case 18:
                logo = getContext().getDrawable(R.drawable.ic_biohazard);
                break;
            case 19:
                logo = getContext().getDrawable(R.drawable.ic_linux);
                break;
            case 20:
                logo = getContext().getDrawable(R.drawable.ic_yin_yang);
                break;
            case 21:
                logo = getContext().getDrawable(R.drawable.ic_windows);
                break;
            case 22:
                logo = getContext().getDrawable(R.drawable.ic_robot);
                break;
            case 23:
                logo = getContext().getDrawable(R.drawable.ic_ninja);
                break;
            case 24:
                logo = getContext().getDrawable(R.drawable.ic_heart);
                break;
            case 25:
                logo = getContext().getDrawable(R.drawable.ic_ghost);
                break;
            case 26:
                logo = getContext().getDrawable(R.drawable.ic_google);
                break;
            case 27:
                logo = getContext().getDrawable(R.drawable.ic_human_male);
                break;
            case 28:
                logo = getContext().getDrawable(R.drawable.ic_human_female);
                break;
            case 29:
                logo = getContext().getDrawable(R.drawable.ic_human_male_female);
                break;
            case 30:
                logo = getContext().getDrawable(R.drawable.ic_gender_male);
                break;
            case 31:
                logo = getContext().getDrawable(R.drawable.ic_gender_female);
                break;
            case 32:
                logo = getContext().getDrawable(R.drawable.ic_gender_male_female);
                break;
            case 33:
                logo = getContext().getDrawable(R.drawable.ic_guitar_electric);
                break;
                // Default (Xtended Main)
            case 0:
            default:
                logo = getContext().getDrawable(R.drawable.status_bar_logo);
                break;
        }

        if (mShowLogo == 1) {
	    mXtendedLogo.setImageDrawable(null);
	    mXtendedLogo.setImageDrawable(logo);
 	    mXtendedLogo.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	} else if (mShowLogo == 2) {
	    mXtendedLogoRight.setImageDrawable(null);
	    mXtendedLogoRight.setImageDrawable(logo);
	    mXtendedLogoRight.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	}

        if (mNotificationIconAreaInner != null) {
            if (mShowLogo == 1) {
                if (mNotificationIconAreaInner.getVisibility() == View.VISIBLE) {
                    animateShow(mXtendedLogo, animate);
                }
            } else if (mShowLogo != 1) {
                animateHide(mXtendedLogo, animate, false);
            }
        }
        if (mSystemIconArea != null) {
            if (mShowLogo == 2) {
                if (mSystemIconArea.getVisibility() == View.VISIBLE) {
                    animateShow(mXtendedLogoRight, animate);
                }
            } else if (mShowLogo != 2) {
                   animateHide(mXtendedLogoRight, animate, false);
            }
        }
    }

    private void updateClockStyle(boolean animate) {
        if (mClockStyle == 1 || mClockStyle == 2) {
            animateHide(mClockView, animate, false);
        } else {
            if (((Clock)mClockView).isClockVisible()) {
                 animateShow(mClockView, animate);
            }
        }
    }

    private void setCarrierLabel(boolean animate) {
        if (mHasCarrierLabel) {
            animateShow(mCustomCarrierLabel, animate);
        } else {
            animateHide(mCustomCarrierLabel, animate, false);
        }
    }

    private void updateSBWeather(boolean animate) {
        if (!mWeatherInHeaderView && mShowWeather != 0) {
            if (mShowWeather == 1 || mShowWeather == 2
	        || mShowWeather == 3 || mShowWeather == 4) {
                animateShow(mWeatherTextView, animate);
                animateShow(mWeatherImageView, animate);
            } else if (mShowWeather == 3 || mShowWeather == 4){
                animateShow(mWeatherTextView, animate);
                animateHide(mWeatherImageView, animate, false);
            } else if (mShowWeather == 5) {
                animateShow(mWeatherImageView, animate);
                animateHide(mWeatherTextView, animate, false);
            }
        }
    }

    private void initTickerView() {
        if (mTickerEnabled != 0) {
            View tickerStub = mStatusBar.findViewById(R.id.ticker_stub);
            if (mTickerViewFromStub == null && tickerStub != null) {
                mTickerViewFromStub = ((ViewStub) tickerStub).inflate();
            }
            TickerView tickerView = (TickerView) mStatusBar.findViewById(R.id.tickerText);
            ImageSwitcher tickerIcon = (ImageSwitcher) mStatusBar.findViewById(R.id.tickerIcon);
            mStatusBarComponent.createTicker(
                    mTickerEnabled, getContext(), mStatusBar, tickerView, tickerIcon, mTickerViewFromStub);
        } else {
            mStatusBarComponent.disableTicker();
        }
    }
}
