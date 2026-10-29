# Vendor Webhook Integration & Decoupled Alerting Pipeline
## Architecture Specification & Step-by-Step Implementation Plan

- **Date:** 2026-10-22
- **Status:** Done (verified against the code on 2026-10-29; full suite 320/0)
- **Author:** adarsh.v.h (commit 46c64da)
- **Implemented in:** `common/db/VendorWebhookRepository`, `controlplane/service/VendorWebhookService`, `controlplane/controller/VendorWebhookController`, `dataplane/service/AlertDispatcher`, `frontend/src/pages/NotificationsPage.jsx`
- **Note:** written before `docs/plans/TEMPLATE.md` existed; moved here from the repo root.

> **Note for Implementing AI / Engineers:**  
> This specification defines the complete end-to-end architecture, database schema, service contracts, REST endpoints, UI components, and testing guidelines for tenant-scoped vendor webhooks in ULPF.  
> **Strict Instruction:** Follow the phases and rules sequentially. If any requirement seems ambiguous or if you consider altering database relationships or thread boundaries, **STOP and notify the user: "Discuss with your team about this before proceeding."** Do NOT guess or make ad-hoc architectural compromises.

---

## 1. Executive Summary & Objective

In ULPF, log schema drift and table ingestion failures generate alerts. Currently:
1. In-app notifications are saved to SQLite for vendor owners and platform admins.
2. Webhooks are delivered **only** to a single global endpoint configured in `.env` (`ULPF_ALERT_WEBHOOK_URL`).

### The Goal
1. **Decouple the Webhook Sender**: Transform the internal HTTP client into a pure, generic utility that accepts `(url, secret, payload)` without being bound to `.env`.
2. **Multi-Tenant Vendor Webhooks**: Allow each registered Vendor organization to configure **one active webhook URL + secret** in a dedicated SQLite table (`vendor_webhooks`).
3. **Dual Dispatching**:
   - System administrators continue to receive alerts via the global `.env` webhook (if set).
   - The affected vendor receives alerts at their registered vendor webhook (if set).
4. **Resilience & Health Visibility**:
   - Log delivery attempts into `webhook_delivery_logs` (recording HTTP status codes, latency, and errors).
   - If a vendor's webhook fails, mark the webhook as `FAILING` and dispatch an in-app notification to the vendor's user inbox alerting them that their webhook endpoint is broken.
   - Provide a **"Send Test Ping"** endpoint so vendors can verify connectivity immediately from the UI.
5. **Frontend Integration**:
   - Add a clean, Warm Kinetic-styled Webhook Integration management card to the top of `/notifications` for `VENDOR` users.

---

## 2. Strict Architectural Guardrails (Must Follow)

1. **DO NOT touch the `notifications` table for webhook delivery tracking**:
   - `notifications` is exclusively the **human user inbox** (`user_id`, `title`, `message`, `read`).
   - Do NOT add `sent` or `is_webhook_sent` flags to `notifications`.
   - Webhook delivery history belongs strictly in `webhook_delivery_logs`.
2. **DO NOT execute synchronous HTTP calls on ingestion threads**:
   - Webhook dispatch MUST remain completely asynchronous via a bounded thread pool (`webhookExecutor` with `ThreadPoolExecutor.DiscardPolicy`).
   - HTTP timeouts must remain strictly clamped to **3 seconds** with at most **1 retry**.
3. **DO NOT expose raw webhook secret keys via GET endpoints**:
   - Secrets must be stored securely and masked on read (e.g. `whsec_••••••••1234`).
4. **Role Enforcement**:
   - Only users with role `VENDOR` (or platform `ADMIN`) can manage vendor webhooks.
   - Regular `USER` accounts must be rejected with HTTP `403 FORBIDDEN`.
5. **Escalation Trigger**:
   - If asked to support multiple webhooks per vendor, custom payload templates, or webhook event filtering:  
     👉 **STOP and prompt:** *"Discuss with your team about this before proceeding."*

---

## 3. Database Schema Changes (`sqlite/schema.sql`)

Append two new tables to `core-engine/src/main/resources/sqlite/schema.sql` and `schema.sql`:

