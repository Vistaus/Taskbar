/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.activity;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.view.WindowManager;

import com.farmerbb.taskbar.activity.dark.InvisibleActivityAltDark;
import com.farmerbb.taskbar.service.DashboardService;
import com.farmerbb.taskbar.service.NotificationService;
import com.farmerbb.taskbar.service.StartMenuService;
import com.farmerbb.taskbar.service.TaskbarService;
import com.farmerbb.taskbar.util.FreeformHackHelper;
import com.farmerbb.taskbar.util.IconCache;
import com.farmerbb.taskbar.util.LauncherHelper;
import com.farmerbb.taskbar.util.U;

public class InvisibleActivityFreeform extends Activity {

    boolean showTaskbar = false;
    boolean doNotHide = false;
    boolean proceedWithOnCreate = true;
    boolean finish = false;
    boolean bootToFreeform = false;
    boolean initialLaunch = true;

    private BroadcastReceiver appearingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            doNotHide = true;
        }
    };

    private BroadcastReceiver disappearingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            doNotHide = false;
        }
    };

    private BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reallyFinish();
        }
    };

    @SuppressLint("HardwareIds")
    @TargetApi(Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FreeformHackHelper helper = FreeformHackHelper.getInstance();
        if(helper.isFreeformHackActive()) {
            proceedWithOnCreate = false;
            super.finish();
        }

        if(getIntent().hasExtra("check_multiwindow")) {
            showTaskbar = false;

            if(!isInMultiWindowMode()) {
                proceedWithOnCreate = false;
                super.finish();
            }
        }

        if(U.isOverridingFreeformHack(this)) {
            helper.setFreeformHackActive(true);
            helper.setInFreeformWorkspace(true);

            proceedWithOnCreate = false;
            super.finish();
            overridePendingTransition(0, 0);
        }

        if(proceedWithOnCreate) {
            // Detect outside touches, and pass them through to the underlying activity
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

            IntentFilter appearingReceiverFilter = new IntentFilter();
            appearingReceiverFilter.addAction("com.farmerbb.taskbar.START_MENU_APPEARING");
            appearingReceiverFilter.addAction("com.farmerbb.taskbar.CONTEXT_MENU_APPEARING");
            appearingReceiverFilter.addAction("com.farmerbb.taskbar.DASHBOARD_APPEARING");

            IntentFilter disappearingReceiverFilter = new IntentFilter();
            disappearingReceiverFilter.addAction("com.farmerbb.taskbar.START_MENU_DISAPPEARING");
            disappearingReceiverFilter.addAction("com.farmerbb.taskbar.CONTEXT_MENU_DISAPPEARING");
            disappearingReceiverFilter.addAction("com.farmerbb.taskbar.DASHBOARD_DISAPPEARING");

            LocalBroadcastManager.getInstance(this).registerReceiver(appearingReceiver, appearingReceiverFilter);
            LocalBroadcastManager.getInstance(this).registerReceiver(disappearingReceiver, disappearingReceiverFilter);
            LocalBroadcastManager.getInstance(this).registerReceiver(finishReceiver, new IntentFilter("com.farmerbb.taskbar.FINISH_FREEFORM_ACTIVITY"));

            helper.setFreeformHackActive(true);

            // Show power button warning on CyanogenMod / LineageOS devices
            if(getPackageManager().hasSystemFeature("com.cyanogenmod.android")) {
                SharedPreferences pref = U.getSharedPreferences(this);
                if(!pref.getString("power_button_warning", "null").equals(Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID))) {
                    new Handler().postDelayed(() -> {
                        if(helper.isInFreeformWorkspace()) {
                            Intent intent = null;

                            switch(pref.getString("theme", "light")) {
                                case "light":
                                    intent = new Intent(this, InvisibleActivityAlt.class);
                                    break;
                                case "dark":
                                    intent = new Intent(this, InvisibleActivityAltDark.class);
                                    break;
                            }

                            if(intent != null) {
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                intent.putExtra("power_button_warning", true);
                            }

                            U.startActivityMaximized(getApplicationContext(), intent);
                        }
                    }, 100);
                }
            }

            showTaskbar = true;
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    @Override
    protected void onResume() {
        super.onResume();

        // Show the taskbar when activity is resumed (no other freeform windows are active)
        if(showTaskbar)
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.SHOW_TASKBAR"));

        if(!isInMultiWindowMode() && !initialLaunch)
            reallyFinish();

        initialLaunch = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(proceedWithOnCreate) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(appearingReceiver);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(disappearingReceiver);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(finishReceiver);

            cleanup();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        FreeformHackHelper.getInstance().setInFreeformWorkspace(true);

        if(U.launcherIsDefault(this) && !U.isChromeOs(this)) {
            LauncherHelper.getInstance().setOnHomeScreen(true);
            bootToFreeform = true;

            SharedPreferences pref = U.getSharedPreferences(this);
            if(pref.getBoolean("first_run", true)) {
                SharedPreferences.Editor editor = pref.edit();
                editor.putBoolean("first_run", false);
                editor.putBoolean("collapsed", true);
                editor.apply();

                new Handler().postDelayed(() -> {
                    Intent intent = new Intent(this, DummyActivity.class);
                    intent.putExtra("show_recent_apps_dialog", true);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    startActivity(intent);
                }, 250);
            }

            // We always start the Taskbar and Start Menu services, even if the app isn't normally running
            startService(new Intent(this, TaskbarService.class));
            startService(new Intent(this, StartMenuService.class));
            startService(new Intent(this, DashboardService.class));

            if(pref.getBoolean("taskbar_active", false) && !U.isServiceRunning(this, NotificationService.class))
                pref.edit().putBoolean("taskbar_active", false).apply();

            // Show the taskbar when activity is started
            if(showTaskbar)
                new Handler().postDelayed(() -> LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.SHOW_TASKBAR")), 100);
        }

        // Show the taskbar when activity is started
        if(showTaskbar)
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.SHOW_TASKBAR"));
    }

    @Override
    protected void onPause() {
        super.onPause();

        possiblyHideTaskbar();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if(!finish) FreeformHackHelper.getInstance().setInFreeformWorkspace(false);

        possiblyHideTaskbar();

        if(bootToFreeform && !finish) {
            LauncherHelper.getInstance().setOnHomeScreen(false);
            bootToFreeform = false;

            // Stop the Taskbar and Start Menu services if they should normally not be active
            SharedPreferences pref = U.getSharedPreferences(this);
            if(!pref.getBoolean("taskbar_active", false) || pref.getBoolean("is_hidden", false)) {
                stopService(new Intent(this, TaskbarService.class));
                stopService(new Intent(this, StartMenuService.class));
                stopService(new Intent(this, DashboardService.class));

                IconCache.getInstance(this).clearCache();
            }
        }
    }

    // We don't want this activity to finish under normal circumstances
    @Override
    public void finish() {}

    private void possiblyHideTaskbar() {
        if(!doNotHide) {
            new Handler().postDelayed(() -> {
                if(U.shouldCollapse(this, false) && !LauncherHelper.getInstance().isOnHomeScreen())
                    LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_TASKBAR"));
                else
                    LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));
            }, 100);
        }
    }

    private void reallyFinish() {
        super.finish();
        overridePendingTransition(0, 0);

        cleanup();
    }

    private void cleanup() {
        if(!finish) {
            FreeformHackHelper helper = FreeformHackHelper.getInstance();
            helper.setFreeformHackActive(false);
            helper.setInFreeformWorkspace(false);

            finish = true;
        }
    }
}