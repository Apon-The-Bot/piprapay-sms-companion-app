package com.deshipay.gateway.network;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;
import com.deshipay.gateway.db.DatabaseHelper;
import com.deshipay.gateway.db.PreferencesManager;
import com.deshipay.gateway.model.PaymentSms;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncManager {
    private static final String TAG = "SyncManager";
    public static final String ACTION_SYNC_STATUS = "com.deshipay.gateway.ACTION_SYNC_STATUS";
    public static final String EXTRA_SYNC_RESULT = "extra_sync_result";

    private static SyncManager instance;
    private final Context context;
    private final DatabaseHelper dbHelper;
    private final PreferencesManager prefs;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private boolean isSyncing = false;

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public static synchronized SyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new SyncManager(context.getApplicationContext());
        }
        return instance;
    }

    private SyncManager(Context context) {
        this.context = context;
        this.dbHelper = DatabaseHelper.getInstance(context);
        this.prefs = new PreferencesManager(context);
        this.executor = Executors.newSingleThreadExecutor();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    /**
     * Normalizes provider name to match server's senderWhitelist
     */
    public static String getGatewaySenderKey(String provider) {
        if (provider == null) return "bkash";
        String lower = provider.trim().toLowerCase();
        if (lower.contains("bkash")) return "bkash";
        if (lower.contains("nagad") || lower.contains("16167")) return "nagad";
        if (lower.contains("rocket") || lower.contains("16216")) return "16216"; // Whitelist requires '16216'
        if (lower.contains("upay")) return "upay";
        return lower;
    }

    /**
     * Triggers sync asynchronously on the internal thread pool.
     */
    public void syncPendingAsync(final SyncCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                syncPendingInternal(callback);
            }
        });
    }

    /**
     * Synchronous internal sync logic. Safe to call from background threads.
     */
    public synchronized void syncPendingInternal(SyncCallback callback) {
        if (isSyncing) {
            Log.d(TAG, "Sync already active. Skipping duplicate call.");
            if (callback != null) callback.onError("Sync already in progress");
            return;
        }

        if (!isOnline()) {
            Log.d(TAG, "Device is offline. Keeping items queued in SQLite.");
            prefs.setLastSyncStatus("Offline (Queued)");
            notifyStatus("Device is offline. Queued for auto-sync.");
            if (callback != null) callback.onError("Device is offline. Queued.");
            return;
        }

        String token = prefs.getDeviceToken();
        if (token == null || token.trim().isEmpty()) {
            Log.w(TAG, "Device token missing. Cannot sync.");
            prefs.setLastSyncStatus("Unpaired / No Token");
            notifyStatus("Device token missing. Please configure in settings.");
            if (callback != null) callback.onError("Device not paired with server");
            return;
        }

        List<PaymentSms> pendingList = dbHelper.getPendingPayments();
        if (pendingList.isEmpty()) {
            Log.d(TAG, "All payments are already synced.");
            prefs.setLastSyncStatus("All Synced");
            notifyStatus("All payments synced");
            if (callback != null) callback.onSuccess("All payments already synced");
            return;
        }

        isSyncing = true;
        int successCount = 0;
        int failCount = 0;
        String serverUrl = prefs.getServerUrl();
        if (!serverUrl.endsWith("/")) {
            serverUrl = serverUrl + "/";
        }

        try {
            for (PaymentSms payment : pendingList) {
                try {
                    String gatewaySender = getGatewaySenderKey(payment.getProvider());

                    JSONArray jsonArray = new JSONArray();
                    JSONObject item = new JSONObject();
                    item.put("id", String.valueOf(payment.getId()));
                    item.put("sender", gatewaySender);
                    item.put("message", payment.getRawMessage());
                    item.put("simSlot", String.valueOf(payment.getSimSlot()));
                    item.put("timestamp", String.valueOf(payment.getTimestamp() / 1000));
                    jsonArray.put(item);

                    FormBody formBody = new FormBody.Builder()
                            .add("action-companion", "sms-transmit-bulk")
                            .add("token", token)
                            .add("sms_list", jsonArray.toString())
                            .build();

                    Request request = new Request.Builder()
                            .url(serverUrl)
                            .post(formBody)
                            .addHeader("User-Agent", "DeshiPay-Companion/1.0")
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        String respBody = response.body() != null ? response.body().string() : "";
                        Log.d(TAG, "Sync response for TrxID " + payment.getTrxId() + " (Sender: " + gatewaySender + "): " + respBody);

                        if (response.isSuccessful()) {
                            boolean isSuccess = false;
                            try {
                                JSONObject json = new JSONObject(respBody);
                                String status = json.optString("status", "false");
                                if ("true".equalsIgnoreCase(status) 
                                        || respBody.contains("SMS Data Created") 
                                        || respBody.contains("created successfully")
                                        || respBody.contains("already exists")) {
                                    isSuccess = true;
                                }
                            } catch (Exception parseEx) {
                                if (respBody.contains("\"status\":\"true\"") || respBody.contains("created successfully")) {
                                    isSuccess = true;
                                }
                            }

                            if (isSuccess) {
                                dbHelper.markSynced(payment.getId(), System.currentTimeMillis());
                                successCount++;
                            } else {
                                dbHelper.markFailed(payment.getId(), respBody.length() > 80 ? respBody.substring(0, 80) : respBody);
                                failCount++;
                            }
                        } else {
                            dbHelper.markFailed(payment.getId(), "HTTP " + response.code());
                            failCount++;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Sync error for payment " + payment.getTrxId(), e);
                    dbHelper.markFailed(payment.getId(), e.getMessage());
                    failCount++;
                }
            }

            prefs.setLastSyncTime(System.currentTimeMillis());
            String summary = "Synced: " + successCount + (failCount > 0 ? " (Pending/Error: " + failCount + ")" : "");
            prefs.setLastSyncStatus(summary);
            notifyStatus(summary);

            if (callback != null) {
                if (failCount == 0) {
                    callback.onSuccess(summary);
                } else {
                    callback.onError(summary);
                }
            }
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Pairs device using gateway one-time password (OTP).
     */
    public void pairWithOtp(final String otp, final SyncCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String serverUrl = prefs.getServerUrl();
                    if (!serverUrl.endsWith("/")) {
                        serverUrl = serverUrl + "/";
                    }

                    FormBody formBody = new FormBody.Builder()
                            .add("action-companion", "login")
                            .add("onetimepassword", otp.trim())
                            .add("name", prefs.getDeviceName())
                            .add("model", Build.MODEL)
                            .add("android_level", String.valueOf(Build.VERSION.SDK_INT))
                            .add("app_version", "1.0.0")
                            .build();

                    Request request = new Request.Builder()
                            .url(serverUrl)
                            .post(formBody)
                            .addHeader("User-Agent", "DeshiPay-Companion/1.0")
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        String respBody = response.body() != null ? response.body().string() : "";
                        Log.d(TAG, "Pair response: " + respBody);

                        if (response.isSuccessful()) {
                            JSONObject json = new JSONObject(respBody);
                            String status = json.optString("status", "false");
                            if ("true".equalsIgnoreCase(status) && json.has("token")) {
                                String token = json.getString("token");
                                prefs.setDeviceToken(token);
                                prefs.setLastSyncStatus("Paired Successfully");
                                if (callback != null) callback.onSuccess("Paired! Token: " + token);
                                // Flush any pending payments immediately
                                syncPendingInternal(null);
                                return;
                            }
                            String msg = json.optString("message", "Invalid OTP or pairing failed");
                            if (callback != null) callback.onError(msg);
                        } else {
                            if (callback != null) callback.onError("Server returned HTTP " + response.code());
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Pairing error", e);
                    if (callback != null) callback.onError("Connection error: " + e.getMessage());
                }
            }
        });
    }

    private void notifyStatus(String result) {
        Intent intent = new Intent(ACTION_SYNC_STATUS);
        intent.putExtra(EXTRA_SYNC_RESULT, result);
        context.sendBroadcast(intent);
    }
}
