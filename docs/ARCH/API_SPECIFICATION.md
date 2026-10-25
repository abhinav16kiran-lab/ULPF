# Universal Log Processing Framework (ULPF)
## API Specification — Production Reference

---

## 1. Core Principles

- **Spring Boot API Boundary**: Spring Boot mediates all HTTP traffic between clients, control plane databases (SQLite), and high-throughput data plane databases (ClickHouse).
- **Zero Ingestion Overhead**: The primary runtime ingestion endpoint (`POST /v1/events`) uses micro-batching and non-blocking ClickHouse async inserts.
- **Isolated Control & Data Planes**: Vendor onboarding and administration occur out-of-band on the control plane without degrading ingestion performance.
- **Role-Based Access Control (RBAC)**: Enforces `ADMIN`, `VENDOR`, and `USER` roles via JWT Bearer authentication headers.
- **Read-Only Analytics Mediation**: Browsers never execute direct queries against ClickHouse; all analytical queries are validated, sanitized, and authorized by Spring Boot.

---

## 2. Endpoint Summary Matrix

| Endpoint | Method | Role | Description |
| :--- | :--- | :--- | :--- |
| `/v1/signup` | `POST` | Public | Register a new user account |
| `/v1/login` | `POST` | Public | Authenticate user and issue JWT Bearer token |
| `/v1/onboard/{username}` | `POST` | `VENDOR`/`ADMIN` | Submit sample log file and schema for AI field mapping |
| `/v1/onboard/update` | `POST` | `VENDOR`/`ADMIN` | Update an existing log source mapping schema |
| `/v1/admin/onboard` | `GET` | `ADMIN` | List all pending vendor onboarding requests |
| `/v1/admin/onboard/{id}`| `PUT` | `ADMIN` | Approve or reject onboarding request candidate mapping |
| `/v1/admin/clickhouse/schemas` | `GET` | `ADMIN` | Inspect active ClickHouse database and table schemas |
| `/v1/events` | `POST` | `VENDOR`/`ADMIN` | Primary plug-and-play high-throughput log event ingestion |
| `/v1/notifications` | `GET` | Authenticated | Fetch notifications and issued API keys for logged-in user |
| `/v1/notifications/{id}/read` | `PUT` | Authenticated | Mark a notification item as read |
| `/v1/analytics` | `GET` | `ADMIN` | Read-only ClickHouse metrics aggregation query execution |
| `/v1/analytics/tables` | `GET` | `ADMIN` | Fetch list of accessible ClickHouse table names |
| `/v1/analytics/tables/{table}/columns` | `GET` | `ADMIN` | Retrieve schema column metadata for specific ClickHouse table |
| `/v1/analytics/schemas` | `GET` | `ADMIN` | Get ClickHouse database catalog tables and dynamic columns |
| `/v1/analytics/search` | `GET` | `ADMIN` | Full-text substring & regex raw log search with Bloom filter |
| `/v1/analytics/timeseries` | `GET` | `ADMIN` | Time-series histogram throughput & error spike aggregation |
| `/v1/analytics/import/file` | `POST` | `ADMIN` | Bulk upload `.json`, `.gz`, `.log`, or `.csv` files into ClickHouse |
| `/v1/analytics/export/parquet` | `GET` | `ADMIN` | Export ClickHouse logs into Snappy-compressed Apache Parquet |
| `/v1/integrity/blocks` | `GET` | Authenticated | List all cryptographic batch integrity blocks |
| `/v1/integrity/verify/{blockId}` | `POST` | Authenticated | Re-verify cryptographic Merkle root hash for specific block |
| `/v1/integrity/verify-all` | `POST` | `ADMIN` | Execute live forensic Merkle tree cryptographic bulk audit |
| `/v1/admin/sources/{sourceId}/replay` | `POST` | `ADMIN` | Trigger asynchronous historical replay & backfill from raw events |
| `/v1/admin/sources/{sourceId}/replay/status` | `GET` | `ADMIN` | Check real-time progress and status of source replay job |
| `/v1/admin/sources/{sourceId}/replay` | `DELETE` | `ADMIN` | Request cancellation of currently running replay job |
| `/v1/vendor/webhook` | `GET` | `VENDOR` | Fetch tenant webhook configuration and delivery health status |
| `/v1/vendor/webhook` | `POST` | `VENDOR` | Register or update tenant webhook URL and optional HMAC secret |
| `/v1/vendor/webhook/test` | `POST` | `VENDOR` | Dispatch HMAC-signed test ping (`TEST_PING`) to configured webhook |

---

## 3. Detailed Endpoint Specifications

### 3.1 Authentication

