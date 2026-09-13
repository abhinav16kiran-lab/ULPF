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
| `/v1/auth/signup` | `POST` | Public | Register a new user (`ADMIN`, `VENDOR`, `USER`) |
| `/v1/auth/login` | `POST` | Public | Authenticate user and issue JWT Bearer token |
| `/v1/onboard/{username}` | `POST` | `VENDOR`/`ADMIN` | Submit sample log file and schema for AI field mapping |
| `/v1/onboard/update` | `POST` | `VENDOR`/`ADMIN` | Update an existing log source mapping schema |
| `/v1/admin/onboard` | `GET` | `ADMIN` | List all pending vendor onboarding requests |
| `/v1/admin/onboard/{id}`| `PUT` | `ADMIN` | Approve or reject onboarding request candidate mapping |
| `/v1/admin/clickhouse/schemas` | `GET` | `ADMIN` | Inspect active ClickHouse database and table schemas |
| `/v1/events` | `POST` | `VENDOR`/`ADMIN` | Primary plug-and-play high-throughput log event ingestion |
| `/v1/notifications` | `GET` | Authenticated | Fetch notifications and issued API keys for logged-in user |
| `/v1/notifications/{id}/read` | `PUT` | Authenticated | Mark a notification item as read |
| `/v1/analytics` | `GET` | `ADMIN` | Read-only ClickHouse metrics aggregation query execution |
| `/v1/analytics/schemas` | `GET` | `ADMIN` | Get ClickHouse database catalog tables and dynamic columns |
| `/v1/analytics/search` | `GET` | `ADMIN` | Full-text substring & regex raw log search with Bloom filter |
| `/v1/analytics/timeseries` | `GET` | `ADMIN` | Time-series histogram throughput & error spike aggregation |
| `/v1/analytics/import/file` | `POST` | `ADMIN` | Bulk upload `.json`, `.gz`, `.log`, or `.csv` files into ClickHouse |
| `/v1/analytics/export/parquet` | `GET` | `ADMIN` | Export ClickHouse logs into Snappy-compressed Apache Parquet |
| `/v1/integrity/blocks` | `GET` | Authenticated | List all cryptographic batch integrity blocks |
| `/v1/integrity/verify/{blockId}` | `POST` | Authenticated | Re-verify cryptographic Merkle root hash for specific block |
| `/v1/integrity/verify-all` | `POST` | `ADMIN` | Execute live forensic Merkle tree cryptographic bulk audit |

---

## 3. Detailed Endpoint Specifications

### 3.1 Authentication

#### `POST /v1/auth/signup`
Registers a new system user.

* **Request Body**:
  ```json
  {
    "username": "crowdstrike_admin",
    "password": "SecurePassword123!",
    "role": "VENDOR"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "message": "User registered successfully",
    "userId": "usr_9012830192"
  }
  ```

#### `POST /v1/auth/login`
Authenticates credentials and returns a JWT token.

* **Request Body**:
  ```json
  {
    "username": "crowdstrike_admin",
    "password": "SecurePassword123!"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "username": "crowdstrike_admin",
    "role": "VENDOR",
    "userId": "usr_9012830192"
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
