package com.deshipay.gateway.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.deshipay.gateway.R;
import com.deshipay.gateway.model.PaymentSms;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PaymentAdapter extends RecyclerView.Adapter<PaymentAdapter.PaymentViewHolder> {
    private final Context context;
    private final List<PaymentSms> items = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault());

    public PaymentAdapter(Context context) {
        this.context = context;
    }

    public void updateData(List<PaymentSms> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PaymentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_payment, parent, false);
        return new PaymentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PaymentViewHolder holder, int position) {
        PaymentSms item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class PaymentViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvProvider;
        private final TextView tvAmount;
        private final TextView tvSyncStatus;
        private final TextView tvTrxId;
        private final ImageView btnCopyTrx;
        private final TextView tvSender;
        private final TextView tvRef;
        private final TextView tvTimestamp;
        private final TextView tvSimSlot;

        public PaymentViewHolder(@NonNull View itemView) {
            super(itemView);
            tvProvider = itemView.findViewById(R.id.tvProvider);
            tvAmount = itemView.findViewById(R.id.tvAmount);
            tvSyncStatus = itemView.findViewById(R.id.tvSyncStatus);
            tvTrxId = itemView.findViewById(R.id.tvTrxId);
            btnCopyTrx = itemView.findViewById(R.id.btnCopyTrx);
            tvSender = itemView.findViewById(R.id.tvSender);
            tvRef = itemView.findViewById(R.id.tvRef);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvSimSlot = itemView.findViewById(R.id.tvSimSlot);
        }

        public void bind(final PaymentSms item) {
            String provider = item.getProvider();
            tvProvider.setText(provider);

            // Provider styling
            if ("bKash".equalsIgnoreCase(provider)) {
                tvProvider.setTextColor(ContextCompat.getColor(context, R.color.bkash));
            } else if ("Nagad".equalsIgnoreCase(provider)) {
                tvProvider.setTextColor(ContextCompat.getColor(context, R.color.nagad));
            } else if ("Rocket".equalsIgnoreCase(provider)) {
                tvProvider.setTextColor(ContextCompat.getColor(context, R.color.rocket));
            } else if ("Upay".equalsIgnoreCase(provider)) {
                tvProvider.setTextColor(ContextCompat.getColor(context, R.color.upay));
            } else {
                tvProvider.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            }

            // Amount
            tvAmount.setText("৳ " + item.getAmount());

            // TrxID
            final String trxId = item.getTrxId();
            tvTrxId.setText(trxId.isEmpty() ? "--" : trxId);

            btnCopyTrx.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!trxId.isEmpty()) {
                        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("TrxID", trxId);
                        if (cm != null) {
                            cm.setPrimaryClip(clip);
                            Toast.makeText(context, "Copied: " + trxId, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

            // Sender
            String senderNum = item.getSenderNumber();
            tvSender.setText("From: " + (senderNum.isEmpty() ? "Unknown" : senderNum));

            // Ref
            String ref = item.getRef();
            if (ref != null && !ref.isEmpty()) {
                tvRef.setVisibility(View.VISIBLE);
                tvRef.setText("Ref: " + ref);
            } else {
                tvRef.setVisibility(View.GONE);
            }

            // Sync Status
            switch (item.getSyncStatus()) {
                case PaymentSms.STATUS_SYNCED:
                    tvSyncStatus.setText("SYNCED");
                    tvSyncStatus.setBackgroundResource(R.drawable.bg_badge_synced);
                    tvSyncStatus.setTextColor(ContextCompat.getColor(context, R.color.status_synced));
                    break;
                case PaymentSms.STATUS_FAILED:
                    tvSyncStatus.setText("FAILED");
                    tvSyncStatus.setBackgroundResource(R.drawable.bg_badge_failed);
                    tvSyncStatus.setTextColor(ContextCompat.getColor(context, R.color.status_failed));
                    break;
                case PaymentSms.STATUS_PENDING:
                default:
                    tvSyncStatus.setText("PENDING");
                    tvSyncStatus.setBackgroundResource(R.drawable.bg_badge_pending);
                    tvSyncStatus.setTextColor(ContextCompat.getColor(context, R.color.status_pending));
                    break;
            }

            // Timestamp
            Date date = new Date(item.getTimestamp());
            tvTimestamp.setText(dateFormat.format(date));

            // SIM slot
            tvSimSlot.setText("SIM " + item.getSimSlot());

            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new androidx.appcompat.app.AlertDialog.Builder(context)
                            .setTitle("Payment Options")
                            .setMessage("TrxID: " + item.getTrxId() + "\nRaw: " + item.getRawMessage())
                            .setPositiveButton("Copy Full SMS", (d, w) -> {
                                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("SMS", item.getRawMessage()));
                                    Toast.makeText(context, "Full SMS copied", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNeutralButton("Delete", (d, w) -> {
                                com.deshipay.gateway.db.DatabaseHelper.getInstance(context).deletePayment(item.getId());
                                items.remove(getAdapterPosition());
                                notifyItemRemoved(getAdapterPosition());
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Close", null)
                            .show();
                    return true;
                }
            });
        }
    }
}
