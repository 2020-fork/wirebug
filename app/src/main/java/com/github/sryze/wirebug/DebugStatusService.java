/*
 * This file is part of Wirebug.
 *
 * Wirebug is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Wirebug is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Wirebug.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.sryze.wirebug;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class DebugStatusService extends Service {
    public static final String ACTION_UPDATE_STATUS =
        "com.github.sryze.wirebug.debugstatus.action.UPDATE_STATUS";
    public static final String ACTION_STATUS_CHANGED =
        "com.github.sryze.wirebug.debugstatus.action.STATUS_CHANGED";
    public static final String EXTRA_IS_ENABLED =
        "com.github.sryze.wirebug.debugstatus.extra.IS_ENABLED";

    private static final String TAG = "DebugStatusService";
    private static final int STATUS_NOTIFICATION_ID = 1;
    private static final long STATUS_UPDATE_INTERVAL = 5000;

    private boolean isCurrentlyEnabled;
    private PowerManager.WakeLock wakeLock;

    private SharedPreferences preferences;
    private AlarmManager alarmManager;
    private NotificationManager notificationManager;
    private KeyguardManager keyguardManager;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "Service is created");

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
            TAG);

        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
            && intent.getAction() != null
            && intent.getAction().equals(ACTION_UPDATE_STATUS)) {
            updateStatus();

            Intent updateStatusIntent =
                new Intent(DebugStatusService.this, DebugStatusService.class);
            updateStatusIntent.setAction(ACTION_UPDATE_STATUS);
            PendingIntent alarmPendingIntent = PendingIntent.getService(
                DebugStatusService.this,
                0,
                updateStatusIntent,
                PendingIntent.FLAG_CANCEL_CURRENT);
            alarmManager.cancel(alarmPendingIntent);
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + STATUS_UPDATE_INTERVAL,
                alarmPendingIntent);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (wakeLock != null && wakeLock.isHeld()) {
            Log.i(TAG, "Releasing the wake lock");
            wakeLock.release();
        }

        Log.d(TAG, "Service is destroyed");
    }

    private void updateStatus() {
        Log.i(TAG, "Performing a status update...");

        boolean isEnabled = DebugManager.isTcpDebuggingEnabled();
        if (isEnabled != isCurrentlyEnabled) {
            Log.i(TAG, String.format(
                "Status has changed to %s", isEnabled ? "enabled" : "disabled"));
            sendStatusChangedBroadcast(isEnabled);
        } else {
            Log.i(TAG, "Status is unchanged");
        }

        if (keyguardManager.inKeyguardRestrictedInputMode()
            && preferences.getBoolean("disable_on_lock", false)) {
            Log.i(TAG, "Disabling debugging because disable_on_lock is true");
            DebugManager.setTcpDebuggingEnabled(false);
        }

        if (isEnabled) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

            boolean isConnectedToWifi = NetworkUtils.isConnectedToWifi(connectivityManager);
            Log.d(TAG, String.format("Connected to Wi-Fi: %s", isConnectedToWifi ? "yes" : "no"));

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
            notificationBuilder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentIntent(pendingIntent);

            if (isConnectedToWifi) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                notificationBuilder.setContentText(
                    String.format(
                        getString(R.string.notification_text),
                        NetworkUtils.getStringFromIpAddress(wifiInfo.getIpAddress()),
                        wifiInfo.getSSID()));
            } else {
                notificationBuilder.setContentText(
                    getString(R.string.notification_text_not_connected));
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                notificationBuilder
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            }

            Notification notification = notificationBuilder.build();
            notification.flags |= Notification.FLAG_NO_CLEAR;
            notificationManager.notify(STATUS_NOTIFICATION_ID, notification);
        } else {
            Log.d(TAG, "Canceling the notification");
            notificationManager.cancel(STATUS_NOTIFICATION_ID);
        }

        if (isEnabled && preferences.getBoolean("stay_awake", false)) {
            if (wakeLock != null && !wakeLock.isHeld()) {
                Log.i(TAG, "Acquiring a wake lock because stay_awake is true");
                wakeLock.acquire();
            }
        } else {
            if (wakeLock != null && wakeLock.isHeld()) {
                Log.i(TAG, "Releasing the wake lock");
                wakeLock.release();
            }
        }

        isCurrentlyEnabled = isEnabled;
    }

    private void sendStatusChangedBroadcast(boolean isEnabled) {
        Intent statusChangedIntent = new Intent(ACTION_STATUS_CHANGED);
        statusChangedIntent.putExtra(EXTRA_IS_ENABLED, isEnabled);
        sendBroadcast(statusChangedIntent);
    }
}
