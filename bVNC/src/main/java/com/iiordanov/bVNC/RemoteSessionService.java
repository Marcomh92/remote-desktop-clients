/**
 * Copyright (C) 2026 Iordan Iordanov
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */

package com.iiordanov.bVNC;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.undatech.remoteClientUi.R;
import com.undatech.opaque.util.GeneralUtils;

/**
 * Foreground service that keeps the remote-desktop session process alive while the user
 * is not actively looking at the canvas (device locked, app backgrounded, etc.).
 *
 * Lifecycle:
 *  - Started by {@link RemoteCanvasActivity} once a connection is being established.
 *  - Stopped by {@link com.iiordanov.bVNC.protocol.RemoteConnection#closeConnection()},
 *    which is the single canonical close point invoked for user-initiated, remote-initiated,
 *    and error-induced disconnects alike.
 *  - Stopped defensively from {@link RemoteCanvasActivity#onDestroy()}.
 *
 * The notification carries the connection's nickname + address and a content intent that
 * re-launches {@link RemoteCanvasActivity} with the original connection extras so tapping
 * it returns the user to their live session.
 */
public class RemoteSessionService extends Service {

    private static final String TAG = "RemoteSessionService";

    public static final String ACTION_START = "com.iiordanov.bVNC.action.START_REMOTE_SESSION";
    public static final String ACTION_STOP  = "com.iiordanov.bVNC.action.STOP_REMOTE_SESSION";

    public static final String EXTRA_LAUNCH_EXTRAS = "launch_extras";
    public static final String EXTRA_NICKNAME      = "nickname";
    public static final String EXTRA_ADDRESS       = "address";

    private static final int NOTIFICATION_ID = 0x52445353; // 'RDSS' — stable so update reuses slot
    private static final String CHANNEL_ID = "remote_session_service";

    /**
     * Start the foreground service. Safe to call repeatedly; subsequent calls only refresh
     * the notification. Must be invoked from a foreground context (e.g. {@code onCreate}).
     */
    public static void start(Context context, Bundle launchExtras, String nickname, String address) {
        Intent intent = new Intent(context, RemoteSessionService.class);
        intent.setAction(ACTION_START);
        if (launchExtras != null) {
            intent.putExtra(EXTRA_LAUNCH_EXTRAS, launchExtras);
        }
        if (nickname != null) intent.putExtra(EXTRA_NICKNAME, nickname);
        if (address != null) intent.putExtra(EXTRA_ADDRESS, address);
        // ponytail: ContextCompat indirection is unnecessary here; both call sites are API 21+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Stop the foreground service. No-op if not running.
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, RemoteSessionService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            // Background startService is restricted on API 26+; the service will already be
            // stopped (or never started) when this is called from closeConnection on a
            // backgrounded activity, so swallow the expected failure.
            Log.d(TAG, "stop: service not startable from background; ignored.");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "onStartCommand: STOP");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            Log.i(TAG, "onStartCommand: START");
            Bundle launchExtras = (intent != null) ? intent.getBundleExtra(EXTRA_LAUNCH_EXTRAS) : null;
            String nickname = (intent != null) ? intent.getStringExtra(EXTRA_NICKNAME) : null;
            String address  = (intent != null) ? intent.getStringExtra(EXTRA_ADDRESS)  : null;
            startInForeground(launchExtras, nickname, address);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_session_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.remote_session_notification_channel_description));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private void startInForeground(Bundle launchExtras, String nickname, String address) {
        ensureChannel();

        PendingIntent contentIntent = buildContentIntent(launchExtras);

        String title = (nickname != null && !nickname.isEmpty())
                ? nickname
                : getString(R.string.remote_session_notification_title);
        String text = (address != null && !address.isEmpty())
                ? address
                : getString(R.string.remote_session_notification_text);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_screen_black_48dp)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private PendingIntent buildContentIntent(Bundle launchExtras) {
        Class<?> canvasCls;
        try {
            canvasCls = GeneralUtils.getClassByName("com.iiordanov.bVNC.RemoteCanvasActivity");
        } catch (Throwable t) {
            Log.w(TAG, "buildContentIntent: could not resolve RemoteCanvasActivity, falling back to launcher", t);
            canvasCls = null;
        }

        Intent intent;
        if (canvasCls != null) {
            intent = new Intent(this, canvasCls);
        } else {
            intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent == null) return null;
        }
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (launchExtras != null) {
            intent.putExtras(launchExtras);
        }
        return PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}