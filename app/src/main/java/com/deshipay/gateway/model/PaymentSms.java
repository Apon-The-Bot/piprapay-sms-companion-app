package com.deshipay.gateway.model;

import java.io.Serializable;

public class PaymentSms implements Serializable {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SYNCED = 1;
    public static final int STATUS_FAILED = 2;

    private long id;
    private String provider;
    private String senderNumber;
    private String amount;
    private String trxId;
    private String fee;
    private String balance;
    private String ref;
    private int simSlot;
    private String rawMessage;
    private long timestamp;
    private int syncStatus;
    private long syncTime;
    private String syncError;

    public PaymentSms() {
        this.simSlot = 1;
        this.syncStatus = STATUS_PENDING;
        this.timestamp = System.currentTimeMillis();
    }

    public PaymentSms(String provider, String senderNumber, String amount, String trxId, 
                      String fee, String balance, String ref, int simSlot, String rawMessage, long timestamp) {
        this.provider = provider;
        this.senderNumber = senderNumber;
        this.amount = amount;
        this.trxId = trxId;
        this.fee = fee;
        this.balance = balance;
        this.ref = ref;
        this.simSlot = simSlot;
        this.rawMessage = rawMessage;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.syncStatus = STATUS_PENDING;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getProvider() { return provider != null ? provider : "Unknown"; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getSenderNumber() { return senderNumber != null ? senderNumber : ""; }
    public void setSenderNumber(String senderNumber) { this.senderNumber = senderNumber; }

    public String getAmount() { return amount != null ? amount : "0"; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getTrxId() { return trxId != null ? trxId : ""; }
    public void setTrxId(String trxId) { this.trxId = trxId; }

    public String getFee() { return fee != null ? fee : "0"; }
    public void setFee(String fee) { this.fee = fee; }

    public String getBalance() { return balance != null ? balance : "0"; }
    public void setBalance(String balance) { this.balance = balance; }

    public String getRef() { return ref != null ? ref : ""; }
    public void setRef(String ref) { this.ref = ref; }

    public int getSimSlot() { return simSlot; }
    public void setSimSlot(int simSlot) { this.simSlot = simSlot; }

    public String getRawMessage() { return rawMessage != null ? rawMessage : ""; }
    public void setRawMessage(String rawMessage) { this.rawMessage = rawMessage; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public int getSyncStatus() { return syncStatus; }
    public void setSyncStatus(int syncStatus) { this.syncStatus = syncStatus; }

    public long getSyncTime() { return syncTime; }
    public void setSyncTime(long syncTime) { this.syncTime = syncTime; }

    public String getSyncError() { return syncError != null ? syncError : ""; }
    public void setSyncError(String syncError) { this.syncError = syncError; }

    public boolean isPending() {
        return syncStatus == STATUS_PENDING;
    }

    public boolean isSynced() {
        return syncStatus == STATUS_SYNCED;
    }
}
