package com.deshipay.gateway.parser;

import android.util.Log;
import com.deshipay.gateway.model.PaymentSms;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PaymentParser {
    private static final String TAG = "PaymentParser";

    // Common Extraction Regex Patterns
    private static final Pattern TRX_PATTERN = Pattern.compile(
            "(?:TrxID|TxnID|TxnId|Trx\\s*ID|Txn\\s*ID|Transaction\\s*ID|Trx|Txn)\\s*[:\\-]?\\s*([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:Tk\\.?|BDT)\\s*([\\d,]+(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENDER_NUMBER_PATTERN = Pattern.compile(
            "(?:from\\s+(?:A\\/C:)?|Sender:\\s*|Uddokta:\\s*)([0-9a-zA-Z*xX+]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FEE_PATTERN = Pattern.compile(
            "Fee\\s*(?:Tk\\.?)?\\s*([\\d,.]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "Balance\\s*[:\\-]?\\s*(?:Tk\\.?)?\\s*([\\d,.]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REF_PATTERN = Pattern.compile(
            "Ref\\s*[:\\-]?\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses incoming SMS. Returns valid PaymentSms or null if non-payment / spam.
     */
    public static PaymentSms parse(String sender, String body, int simSlot, long timestamp) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }

        String cleanBody = body.replaceAll("\\s+", " ").trim();
        String lowerSender = sender != null ? sender.toLowerCase().trim() : "";
        String lowerBody = cleanBody.toLowerCase();

        // Immediate Discards: Outgoing or non-credit events
        if (isNonCreditOrSpam(lowerBody)) {
            Log.d(TAG, "Discarded non-credit / spam SMS: " + cleanBody);
            return null;
        }

        // Determine Provider
        String provider = detectProvider(lowerSender, lowerBody);

        // Extract TrxID
        String trxId = extractTrxId(cleanBody);
        if (trxId == null || trxId.isEmpty()) {
            Log.d(TAG, "No TrxID found in SMS: " + cleanBody);
            return null;
        }

        // Extract Amount
        String amount = extractAmount(cleanBody);
        if (amount == null || amount.isEmpty() || "0".equals(amount)) {
            Log.d(TAG, "No valid amount found in SMS: " + cleanBody);
            return null;
        }

        // Extract Sender Number (customer phone number)
        String senderNumber = extractSenderNumber(cleanBody);
        if (senderNumber == null || senderNumber.isEmpty()) {
            senderNumber = sender != null ? sender.trim() : "Unknown";
        }

        // Extract optional metadata: Fee, Balance, Ref
        String fee = extractFee(cleanBody);
        String balance = extractBalance(cleanBody);
        String ref = extractRef(cleanBody);

        PaymentSms payment = new PaymentSms(
                provider,
                senderNumber,
                amount,
                trxId,
                fee,
                balance,
                ref,
                simSlot,
                cleanBody,
                timestamp > 0 ? timestamp : System.currentTimeMillis()
        );

        Log.i(TAG, "Successfully parsed: " + provider + " ৳" + amount + " [Trx: " + trxId + "] from " + senderNumber);
        return payment;
    }

    private static boolean isNonCreditOrSpam(String lowerBody) {
        // Discard outgoing or non-payment messages
        if (lowerBody.contains("you have sent") ||
            lowerBody.contains("cash out") ||
            lowerBody.contains("recharge successful") ||
            lowerBody.contains("mobile recharge") ||
            lowerBody.contains("security code") ||
            lowerBody.contains("verification code") ||
            lowerBody.contains("otp is") ||
            lowerBody.contains("your pin") ||
            lowerBody.contains("dial *") ||
            lowerBody.contains("account active") ||
            lowerBody.contains("pin change") ||
            lowerBody.contains("balance inquiry")) {

            // Safety check: if it explicitly says received money, do not discard
            if (!lowerBody.contains("you have received") &&
                !lowerBody.contains("money received") &&
                !lowerBody.contains("cash in")) {
                return true;
            }
        }

        // Discard promo / bonus messages without TrxID
        if (lowerBody.contains("bonus") && !lowerBody.contains("trxid") && !lowerBody.contains("txnid")) {
            return true;
        }

        return false;
    }

    private static String detectProvider(String lowerSender, String lowerBody) {
        if (lowerSender.contains("bkash") || lowerSender.contains("16247") || lowerBody.contains("bkash")) {
            return "bKash";
        }
        if (lowerSender.contains("nagad") || lowerSender.contains("16167") || lowerBody.contains("nagad")) {
            return "Nagad";
        }
        if (lowerSender.contains("rocket") || lowerSender.contains("16216") || lowerSender.contains("dbbl") || lowerBody.contains("rocket")) {
            return "Rocket";
        }
        if (lowerSender.contains("upay") || lowerSender.contains("16268") || lowerSender.contains("ucb") || lowerBody.contains("upay")) {
            return "Upay";
        }
        if (lowerSender.contains("cellfin") || lowerSender.contains("ibbl") || lowerBody.contains("cellfin")) {
            return "Cellfin";
        }

        // Infer from text patterns
        if (lowerBody.contains("you have received tk") || lowerBody.contains("cash in tk")) {
            return "bKash";
        }
        if (lowerBody.contains("money received") || lowerBody.contains("cash in received")) {
            return "Nagad";
        }
        if (lowerBody.contains("a/c balance") || lowerBody.contains("received from a/c")) {
            return "Rocket";
        }
        if (lowerBody.contains("has been received from")) {
            return "Upay";
        }

        return "MFS";
    }

    private static String extractTrxId(String text) {
        Matcher m = TRX_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String extractAmount(String text) {
        Matcher m = AMOUNT_PATTERN.matcher(text);
        if (m.find()) {
            return cleanAmount(m.group(1));
        }

        // Fallback for "Amount: 500" without explicit "Tk"
        Pattern fallbackAmt = Pattern.compile("Amount:\\s*([\\d,]+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);
        Matcher m2 = fallbackAmt.matcher(text);
        if (m2.find()) {
            return cleanAmount(m2.group(1));
        }

        return null;
    }

    private static String extractSenderNumber(String text) {
        Matcher m = SENDER_NUMBER_PATTERN.matcher(text);
        if (m.find()) {
            String num = m.group(1).trim();
            // strip trailing period if captured
            if (num.endsWith(".")) {
                num = num.substring(0, num.length() - 1);
            }
            return num;
        }
        return null;
    }

    private static String extractFee(String text) {
        Matcher m = FEE_PATTERN.matcher(text);
        if (m.find()) {
            return cleanAmount(m.group(1));
        }
        return "0";
    }

    private static String extractBalance(String text) {
        Matcher m = BALANCE_PATTERN.matcher(text);
        if (m.find()) {
            return cleanAmount(m.group(1));
        }
        return "0";
    }

    private static String extractRef(String text) {
        Matcher m = REF_PATTERN.matcher(text);
        if (m.find()) {
            String ref = m.group(1).trim();
            if (ref.endsWith(".")) {
                ref = ref.substring(0, ref.length() - 1);
            }
            return ref;
        }
        return "";
    }

    private static String cleanAmount(String raw) {
        if (raw == null) return "0";
        String cleaned = raw.replace(",", "").replace("..", ".").trim();
        // remove trailing dot if any
        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "0" : cleaned;
    }
}
