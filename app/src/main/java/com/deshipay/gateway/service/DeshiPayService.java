package com.deshipay.gateway.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Telephony;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.deshipay.gateway.DeshiPayApp;
import com.deshipay.gateway.R;
import com.deshipay.gateway.db.DatabaseHelper;
import com.deshipay.gateway.db.PreferencesManager;
import com.deshipay.gateway.network.SyncManager;
import com.deshipay.gateway.receiver.SmsReceiver;
import com.deshipay.gateway.ui.MainActivity;

public class DeshiPayService extends Service {
    private static final String TAG = "DeshiPayService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long PERIODIC_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PreferencesManager prefs;
    private DatabaseHelper dbHelper;

    private SmsReceiver dynamicSmsReceiver;
    private SmsContentObserver smsContentObserver;

    private final Runnable periodicSyncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (prefs.isServiceActive()) {
                    int pendingCount = dbHelper.getCountByStatus(0);
                    if (pendingCount > 0) {
                        Log.d(TAG, "Periodic check found " + pendingCount + " pending payments. Syncing...");
                        SyncManager.getInstance(getApplicationContext()).syncPendingAsync(null);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Periodic sync check error", e);
            } finally {
                handler.postDelayed(this, PERIODIC_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new PreferencesManager(this);
        dbHelper = DatabaseHelper.getInstance(this);

        startForegroundNotification();
        registerDynamicSmsReceiver();
        registerSmsContentObserver();
        registerNetworkCallback();
        handler.postDelayed(periodicSyncRunnable, PERIODIC_INTERVAL_MS);
        Log.i(TAG, "DeshiPayService started and listening for incoming SMS");
    }

    private void registerDynamicSmsReceiver() {
        try {
            dynamicSmsReceiver = new SmsReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicSmsReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(dynamicSmsReceiver, filter);
            }
            Log.i(TAG, "Dynamic SmsReceiver registered successfully in Foreground Service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register dynamic SmsReceiver", e);
        }
    }

    private void registerSmsContentObserver() {
        try {
            smsContentObserver = new SmsContentObserver(this, handler);
            getContentResolver().registerContentObserver(
                    Uri.parse("content://sms"),
                    true,
                    smsContentObserver
            );
            Log.i(TAG, "SmsContentObserver registered for content://sms");

            // Perform initial quick scan of recent inbox messages
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (smsContentObserver != null) {
                        smsContentObserver.scanLatestInboxMessages();
                    }
                }
            }, 1500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register SmsContentObserver", e);
        }
    }

    private void startForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, DeshiPayApp.CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_desc))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        Log.i(TAG, "Internet connection restored! Triggering auto-sync for pending payments...");
                        SyncManager.getInstance(getApplicationContext()).syncPendingAsync(null);
                    }

                    @Override
                    public void onLost(Network network) {
                        super.onLost(network);
                        Log.d(TAG, "Internet connection lost. New payments will be queued offline.");
                    }
                };

                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicSyncRunnable);

        if (dynamicSmsReceiver != null) {
            try {
                unregisterReceiver(dynamicSmsReceiver);
            } catch (Exception ignored) {}
        }

        if (smsContentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(smsContentObserver);
            } catch (Exception ignored) {}
        }

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "DeshiPayService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