```sql
-- 12. Vendor Webhook Configurations (1 active endpoint per vendor organization)
CREATE TABLE IF NOT EXISTS vendor_webhooks (
    webhook_id          TEXT PRIMARY KEY,
    vendor_id           TEXT NOT NULL UNIQUE,
    webhook_url         TEXT NOT NULL,
    secret_key          TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT 1,
    status              TEXT NOT NULL DEFAULT 'UNTESTED' CHECK (status IN ('UNTESTED', 'HEALTHY', 'FAILING')),
    last_status_code    INTEGER,
    last_attempt_at     TIMESTAMP,
    consecutive_fails   INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vendor_id) REFERENCES vendors(vendor_id)
);

CREATE INDEX IF NOT EXISTS idx_vendor_webhooks_vendor ON vendor_webhooks(vendor_id);

-- 13. Webhook Delivery Logs (Audit trail of recent deliveries & test pings)
CREATE TABLE IF NOT EXISTS webhook_delivery_logs (
    delivery_id         TEXT PRIMARY KEY,
    vendor_id           TEXT,               -- NULL for global admin .env webhook
    target_url          TEXT NOT NULL,
    alert_type          TEXT NOT NULL,
    status_code         INTEGER,
    success             BOOLEAN NOT NULL,
    error_message       TEXT,
    latency_ms          INTEGER,
    attempted_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_logs_vendor_date ON webhook_delivery_logs(vendor_id, attempted_at);
```

---

## 4. Phase-by-Step Implementation Guide

### Phase 1: Database Entities & Repository Layer
**Package:** `com.ulpf.common.db`

1. **`VendorWebhookRepository.java`**:
   - Model Records:
     ```java
     public record VendorWebhookRecord(
         String webhookId,
         String vendorId,
         String webhookUrl,
         String secretKey,
         boolean isActive,
         String status,           // UNTESTED, HEALTHY, FAILING
         Integer lastStatusCode,
         LocalDateTime lastAttemptAt,
         int consecutiveFails,
         LocalDateTime createdAt,
         LocalDateTime updatedAt
     ) {}

     public record WebhookDeliveryLogRecord(
         String deliveryId,
         String vendorId,
         String targetUrl,
         String alertType,
         Integer statusCode,
         boolean success,
         String errorMessage,
         Integer latencyMs,
         LocalDateTime attemptedAt
     ) {}
     ```
   - Repository Methods:
     - `Optional<VendorWebhookRecord> findByVendorId(String vendorId)`
     - `VendorWebhookRecord saveOrUpdate(VendorWebhookRecord webhook)`
     - `void updateDeliveryStatus(String vendorId, boolean success, int statusCode, int consecutiveFails)`
     - `void deleteByVendorId(String vendorId)`
     - `void logDelivery(WebhookDeliveryLogRecord log)`
     - `List<WebhookDeliveryLogRecord> findRecentLogsByVendorId(String vendorId, int limit)`

---

### Phase 2: Decouple the Webhook Dispatcher
**File:** `core-engine/src/main/java/com/ulpf/dataplane/service/AlertDispatcher.java`

1. **Create the Decoupled Transmission Method**:
   ```java
   public record DeliveryResult(boolean success, int statusCode, String errorMessage, long latencyMs) {}

   /**
    * Pure decoupled method: sends an alert to ANY valid URL with ANY secret key,
    * completely independent of .env.
    */
   public CompletableFuture<DeliveryResult> sendWebhook(
           URI targetUri,
           String secret,
           AlertPayload payload,
           String vendorId) {

       if (targetUri == null || payload == null) {
           return CompletableFuture.completedFuture(new DeliveryResult(false, 0, "Null URI or payload", 0));
       }

       return CompletableFuture.supplyAsync(() -> {
           long start = System.currentTimeMillis();
           try {
               String jsonBody = objectMapper.writeValueAsString(payload);
               HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                       .uri(targetUri)
                       .header("Content-Type", "application/json")
                       .timeout(Duration.ofSeconds(3))
                       .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

               if (secret != null && !secret.isBlank()) {
                   long ts = Instant.now().getEpochSecond();
                   String signature = computeHmacSha256(ts + "." + jsonBody, secret);
                   if (signature != null) {
                       reqBuilder.header("X-ULPF-Timestamp", String.valueOf(ts));
                       reqBuilder.header("X-ULPF-Signature", "sha256=" + signature);
                   }
               }

               HttpRequest request = reqBuilder.build();
               int statusCode = executeWithStatusCode(request);
               long latency = System.currentTimeMillis() - start;
               boolean success = (statusCode >= 200 && statusCode < 300);

               // Record delivery log in SQLite
               logDeliveryRecord(vendorId, targetUri.toString(), payload.alertType(), statusCode, success, null, (int) latency);

               // Handle failure & notify vendor inbox if vendor webhook failed
               handleVendorDeliveryOutcome(vendorId, targetUri.toString(), success, statusCode);

               log.info("[WEBHOOK] Dispatched {} to {} -> Status {} ({}ms)", payload.alertType(), targetUri.getHost(), statusCode, latency);
               return new DeliveryResult(success, statusCode, null, latency);

           } catch (Exception e) {
               long latency = System.currentTimeMillis() - start;
               logDeliveryRecord(vendorId, targetUri.toString(), payload.alertType(), 0, false, e.getMessage(), (int) latency);
               handleVendorDeliveryOutcome(vendorId, targetUri.toString(), false, 0);

               log.warn("[WEBHOOK ERROR] Failed to deliver to {}: {}", targetUri.getHost(), e.getMessage());
               return new DeliveryResult(false, 0, e.getMessage(), latency);
           }
       }, webhookExecutor);
   }
   ```

