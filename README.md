# MFS SMS Gateway — Android Companion App

[![Platform](https://img.shields.io/badge/Platform-Android_7.0+-3DDC84.svg?logo=android&logoColor=white)](https://www.android.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Architecture](https://img.shields.io/badge/Architecture-Offline--First_SQLite-orange.svg)](#architecture)
[![Security](https://img.shields.io/badge/Security-Bearer_Auth_%7C_HMAC-red.svg)](#security--data-privacy)

A high-performance, resilient, and production-grade native Android background gateway that bridges cellular Mobile Financial Service (MFS) SMS notifications (**bKash, Nagad, Rocket, Upay**) with automated webhooks and payment gateways (**PipraPay, custom Laravel/Node.js/Python backends, n8n automation pipelines, or self-hosted billing engines**).

---

## 🏗️ Architecture & How It Works

This companion application runs directly on an Android device equipped with merchant or personal MFS SIM cards. It eliminates the need for brittle notification scrapers or manual transaction entry.

```mermaid
flowchart TD
    A["Customer Completes MFS Payment\n(bKash / Nagad / Rocket / Upay)"] --> B["Telco Cellular Network"]
    B --> C["Merchant Android Device\n(Physical SIM)"]
    
    subgraph Android App ["Native Android SMS Gateway Engine"]
        C --> D["Telephony BroadcastReceiver\n(android.provider.Telephony.SMS_RECEIVED)"]
        D -->|Fallback Mechanism| E["ContentObserver\n(content://sms/inbox)"]
        D & E --> F["Regex Parsing & Validation Engine\n(PaymentParser.java)"]
        F --> G{"Is Valid MFS\nCredit Transaction?"}
        G -->|Spam / OTP / Outgoing / Promo| H["Drop & Discard"]
        G -->|Verified Payment| I["Offline SQLite Buffer\nStatus: PENDING"]
        I --> J["Network & Battery-Aware\nSync Daemon (SyncManager.java)"]
    the end

    J -->|HTTPS POST + Bearer Auth| K["Merchant Webhook / Payment Gateway\n(PipraPay, Laravel, Node.js, n8n)"]
    K --> L{"Gateway Response\nHTTP 200 OK?"}
    L -->|Success| M["Update Local DB\nStatus: SYNCED"]
    L -->|Network Down / 5xx Error| N["Exponential Backoff\nStatus: RETRY"]
    N --> J
```

---

## ⚡ Key Capabilities

### 1. Zero-Dependency Direct Telephony Capture
* Captures SMS directly at the Android Telephony broadcast layer (`SMS_RECEIVED`).
* Does **not** rely on fragile Android notification listener services that break when notifications are swiped away, silenced, or batched by the OS.
* Secondary background fallback via `ContentObserver` ensures 100% capture rate even across restrictive vendor firmware (MIUI, ColorOS, OneUI).

### 2. High-Precision Regex Parsing Engine
* Automatically identifies and parses transaction payloads from:
  * **bKash** (Sender: `bKash`, `16247`)
  * **Nagad** (Sender: `Nagad`, `16167`)
  * **Rocket** (Sender: `16216`, `Rocket`)
  * **Upay** (Sender: `UPAY`, `16268`)
* Accurately extracts structured parameters:
  * `trx_id` (Unique transaction alphanumeric identifier)
  * `amount` (Monetary value in BDT)
  * `sender_number` (Customer mobile number)
  * `timestamp` (Exact transaction date and time)
  * `balance` (Post-transaction account balance, optional)
* **Anti-Spoofing & Noise Filter**: Automatically filters out and ignores outgoing cash-outs, OTPs, password reset alerts, referral promotions, and unauthorized spoofed sender IDs.

### 3. Offline-First Resilience (Zero Lost Payments)
* Every parsed transaction is immediately committed to an embedded **SQLite Database** before any network request is attempted.
* If the device loses internet connection, enters airplane mode, or your backend server experiences maintenance, transactions remain safe in `PENDING` state.
* The synchronization daemon monitors network state via `ConnectivityManager` and flushes pending queues the moment connectivity is restored.

### 4. 24/7 Persistent Daemon & Auto-Start
* Runs as an Android Foreground Service with a persistent notification (`START_STICKY`) to guard against aggressive OEM low-memory killers (OOM).
* Registers a `BootReceiver` (`BOOT_COMPLETED`) to automatically launch the background synchronization service on phone restart.

### 5. Built-In Offline Simulator
* Developers and merchants can test complete end-to-end webhook integration without spending real money.
* Built-in simulation dialog generates mock bKash and Nagad payment SMS formats to test parsing, database insertion, and webhook delivery.

---

## 🔌 Webhook & Integration Specification

When a valid payment is captured, the app sends an `HTTPS POST` request to your configured gateway endpoint.

### Request Headers
```http
POST /api/v1/sms/webhook HTTP/1.1
Host: your-gateway.example.com
Authorization: Bearer <YOUR_CONFIGURED_DEVICE_TOKEN>
Content-Type: application/json
User-Agent: MFS-SMS-Companion/1.1
```

### JSON Request Payload
```json
{
  "provider": "bKash",
  "trx_id": "BLA38DK98Z",
  "amount": 1250.00,
  "sender_number": "017XXXXXXXX",
  "timestamp": "2026-10-31 07:00:00",
  "raw_sms": "You have received Tk 1,250.00 from 017XXXXXXXX. TrxID BLA38DK98Z at 31/10/2026 07:00. Fee Tk 0.00. Balance Tk 15,200.00.",
  "sim_slot": 1,
  "device_name": "Samsung-Galaxy-A12"
}
```

### Expected Response
Your server must return an HTTP status code in the `2xx` range (preferably `200 OK`) with JSON:
```json
{
  "success": true,
  "message": "Payment verified and credited."
}
```

---

## 🛠️ Configuration & Setup

All endpoints and credentials are **100% dynamic and configurable via the app settings UI**. No domains, API keys, or tokens are hardcoded.

1. Install the APK on your dedicated Android phone.
2. Grant runtime permissions:
   * **Receive SMS** (`android.permission.RECEIVE_SMS`)
   * **Read SMS** (`android.permission.READ_SMS`)
3. Open the **Settings** dialog (gear icon) in the app:
   * **Gateway Server URL**: Enter your merchant server URL (e.g., `https://gateway.yourdomain.com`).
   * **Device Token**: Enter your merchant API secret or unique device token for webhook authentication.
4. Tap **Save & Test Connection**.
5. Once paired, the status indicator turns green (`Online • Gateway Connected`).

---

## 🛡️ Security & Privacy Best Practices

* **Zero Hardcoded Secrets**: The application codebase is completely agnostic. Never commit production server URLs, private device tokens, or live merchant credentials.
* **HTTPS Only**: Always deploy your backend webhook server with valid SSL/TLS certificates (`https://`).
* **Token Authentication**: Protect your server endpoint by validating the `Authorization: Bearer <token>` header on every incoming request.
* **Idempotency**: Implement database unique constraints on `trx_id` in your backend to ensure duplicate webhook deliveries are handled safely.

---

## 📦 Building from Source

### Prerequisites
* Android Studio Iguana / Ladybug or newer
* Android SDK 34 (Android 14)
* Java 17+ / Gradle 8.x

### Build Release APK
```bash
git clone https://github.com/Apon-The-Bot/piprapay-sms-companion-app.git
cd piprapay-sms-companion-app

# Build debug APK
./gradlew assembleDebug

# Build optimized release APK
./gradlew assembleRelease
```
The output APK will be generated at `app/build/outputs/apk/release/app-release-unsigned.apk`.

---

## 🤝 Supported Integrations

* **PipraPay Payment Gateway** (Native compatibility)
* **Custom Gateways** (Node.js, Express, Python FastAPI, PHP Laravel)
* **Automation Engines** (n8n Webhook Trigger, Zapier, Make)
* **E-Commerce & Billing** (WooCommerce webhook receiver, custom SaaS checkout)

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
Developed with focus on reliability, security, and developer freedom.
