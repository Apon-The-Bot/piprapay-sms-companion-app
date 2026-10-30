package com.deshipay.gateway.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.deshipay.gateway.R;
import com.deshipay.gateway.db.DatabaseHelper;
import com.deshipay.gateway.db.PreferencesManager;
import com.deshipay.gateway.model.PaymentSms;
import com.deshipay.gateway.network.SyncManager;
import com.deshipay.gateway.parser.PaymentParser;
import com.deshipay.gateway.service.DeshiPayService;
import com.deshipay.gateway.service.SmsContentObserver;
import android.os.Handler;
import android.os.Looper;
import com.google.android.material.button.MaterialButton;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_CODE = 101;

    private DatabaseHelper dbHelper;
    private PreferencesManager prefs;
    private SyncManager syncManager;
    private PaymentAdapter adapter;

    private View viewStatusDot;
    private TextView tvConnectionStatus;
    private TextView tvServerUrlDisplay;
    private TextView tvSyncSummary;
    private TextView tvStatTotal;
    private TextView tvStatSynced;
    private TextView tvStatPending;
    private TextView tvFeedCount;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private LinearLayout layoutEmpty;
    private MaterialButton btnSyncNow;
    private MaterialButton btnTestSms;
    private ImageButton btnBatteryOptimize;
    private ImageButton btnSettings;
    private TextView btnQuickPair;

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refreshData();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = DatabaseHelper.getInstance(this);
        prefs = new PreferencesManager(this);
        syncManager = SyncManager.getInstance(this);

        initViews();
        setupRecyclerView();
        checkAndRequestPermissions();
        startDeshiPayService();
        refreshData();
    }

    private void initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvServerUrlDisplay = findViewById(R.id.tvServerUrlDisplay);
        tvSyncSummary = findViewById(R.id.tvSyncSummary);
        tvStatTotal = findViewById(R.id.tvStatTotal);
        tvStatSynced = findViewById(R.id.tvStatSynced);
        tvStatPending = findViewById(R.id.tvStatPending);
        tvFeedCount = findViewById(R.id.tvFeedCount);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        recyclerView = findViewById(R.id.recyclerViewPayments);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        btnSyncNow = findViewById(R.id.btnSyncNow);
        btnTestSms = findViewById(R.id.btnTestSms);
        btnBatteryOptimize = findViewById(R.id.btnBatteryOptimize);
        btnSettings = findViewById(R.id.btnSettings);
        btnQuickPair = findViewById(R.id.btnQuickPair);

        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                scanRecentInboxSms();
                syncManager.syncPendingAsync(new SyncManager.SyncCallback() {
                    @Override
                    public void onSuccess(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                swipeRefresh.setRefreshing(false);
                                refreshData();
                            }
                        });
                    }

                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                swipeRefresh.setRefreshing(false);
                                refreshData();
                            }
                        });
                    }
                });
            }
        });

        btnSyncNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnSyncNow.setEnabled(false);
                btnSyncNow.setText("Syncing...");
                syncManager.syncPendingAsync(new SyncManager.SyncCallback() {
                    @Override
                    public void onSuccess(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnSyncNow.setEnabled(true);
                                btnSyncNow.setText("Sync Now");
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                                refreshData();
                            }
                        });
                    }

                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnSyncNow.setEnabled(true);
                                btnSyncNow.setText("Sync Now");
                                Toast.makeText(MainActivity.this, "Sync: " + error, Toast.LENGTH_SHORT).show();
                                refreshData();
                            }
                        });
                    }
                });
            }
        });

        btnTestSms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTestSmsDialog();
            }
        });

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        btnQuickPair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        btnBatteryOptimize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBatteryExemption();
            }
        });
    }

    private void setupRecyclerView() {
        adapter = new PaymentAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void refreshData() {
        // Status & URL
        tvServerUrlDisplay.setText(prefs.getServerUrl());
        boolean isPaired = prefs.isPaired();
        boolean isOnline = syncManager.isOnline();

        if (!isPaired) {
            viewStatusDot.setBackgroundResource(R.drawable.bg_badge_pending);
            tvConnectionStatus.setText("Not Paired (Device Token Required)");
        } else if (!isOnline) {
            viewStatusDot.setBackgroundResource(R.drawable.bg_badge_pending);
            tvConnectionStatus.setText("Offline (SMS Queued For Auto-Sync)");
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.bg_badge_synced);
            tvConnectionStatus.setText("Online • Gateway Connected");
        }

        tvSyncSummary.setText("Status: " + prefs.getLastSyncStatus());

        // Stats
        int total = dbHelper.getTotalCount();
        int synced = dbHelper.getCountByStatus(PaymentSms.STATUS_SYNCED);
        int pending = dbHelper.getCountByStatus(PaymentSms.STATUS_PENDING);

        tvStatTotal.setText(String.valueOf(total));
        tvStatSynced.setText(String.valueOf(synced));
        tvStatPending.setText(String.valueOf(pending));

        // List
        List<PaymentSms> list = dbHelper.getAllPayments(100);
        adapter.updateData(list);
        tvFeedCount.setText(list.size() + " items");

        if (list.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void startDeshiPayService() {
        try {
            Intent serviceIntent = new Intent(this, DeshiPayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkAndRequestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean granted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                Toast.makeText(this, "SMS permissions granted. Deshi Pay is active!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "SMS permission is required to capture payments automatically.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Please disable battery optimization for Deshi Pay in Settings.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Battery optimization is already unrestricted. 24/7 background sync is enabled!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        final EditText etServerUrl = dialogView.findViewById(R.id.etServerUrl);
        final EditText etDeviceToken = dialogView.findViewById(R.id.etDeviceToken);
        final EditText etDeviceName = dialogView.findViewById(R.id.etDeviceName);

        etServerUrl.setText(prefs.getServerUrl());
        etDeviceToken.setText(prefs.getDeviceToken());
        etDeviceName.setText(prefs.getDeviceName());

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Save Settings", null)
                .setNeutralButton("Pair With OTP", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        // Prevent auto-dismiss on button clicks so async OTP pairing works reliably
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = etServerUrl.getText().toString().trim();
                String token = etDeviceToken.getText().toString().trim();
                String name = etDeviceName.getText().toString().trim();

                if (!url.isEmpty()) prefs.setServerUrl(url);
                prefs.setDeviceToken(token);
                if (!name.isEmpty()) prefs.setDeviceName(name);

                Toast.makeText(MainActivity.this, "Settings saved!", Toast.LENGTH_SHORT).show();
                refreshData();

                if (!token.isEmpty()) {
                    syncManager.syncPendingAsync(null);
                }
                dialog.dismiss();
            }
        });

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String otp = etDeviceToken.getText().toString().trim();
                if (otp.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter OTP in the Token field", Toast.LENGTH_SHORT).show();
                    return;
                }
                String url = etServerUrl.getText().toString().trim();
                if (!url.isEmpty()) prefs.setServerUrl(url);

                Toast.makeText(MainActivity.this, "Pairing device with OTP...", Toast.LENGTH_SHORT).show();
                syncManager.pairWithOtp(otp, new SyncManager.SyncCallback() {
                    @Override
                    public void onSuccess(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                                refreshData();
                                dialog.dismiss();
                            }
                        });
                    }

                    @Override
                    public void onError(final String error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Pairing error: " + error, Toast.LENGTH_LONG).show();
                                refreshData();
                            }
                        });
                    }
                });
            }
        });
    }

    private void showTestSmsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_test_sms, null);
        final RadioGroup rgProvider = dialogView.findViewById(R.id.rgProvider);
        final EditText etTestAmount = dialogView.findViewById(R.id.etTestAmount);
        final EditText etTestSender = dialogView.findViewById(R.id.etTestSender);
        final EditText etTestTrxId = dialogView.findViewById(R.id.etTestTrxId);

        // Pre-fill random TrxID
        String randomTrx = "BLA" + (100000 + new Random().nextInt(900000));
        etTestTrxId.setText(randomTrx);

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Simulate & Test", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String amount = etTestAmount.getText().toString().trim();
                        String sender = etTestSender.getText().toString().trim();
                        String trxId = etTestTrxId.getText().toString().trim();
                        if (amount.isEmpty()) amount = "500.00";
                        if (sender.isEmpty()) sender = "01712345678";
                        if (trxId.isEmpty()) trxId = "BLA" + (100000 + new Random().nextInt(900000));

                        int checkedId = rgProvider.getCheckedRadioButtonId();
                        String fakeSms;
                        String providerSender;

                        if (checkedId == R.id.rbNagad) {
                            providerSender = "16167";
                            fakeSms = "Money Received. Amount: Tk " + amount + " Sender: " + sender + " TxnID: " + trxId + " Balance: Tk 10500.00 27/10/2026 14:30";
                        } else if (checkedId == R.id.rbRocket) {
                            providerSender = "16216";
                            fakeSms = "Tk" + amount + " received from A/C:" + sender + " Fee:Tk0.00, Your A/C Balance: Tk5000.00 TxnId:" + trxId + " Date:27-OCT-26 02:30:00 PM";
                        } else {
                            providerSender = "bKash";
                            fakeSms = "You have received Tk " + amount + " from " + sender + ". Fee Tk 0.00. Balance Tk 5000.00. TrxID " + trxId + " at 27/10/2026 14:30";
                        }

                        PaymentSms payment = PaymentParser.parse(providerSender, fakeSms, 1, System.currentTimeMillis());
                        if (payment != null) {
                            long id = dbHelper.insertPayment(payment);
                            if (id != -1) {
                                Toast.makeText(MainActivity.this, "Test Payment Recorded! Syncing...", Toast.LENGTH_SHORT).show();
                                refreshData();
                                syncManager.syncPendingAsync(null);
                            } else {
                                Toast.makeText(MainActivity.this, "Payment with TrxID " + trxId + " already exists.", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(MainActivity.this, "Parser failed to parse test message", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void scanRecentInboxSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        SmsContentObserver observer = new SmsContentObserver(MainActivity.this, new Handler(Looper.getMainLooper()));
                        observer.scanLatestInboxMessages();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                refreshData();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SyncManager.ACTION_SYNC_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, filter);
        }
        scanRecentInboxSms();
        refreshData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(syncReceiver);
        } catch (Exception ignored) {}
    }
}