2. **Handle Broken Webhook Alert (In-App Inbox Fallback)**:
   - When `handleVendorDeliveryOutcome(vendorId, url, success, statusCode)` detects `success == false`:
     - Increment `consecutive_fails`.
     - Update status to `FAILING`.
     - If `consecutive_fails == 1` or `consecutive_fails % 5 == 0`:
       - Resolve vendor owner user ID.
       - Post in-app alert via `notificationService.createNotification(ownerUserId, "[ALERT] Webhook Endpoint Failing", "Your webhook to " + url + " returned HTTP " + statusCode + ". Please check your endpoint settings.")`.
   - When `success == true`:
     - Reset `consecutive_fails = 0`.
     - Update status to `HEALTHY`.

3. **Multi-Tenant Dispatch in `dispatchAlert`**:
   ```java
   public void dispatchAlert(AlertPayload payload, ...) {
       // 1. Dispatch In-App SQLite notifications to vendor and admins (existing logic)
       ...

       // 2. Dispatch Global Admin Webhook (from .env)
       if (this.validatedWebhookUri != null) {
           sendWebhook(this.validatedWebhookUri, this.webhookSecret, payload, null);
       }

       // 3. Dispatch Specific Vendor Webhook (from SQLite vendor_webhooks)
       if (payload.vendorId() != null && vendorWebhookRepository != null) {
           vendorWebhookRepository.findByVendorId(payload.vendorId())
               .filter(VendorWebhookRecord::isActive)
               .ifPresent(vw -> {
                   try {
                       URI uri = URI.create(vw.webhookUrl());
                       sendWebhook(uri, vw.secretKey(), payload, vw.vendorId());
                   } catch (Exception e) {
                       log.warn("Invalid vendor webhook URI for vendor {}: {}", vw.vendorId(), e.getMessage());
                   }
               });
       }
   }
   ```

---

### Phase 3: Vendor Webhook Service Layer
**File:** `core-engine/src/main/java/com/ulpf/controlplane/service/VendorWebhookService.java`

- **Methods**:
  1. `VendorWebhookResponse getWebhook(String userId)`:
     - Looks up vendor by `ownerUserId = userId`.
     - Returns webhook URL, masked secret (`whsec_••••1234`), health status, last attempt, and recent 5 delivery logs.
  2. `VendorWebhookResponse saveOrUpdateWebhook(String userId, WebhookConfigRequest request)`:
     - Validates URL (must start with `http://` or `https://` and have a valid host).
     - Persists record with status `UNTESTED`.
  3. `void deleteWebhook(String userId)`:
     - Removes webhook entry for vendor.
  4. `DeliveryResult sendTestPing(String userId)`:
     - Constructs a mock `AlertPayload` (`alertType = "TEST_PING"`, `message = "Test alert from ULPF webhook integration"`).
     - Executes `alertDispatcher.sendWebhook(...)`.
     - Returns execution status, response code, and latency to UI.

---

### Phase 4: Vendor REST API Endpoints
**File:** `core-engine/src/main/java/com/ulpf/controlplane/controller/VendorWebhookController.java`

- **Base Path:** `/v1/vendor/webhook`
- **Security:** `@PreAuthorize("hasRole('VENDOR') or hasRole('ADMIN')")`

| Method | Endpoint | Description | Response Status |
| :--- | :--- | :--- | :--- |
| `GET` | `/v1/vendor/webhook` | Get current vendor webhook config & recent logs | `200 OK` |
| `PUT` | `/v1/vendor/webhook` | Register or update webhook URL and secret | `200 OK` / `400 BAD REQUEST` |
| `DELETE`| `/v1/vendor/webhook` | Remove webhook configuration | `204 NO CONTENT` |
| `POST` | `/v1/vendor/webhook/test`| Send immediate test ping to configured webhook | `200 OK` |