Public endpoints bypass the `JwtFilter` via `shouldNotFilter()`, ensuring unauthenticated traffic is never rejected prematurely by stale bearer tokens.

#### `POST /v1/signup`
Registers a new system user.

* **Request Body**:
  ```json
  {
    "name": "CrowdStrike Admin",
    "username": "crowdstrike_admin",
    "password": "SecurePassword123!",
    "confirmPassword": "SecurePassword123!"
  }
  ```
* **Response (200 OK)**:
  ```json
  "User registered successfully"
  ```

#### `POST /v1/login`
Authenticates credentials and returns a signed JWT token.

* **Request Body**:
  ```json
  {
    "username": "crowdstrike_admin",
    "password": "SecurePassword123!",
    "role": "ADMIN"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "username": "crowdstrike_admin",
    "role": "ADMIN"
  }
  ```

---

### 3.2 Vendor Log Source Onboarding

#### `POST /v1/onboard/{username}`
Uploads sample logs and schema files for AI field mapping discovery.

* **Content-Type**: `multipart/form-data`
* **Parameters**:
  - `vendorName` (string, required): Organization name (e.g. `CrowdStrike`)
  - `sourceName` (string, required): Log source identifier (e.g. `Falcon_EDR`)
  - `sourceType` (string, required): `SYSLOG`, `JSON`, `CEF`, `LEEF`, `CSV`
  - `sampleLogFile` (file, required): Raw log sample payload (`.log`, `.json`, `.txt`, `.csv`)
  - `schemaFile` (file, optional): Vendor schema specification file
* **Response (201 Created)**:
  ```json
  {
    "requestId": "req_89a12c4b",
    "status": "SUBMITTED",
    "message": "Onboarding request submitted successfully. AI candidate mapping generated."
  }
  ```

---

### 3.3 Admin Governance

#### `GET /v1/admin/onboard`
Lists all pending and historical vendor onboarding requests.

* **Headers**: `Authorization: Bearer <token>`
* **Response (200 OK)**:
  ```json
  {
    "requests": [
      {
        "requestId": "req_89a12c4b",
        "vendorName": "CrowdStrike",
        "sourceName": "Falcon_EDR",
        "sourceType": "SYSLOG",
        "status": "SUBMITTED",
        "mappingJson": "{\"src_ip\":\"source_ip\",\"timstamp\":\"timestamp\"}",
        "createdAt": "2026-09-12T10:00:00"
      }
    ]
  }
  ```

#### `PUT /v1/admin/onboard/{requestId}`
Approves or rejects an AI-proposed schema mapping candidate.

* **Request Body**:
  ```json
  {
    "decision": "APPROVED"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "requestId": "req_89a12c4b",
    "status": "APPROVED",
    "apiKey": "ulpf_live_9f82a1b73e4c5d6e7f8a9b0c1d2e3f4a",
    "message": "Onboarding request approved. Dynamic ClickHouse table provisioned."
  }
  ```

---

### 3.4 Runtime Ingestion (Data Plane)

#### `POST /v1/events`
High-throughput ingestion endpoint for log streaming.

* **Headers**:
  - `X-API-Key: ulpf_live_9f82a1b73e4c5d6e7f8a9b0c1d2e3f4a`
  - `Content-Type: application/json` (or plain text for Syslog/CEF)
* **Request Body**:
  ```json
  {
    "timestamp": "2026-09-12T10:15:00Z",
    "src_ip": "192.168.1.50",
    "dst_ip": "10.0.0.1",
    "action": "ALLOW",
    "status_code": 200
  }
  ```
* **Response (202 Accepted)**:
  ```json
  {
    "eventId": "evt_019284a1-89bc-4a12",
    "status": "ACCEPTED",
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
  }
  ```

---

### 3.5 ClickHouse Analytics & Observability

#### `GET /v1/analytics`
Executes read-only aggregation queries against ClickHouse log tables.

