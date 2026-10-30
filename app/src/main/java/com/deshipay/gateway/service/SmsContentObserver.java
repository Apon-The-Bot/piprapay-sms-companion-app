package com.deshipay.gateway.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.Telephony;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.deshipay.gateway.DeshiPayApp;
import com.deshipay.gateway.R;
import com.deshipay.gateway.db.DatabaseHelper;
import com.deshipay.gateway.model.PaymentSms;
import com.deshipay.gateway.network.SyncManager;
import com.deshipay.gateway.parser.PaymentParser;
import com.deshipay.gateway.ui.MainActivity;

public class SmsContentObserver extends ContentObserver {
    private static final String TAG = "SmsContentObserver";
    private final Context context;
    private final DatabaseHelper dbHelper;
    private long lastProcessedTime = 0;

    public SmsContentObserver(Context context, Handler handler) {
        super(handler);
        this.context = context.getApplicationContext();
        this.dbHelper = DatabaseHelper.getInstance(this.context);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        // Throttle rapid triggers
        long now = System.currentTimeMillis();
        if (now - lastProcessedTime < 800) {
            return;
        }
        lastProcessedTime = now;

        scanLatestInboxMessages();
    }

    public synchronized void scanLatestInboxMessages() {
        Cursor cursor = null;
        try {
            Uri inboxUri = Uri.parse("content://sms/inbox");
            String[] projection = new String[]{"_id", "address", "body", "date", "sub_id"};
            cursor = context.getContentResolver().query(
                    inboxUri,
                    projection,
                    null,
                    null,
                    "date DESC LIMIT 15"
            );

            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }

            int countNew = 0;
            do {
                int addressIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                int dateIdx = cursor.getColumnIndex("date");
                int subIdIdx = cursor.getColumnIndex("sub_id");

                String address = addressIdx != -1 ? cursor.getString(addressIdx) : "";
                String body = bodyIdx != -1 ? cursor.getString(bodyIdx) : "";
                long date = dateIdx != -1 ? cursor.getLong(dateIdx) : System.currentTimeMillis();
                int simSlot = subIdIdx != -1 ? cursor.getInt(subIdIdx) + 1 : 1;

                if (body == null || body.trim().isEmpty()) {
                    continue;
                }

                // Check if payment
                PaymentSms payment = PaymentParser.parse(address, body, simSlot, date);
                if (payment != null) {
                    if (!dbHelper.existsByTrxId(payment.getTrxId())) {
                        long rowId = dbHelper.insertPayment(payment);
                        if (rowId != -1) {
                            countNew++;
                            Log.i(TAG, "ContentObserver captured Payment: " + payment.getProvider() + " ৳" + payment.getAmount() + " [Trx: " + payment.getTrxId() + "]");
                            showPaymentNotification(context, payment);
                        }
                    }
                }
            } while (cursor.moveToNext());

            if (countNew > 0) {
                // Notify UI
                Intent updateIntent = new Intent(SyncManager.ACTION_SYNC_STATUS);
                updateIntent.setPackage(context.getPackageName());
                context.sendBroadcast(updateIntent);

                // Sync immediately
                SyncManager.getInstance(context).syncPendingAsync(null);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error scanning inbox messages", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void showPaymentNotification(Context context, PaymentSms payment) {
        try {
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, (int) payment.getId(), openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DeshiPayApp.CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Payment: ৳" + payment.getAmount() + " (" + payment.getProvider() + ")")
                    .setContentText("TrxID: " + payment.getTrxId() + " from " + payment.getSenderNumber())
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to display payment notification", e);
        }
    }
}