#### Payload Examples:
- **`PUT /v1/vendor/webhook` Request Body**:
  ```json
  {
    "webhookUrl": "https://hooks.slack.com/services/T00/B00/XXXXX",
    "secretKey": "my-shared-hmac-secret"
  }
  ```
- **`GET /v1/vendor/webhook` Response Body**:
  ```json
  {
    "configured": true,
    "webhookUrl": "https://hooks.slack.com/services/T00/B00/XXXXX",
    "maskedSecret": "my-s••••cret",
    "status": "HEALTHY",
    "lastStatusCode": 200,
    "lastAttemptAt": "2026-10-22T00:45:00",
    "consecutiveFails": 0,
    "recentLogs": [
      {
        "alertType": "SCHEMA_DRIFT",
        "statusCode": 200,
        "success": true,
        "latencyMs": 182,
        "attemptedAt": "2026-10-22T00:45:00"
      }
    ]
  }
  ```
- **`POST /v1/vendor/webhook/test` Response Body**:
  ```json
  {
    "success": true,
    "statusCode": 200,
    "latencyMs": 145,
    "errorMessage": null
  }
  ```

---

### Phase 5: Frontend UI in `NotificationsPage.jsx`
**File:** `frontend/src/pages/NotificationsPage.jsx`

For users with `role === 'VENDOR'`, display a dedicated **Webhook Alert Integration** card positioned directly above the notification feed.

#### Design Specifications (Warm Kinetic Design System):
1. **Card Container**:
   - `bg-white rounded-2xl border border-slate-200/80 shadow-sm p-6 mb-8`
2. **Status Header**:
   - Title: `⚡ Out-of-Band Webhook Integration (Slack, Discord, PagerDuty)`
   - Health Badge:
     - 🟢 `HEALTHY (200 OK)`: `bg-emerald-50 text-emerald-700 border-emerald-200`
     - 🔴 `FAILING`: `bg-rose-50 text-rose-700 border-rose-200`
     - ⚪ `UNTESTED`: `bg-slate-100 text-slate-600 border-slate-200`
3. **Configuration Form**:
   - `Webhook URL`: Input field with placeholder `https://hooks.slack.com/services/...`
   - `Secret Key`: Masked input field for optional HMAC-SHA256 signature verification.
   - Buttons:
     - `Save Configuration` (Primary Teal button)
     - `Send Test Ping` (Secondary Slate outline button with loading spinner)
     - `Remove Webhook` (Muted Rose text button)
4. **Recent Deliveries Accordion**:
   - Displays a clean history table with the last 5 attempts (`Alert Type`, `Status Code`, `Latency`, `Time`).

---

## 5. Testing & Verification Plan

1. **Unit Tests (`VendorWebhookServiceTest.java`)**:
   - `testSaveAndMaskSecret()`: Ensures secrets are properly masked when retrieved.
   - `testTestPingSuccessAndFailure()`: Mocks `HttpClient` to verify 200 OK and 500 ERROR handling.
   - `testUserRoleForbidden()`: Verifies that a non-vendor user cannot access or configure vendor webhooks.
2. **Integration Tests (`AlertDispatcherTest.java`)**:
   - `testDualDispatchToAdminAndVendor()`: Confirms that an alert for Vendor A is dispatched to Vendor A's webhook AND the admin `.env` webhook, but NOT to Vendor B.
   - `testBrokenWebhookCreatesInAppAlert()`: Confirms that when a webhook fails, an in-app alert is generated in `notifications` for the vendor owner.
3. **Full Suite Regression**:
   - Run `mvn clean test` to ensure all 300+ existing tests continue to pass with 0 failures.

---

## 6. Definition of Done Checklist

- [x] SQLite tables `vendor_webhooks` and `webhook_delivery_logs` defined in `schema.sql`.
- [x] `AlertDispatcher.sendWebhook(...)` decoupled to accept dynamic URI, secret, and payload.
- [x] Broken webhook detection updates `status = 'FAILING'` and alerts vendor inbox.
- [x] `VendorWebhookController` endpoints implemented with RBAC (`VENDOR` or `ADMIN`).
- [x] Vendor Webhook card implemented in `NotificationsPage.jsx` with test ping and delivery history.
- [x] Full Maven build (`mvn clean test`) succeeds with 100% pass rate.
