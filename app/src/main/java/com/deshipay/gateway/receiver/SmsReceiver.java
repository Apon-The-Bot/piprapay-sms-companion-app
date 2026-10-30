package com.deshipay.gateway.receiver;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.deshipay.gateway.DeshiPayApp;
import com.deshipay.gateway.R;
import com.deshipay.gateway.db.DatabaseHelper;
import com.deshipay.gateway.model.PaymentSms;
import com.deshipay.gateway.network.SyncManager;
import com.deshipay.gateway.parser.PaymentParser;
import com.deshipay.gateway.ui.MainActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        final PendingResult pendingResult = goAsync();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = null;
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeshiPay:SmsWakeLock");
                    wakeLock.acquire(15000); // 15 seconds max
                }

                try {
                    processIncomingSms(context, intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error in processIncomingSms", e);
                } finally {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    pendingResult.finish();
                }
            }
        });
    }

    public static void processIncomingSms(Context context, Intent intent) {
        SmsMessage[] messages = extractSmsMessages(intent);
        if (messages == null || messages.length == 0) {
            Log.w(TAG, "No SMS messages extracted from intent");
            return;
        }

        StringBuilder fullBody = new StringBuilder();
        String sender = "";
        long timestamp = System.currentTimeMillis();

        for (SmsMessage msg : messages) {
            if (msg != null) {
                if (sender.isEmpty()) {
                    sender = msg.getDisplayOriginatingAddress();
                    if (sender == null || sender.isEmpty()) {
                        sender = msg.getOriginatingAddress();
                    }
                }
                String bodyPart = msg.getDisplayMessageBody();
                if (bodyPart == null || bodyPart.isEmpty()) {
                    bodyPart = msg.getMessageBody();
                }
                if (bodyPart != null) {
                    fullBody.append(bodyPart);
                }
                if (msg.getTimestampMillis() > 0) {
                    timestamp = msg.getTimestampMillis();
                }
            }
        }

        int simSlot = getSimSlot(intent);
        String rawMessage = fullBody.toString();

        Log.d(TAG, "Received raw SMS from [" + sender + "] on SIM " + simSlot + ": " + rawMessage);

        // Filter and Parse payment
        PaymentSms payment = PaymentParser.parse(sender, rawMessage, simSlot, timestamp);
        if (payment == null) {
            Log.d(TAG, "Filtered out non-payment SMS from " + sender);
            return;
        }

        // Insert into local SQLite database
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(context);
        long rowId = dbHelper.insertPayment(payment);

        if (rowId != -1) {
            Log.i(TAG, "Captured Payment: " + payment.getProvider() + " ৳" + payment.getAmount() + " [Trx: " + payment.getTrxId() + "]");

            // Show notification on phone
            showPaymentNotification(context, payment);

            // Broadcast to MainActivity to refresh UI immediately
            Intent updateIntent = new Intent(SyncManager.ACTION_SYNC_STATUS);
            updateIntent.setPackage(context.getPackageName());
            context.sendBroadcast(updateIntent);

            // Attempt online sync immediately; if offline, stays in SQLite pending queue
            SyncManager.getInstance(context).syncPendingAsync(null);
        } else {
            Log.w(TAG, "Duplicate TrxID or already recorded: " + payment.getTrxId());
        }
    }

    private static SmsMessage[] extractSmsMessages(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus != null && pdus.length > 0) {
                String format = bundle.getString("format");
                SmsMessage[] messages = new SmsMessage[pdus.length];
                for (int i = 0; i < pdus.length; i++) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format != null) {
                        messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
                    } else {
                        messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
                    }
                }
                return messages;
            }
        }
        return Telephony.Sms.Intents.getMessagesFromIntent(intent);
    }

    private static int getSimSlot(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return 1;

        if (extras.containsKey("slot")) {
            return extras.getInt("slot") + 1;
        } else if (extras.containsKey("simSlot")) {
            return extras.getInt("simSlot") + 1;
        } else if (extras.containsKey("sim_id")) {
            return (int) extras.getLong("sim_id") + 1;
        } else if (extras.containsKey("subscription")) {
            return 1;
        }
        return 1;
    }

    private static void showPaymentNotification(Context context, PaymentSms payment) {
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