* **Query Parameters**:
  - `table` (required): Target table (e.g. `canonical_events`, `events_crowdstrike_falcon_edr`)
  - `column` (required): Target column name (e.g. `source_ip`, `status_code`)
  - `aggregation` (required): `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
* **Response (200 OK)**:
  ```json
  {
    "table": "canonical_events",
    "column": "source_ip",
    "aggregation": "COUNT",
    "result": 14285901,
    "executionTimeMs": 8
  }
  ```

#### `GET /v1/analytics/tables`
Returns a list of accessible ClickHouse table names across the system.

* **Response (200 OK)**:
  ```json
  [
    "canonical_events",
    "raw_events",
    "events_crowdstrike_falcon_edr",
    "events_paloalto_panos_firewall"
  ]
  ```

#### `GET /v1/analytics/tables/{table}/columns`
Retrieves column metadata (`name`, `type`) for a specific ClickHouse table for dynamic UI query builder population.

* **Path Parameters**: `table` (required, e.g. `events_crowdstrike_falcon_edr`)
* **Response (200 OK)**:
  ```json
  {
    "table": "events_crowdstrike_falcon_edr",
    "columns": [
      {"name": "event_id", "type": "String"},
      {"name": "source_id", "type": "String"},
      {"name": "timestamp", "type": "DateTime64(3)"},
      {"name": "vendor_id", "type": "String"},
      {"name": "raw_unmapped", "type": "String"}
    ]
  }
  ```

#### `GET /v1/analytics/schemas`
Returns live ClickHouse database tables and column definitions.

* **Response (200 OK)**:
  ```json
  {
    "tables": [
      "canonical_events",
      "raw_events",
      "events_crowdstrike_falcon_edr"
    ],
    "schemas": {
      "canonical_events": [
        {"name": "event_id", "type": "String"},
        {"name": "source_ip", "type": "String"},
        {"name": "timestamp", "type": "DateTime64(3)"}
      ]
    }
  }
  ```

#### `GET /v1/analytics/export/parquet`
Exports log dataset chunks as Snappy-compressed binary Apache Parquet files.

* **Query Parameters**: `table`, `vendorId`, `sourceId`, `from`, `to`, `limit`
* **Response Headers**: `Content-Type: application/vnd.apache.parquet`

---

### 3.6 Forensic Integrity & Audit

#### `GET /v1/integrity/blocks`
Lists all batch integrity blocks, optionally filtered by `sourceId`.

#### `POST /v1/integrity/verify/{blockId}`
Re-verifies a single integrity block against ClickHouse and returns full deterministic traceability data.

* **Response (200 OK)**:
  ```json
  {
    "blockId": 12,
    "sourceId": "src_fw_1",
    "firstEventId": "evt_001",
    "lastEventId": "evt_050",
    "databaseTable": "ulpf_raw.raw_events",
    "status": "VALID",
    "storedMerkleRoot": "abc123def...",
    "computedMerkleRoot": "abc123def...",
    "eventCount": 50,
    "isTampered": false,
    "message": "Cryptographic Merkle Proof verified successfully! Log payloads match 100%."
  }
  ```

#### `POST /v1/integrity/verify-all`
Executes full-scale Merkle tree cryptographic audit across ClickHouse raw log batches using `.parallelStream()`.

* **Response (200 OK)**:
  ```json
  {
    "totalBlocksChecked": 134,
    "validCount": 134,
    "tamperedCount": 0,
    "tamperedBlocks": []
  }
  ```

---

### 3.8 Replay & Backfill Engine

Administrative endpoints to manage historical log replay from `ulpf_raw.raw_events` into dynamic canonical tables (`ulpf_events.{tableName}`).

#### `POST /v1/admin/sources/{sourceId}/replay`
Triggers an asynchronous historical backfill job for a given log source.

* **Path Parameters**:
  - `sourceId` (string, required): Unique identifier of the log source.
* **Request Body** (optional):
  ```json
  {
    "fromTime": "2026-09-01T00:00:00",
    "toTime": "2026-09-15T23:59:59",
    "chunkSize": 5000
  }
  ```
  *If omitted, `toTime` defaults to current timestamp, `fromTime` defaults to `toTime - 30 days`, and `chunkSize` defaults to 1,000 records.*
* **Response (202 ACCEPTED)**:
  ```json
  {
    "jobId": "replay_a1b2c3d4",
    "sourceId": "src_123456",
    "targetTable": "events_crowdstrike_falcon_edr",
    "status": "RUNNING",
    "eventsTotal": 450000,
    "eventsProcessed": 0,
    "eventsInserted": 0,
    "eventsSkipped": 0,
    "eventsFailed": 0,
    "startTime": "2026-10-21T08:00:00",
    "endTime": null,
    "errorMessage": null
  }
  ```
* **Error Responses**:
  - `404 NOT FOUND`: Source or active mapping version does not exist.
  - `409 CONFLICT`: A replay job is already active (`RUNNING` or `QUEUED`) for this source.

#### `GET /v1/admin/sources/{sourceId}/replay/status`
Queries the real-time progress and status of a source's replay job.

* **Path Parameters**:
  - `sourceId` (string, required): Unique identifier of the log source.
* **Response (200 OK)**:
  ```json
  {
    "jobId": "replay_a1b2c3d4",
    "sourceId": "src_123456",
    "targetTable": "events_crowdstrike_falcon_edr",
    "status": "COMPLETED",
    "eventsTotal": 450000,
    "eventsProcessed": 450000,
    "eventsInserted": 448500,
    "eventsSkipped": 1500,
    "eventsFailed": 0,
    "startTime": "2026-10-21T08:00:00",
    "endTime": "2026-10-21T08:02:15",
    "errorMessage": null
  }
  ```
* **Error Responses**:
  - `404 NOT FOUND`: No active or past replay job recorded for this source.

#### `DELETE /v1/admin/sources/{sourceId}/replay`
Requests immediate cancellation of a currently executing replay job.

* **Path Parameters**:
  - `sourceId` (string, required): Unique identifier of the log source.
* **Response (200 OK)**:
  ```json
  {
    "message": "Cancellation requested for replay job on source src_123456"
  }
  ```
* **Error Responses**:
  - `404 NOT FOUND`: No active running replay job found for this source.

---

### 3.8 Tenant-Scoped Vendor Webhooks

Provides self-service registration and health monitoring for vendor-owned external alert sinks. Accessible strictly to authenticated users with the `VENDOR` role.

#### `GET /v1/vendor/webhook`
Retrieves the active webhook configuration and delivery health metrics for the authenticated vendor.

* **Headers**: `Authorization: Bearer <token>` (Role: `VENDOR`)
* **Response (200 OK)**:
  ```json
  {
    "webhookId": "wh_45a6b7c8",
    "vendorId": "vnd_987654",
    "webhookUrl": "https://api.vendor.com/webhooks/ulpf",
    "hasSecret": true,
    "isActive": true,
    "status": "HEALTHY",
    "lastStatusCode": 200,
    "lastAttemptAt": "2026-10-25T14:22:10",
    "consecutiveFails": 0,
    "updatedAt": "2026-10-25T12:00:00"
  }
  ```
* **Error Responses**:
  - `404 NOT FOUND`: No webhook currently configured for this vendor.

#### `POST /v1/vendor/webhook`
Registers a new webhook URL or updates the existing configuration. Omission of `secretKey` preserves the existing secret in SQLite.

* **Headers**: `Authorization: Bearer <token>` (Role: `VENDOR`)
* **Request Body**:
  ```json
  {
    "webhookUrl": "https://api.vendor.com/webhooks/ulpf",
    "secretKey": "my-optional-new-hmac-secret",
    "isActive": true
  }
  ```
* **SSRF Protection Rules**:
  - Rejects loopback destinations (`127.0.0.1`, `localhost`, `::1`).
  - Rejects cloud metadata addresses (`169.254.169.254`).
  - Requires valid `http` or `https` protocol schemes.
* **Response (200 OK)**: Returns the updated `VendorWebhookResponse` object.

#### `POST /v1/vendor/webhook/test`
Dispatches an immediate out-of-band test ping (`TEST_PING`) to verify external endpoint reachability and signature computation.

* **Headers**: `Authorization: Bearer <token>` (Role: `VENDOR`)
* **Response (200 OK)**:
  ```json
  {
    "success": true,
    "statusCode": 200,
    "latencyMs": 48,
    "message": "Test ping delivered successfully"
  }
  ```

#### Outbound Webhook Payload Format & HMAC Headers
All outbound webhook HTTP POST requests dispatched by `AlertDispatcher` include:
* **Headers**:
  - `Content-Type: application/json`
  - `X-ULPF-Event: SCHEMA_DRIFT | INGESTION_FAILURE | TEST_PING`
  - `X-ULPF-Timestamp: 1792940000000` (Unix millisecond epoch)
  - `X-ULPF-Signature-256: <hex-encoded-hmac-sha256>` (Computed over `${timestamp}.${rawJsonBody}`)
* **Payload Structure**:
  ```json
  {
    "alertType": "SCHEMA_DRIFT",
    "severity": "WARNING",
    "sourceId": "src_123456",
    "vendorId": "vnd_987654",
    "title": "High Schema Drift Detected",
    "message": "Source 'Firewall-1' has unmapped drift ratio of 42.5%",
    "timestamp": "2026-10-25T14:22:10",
    "details": {
      "unmappedRatio": 0.425,
      "unmappedKeys": ["custom_tag", "sensor_vlan"]
    }
  }
  ```

---

## 4. Error Response Structure

All API errors return standard HTTP error status codes and JSON error objects:

```json
{
  "timestamp": "2026-09-12T10:35:00",
  "status": 403,
  "error": "Forbidden",
  "message": "Invalid or revoked API key credential",
  "path": "/v1/events"
}
```
