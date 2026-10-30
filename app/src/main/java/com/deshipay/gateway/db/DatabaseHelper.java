package com.deshipay.gateway.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import com.deshipay.gateway.model.PaymentSms;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseHelper";
    private static final String DATABASE_NAME = "deshipay.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_PAYMENTS = "payments";
    public static final String COL_ID = "id";
    public static final String COL_PROVIDER = "provider";
    public static final String COL_SENDER = "sender_number";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_TRX_ID = "trx_id";
    public static final String COL_FEE = "fee";
    public static final String COL_BALANCE = "balance";
    public static final String COL_REF = "ref";
    public static final String COL_SIM_SLOT = "sim_slot";
    public static final String COL_RAW_MESSAGE = "raw_message";
    public static final String COL_TIMESTAMP = "timestamp";
    public static final String COL_SYNC_STATUS = "sync_status";
    public static final String COL_SYNC_TIME = "sync_time";
    public static final String COL_SYNC_ERROR = "sync_error";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_PAYMENTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_PROVIDER + " TEXT, "
                + COL_SENDER + " TEXT, "
                + COL_AMOUNT + " TEXT, "
                + COL_TRX_ID + " TEXT UNIQUE, "
                + COL_FEE + " TEXT, "
                + COL_BALANCE + " TEXT, "
                + COL_REF + " TEXT, "
                + COL_SIM_SLOT + " INTEGER, "
                + COL_RAW_MESSAGE + " TEXT, "
                + COL_TIMESTAMP + " INTEGER, "
                + COL_SYNC_STATUS + " INTEGER DEFAULT 0, "
                + COL_SYNC_TIME + " INTEGER DEFAULT 0, "
                + COL_SYNC_ERROR + " TEXT"
                + ")";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PAYMENTS);
        onCreate(db);
    }

    /**
     * Inserts a payment SMS into SQLite. If trxId already exists, ignores to prevent duplicates.
     */
    public synchronized long insertPayment(PaymentSms payment) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_PROVIDER, payment.getProvider());
        values.put(COL_SENDER, payment.getSenderNumber());
        values.put(COL_AMOUNT, payment.getAmount());
        values.put(COL_TRX_ID, payment.getTrxId());
        values.put(COL_FEE, payment.getFee());
        values.put(COL_BALANCE, payment.getBalance());
        values.put(COL_REF, payment.getRef());
        values.put(COL_SIM_SLOT, payment.getSimSlot());
        values.put(COL_RAW_MESSAGE, payment.getRawMessage());
        values.put(COL_TIMESTAMP, payment.getTimestamp());
        values.put(COL_SYNC_STATUS, payment.getSyncStatus());
        values.put(COL_SYNC_TIME, payment.getSyncTime());
        values.put(COL_SYNC_ERROR, payment.getSyncError());

        long id = db.insertWithOnConflict(TABLE_PAYMENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) {
            payment.setId(id);
            Log.d(TAG, "Payment inserted with ID: " + id + ", TrxID: " + payment.getTrxId());
        } else {
            Log.w(TAG, "Duplicate or failed insert for TrxID: " + payment.getTrxId());
        }
        return id;
    }

    public synchronized boolean existsByTrxId(String trxId) {
        if (trxId == null || trxId.trim().isEmpty()) return false;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT id FROM " + TABLE_PAYMENTS + " WHERE " + COL_TRX_ID + " = ? LIMIT 1",
                new String[]{trxId.trim()});
        boolean exists = (cursor != null && cursor.moveToFirst());
        if (cursor != null) cursor.close();
        return exists;
    }

    public synchronized void markSynced(long id, long syncTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_SYNC_STATUS, PaymentSms.STATUS_SYNCED);
        values.put(COL_SYNC_TIME, syncTime);
        values.put(COL_SYNC_ERROR, "");
        db.update(TABLE_PAYMENTS, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void markFailed(long id, String error) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_SYNC_STATUS, PaymentSms.STATUS_FAILED);
        values.put(COL_SYNC_ERROR, error);
        db.update(TABLE_PAYMENTS, values, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public synchronized List<PaymentSms> getPendingPayments() {
        List<PaymentSms> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_PAYMENTS, null, COL_SYNC_STATUS + " = ?",
                new String[]{String.valueOf(PaymentSms.STATUS_PENDING)}, null, null, COL_ID + " ASC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToPayment(cursor));
            }
            cursor.close();
        }
        return list;
    }

    public synchronized List<PaymentSms> getAllPayments(int limit) {
        List<PaymentSms> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String limitClause = limit > 0 ? String.valueOf(limit) : null;
        Cursor cursor = db.query(TABLE_PAYMENTS, null, null, null, null, null, COL_TIMESTAMP + " DESC", limitClause);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToPayment(cursor));
            }
            cursor.close();
        }
        return list;
    }

    public synchronized int getCountByStatus(int status) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_PAYMENTS + " WHERE " + COL_SYNC_STATUS + " = ?",
                new String[]{String.valueOf(status)});
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }
        return count;
    }

    public synchronized int getTotalCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_PAYMENTS, null);
        int count = 0;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
        }
        return count;
    }

    public synchronized void deletePayment(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_PAYMENTS, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_PAYMENTS, null, null);
    }

    private PaymentSms cursorToPayment(Cursor cursor) {
        PaymentSms item = new PaymentSms();
        item.setId(cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)));
        item.setProvider(cursor.getString(cursor.getColumnIndexOrThrow(COL_PROVIDER)));
        item.setSenderNumber(cursor.getString(cursor.getColumnIndexOrThrow(COL_SENDER)));
        item.setAmount(cursor.getString(cursor.getColumnIndexOrThrow(COL_AMOUNT)));
        item.setTrxId(cursor.getString(cursor.getColumnIndexOrThrow(COL_TRX_ID)));
        item.setFee(cursor.getString(cursor.getColumnIndexOrThrow(COL_FEE)));
        item.setBalance(cursor.getString(cursor.getColumnIndexOrThrow(COL_BALANCE)));
        item.setRef(cursor.getString(cursor.getColumnIndexOrThrow(COL_REF)));
        item.setSimSlot(cursor.getInt(cursor.getColumnIndexOrThrow(COL_SIM_SLOT)));
        item.setRawMessage(cursor.getString(cursor.getColumnIndexOrThrow(COL_RAW_MESSAGE)));
        item.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
        item.setSyncStatus(cursor.getInt(cursor.getColumnIndexOrThrow(COL_SYNC_STATUS)));
        item.setSyncTime(cursor.getLong(cursor.getColumnIndexOrThrow(COL_SYNC_TIME)));
        item.setSyncError(cursor.getString(cursor.getColumnIndexOrThrow(COL_SYNC_ERROR)));
        return item;
    }
}
