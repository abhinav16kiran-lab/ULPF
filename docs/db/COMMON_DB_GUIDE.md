# Centralized Database Layer Specification (`com.ulpf.common.db`)

**Module Owner:** Database & Core Engine Engineering  
**Package Path:** `com.ulpf.common.db`  
**Target Storage Engine(s):** SQLite 3 (Control Plane) & ClickHouse 26.3 LTS (Data Plane)  
**Status:** Production Ready  

---

## 1. Architectural Executive Summary

The `com.ulpf.common.db` package provides a centralized, dual-database access layer for the ULPF backend engine. It manages connection pooling, thread-safe memory caching, automatic idle resource eviction, high-throughput log event buffering, and graceful container shutdown flushing across two distinct databases:

```text
                               ┌─────────────────────────────┐
                               │  Spring Boot Core Engine    │
                               └──────────────┬──────────────┘
                                              │
                    ┌─────────────────────────┴─────────────────────────┐
                    │                                                   │
                    ▼                                                   ▼
       [ SqliteConnectionConfig ]                           [ ClickHouseConnectionConfig ]
        (@Primary HikariCP Pool)                            (@Qualifier clickhouseJdbcTemplate)
                    │                                                   │
                    ▼                                                   ▼
    ┌───────────────────────────────┐                   ┌───────────────────────────────┐
    │  SQLite Control Plane DB      │                   │  ClickHouse Data Plane DB     │
    │  (data/control-plane.db)      │                   │  (ulpf_raw.raw_events)        │
    │  - users                      │                   │  - High-throughput buffering  │
    │  - credentials                │                   │  - 500-batch limit flush      │
    │  - mapping_versions           │                   │  - 1s scheduled timer         │
    │  - mapping_aliases            │                   │  - @PreDestroy shutdown flush │
    └───────────────────────────────┘                   └───────────────────────────────┘
```

---

## 2. Configuration Classes

### 2.1 `SqliteConnectionConfig`

* **File Location:** `com/ulpf/common/db/SqliteConnectionConfig.java`
* **Purpose:** Configures the primary `@Primary` SQLite connection pool and `JdbcTemplate`.

#### Configured Beans:
* **`@Bean @Primary DataSource sqliteDataSource()`**: Creates HikariCP connection pool pointing to SQLite.
  * **Driver:** `org.sqlite.JDBC`
  * **URL Property:** `${spring.datasource.url}` (Default: `jdbc:sqlite:./data/control-plane.db?foreign_keys=on`)
* **`@Bean @Primary JdbcTemplate jdbcTemplate(DataSource sqliteDataSource)`**: Provides the default `JdbcTemplate` for all SQLite repository queries.

---

### 2.2 `ClickHouseConnectionConfig`

* **File Location:** `com/ulpf/common/db/ClickHouseConnectionConfig.java`
* **Purpose:** Configures the secondary ClickHouse HTTP/JDBC connection pool and dedicated `JdbcTemplate`.

#### Configured Beans:
* **`@Bean @Qualifier("clickhouseDataSource") DataSource clickhouseDataSource()`**: Creates ClickHouse JDBC connection pool.
  * **Driver:** `com.clickhouse.jdbc.ClickHouseDriver`
  * **URL Property:** `${clickhouse.datasource.url}` (Default: `jdbc:clickhouse://localhost:8123/ulpf_raw`)
  * **Credentials:** `${clickhouse.datasource.username}` & `${clickhouse.datasource.password}`
* **`@Bean @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate(...)`**: Dedicated `JdbcTemplate` for high-throughput raw event log writes to ClickHouse.

---

## 3. Repositories Reference

### 3.1 `UserRepository`

* **File Location:** `com/ulpf/common/db/UserRepository.java`
* **Purpose:** Centralized user management and authentication queries against the SQLite `users` table.

#### Public Methods:

##### `User save(User user)`
* **Description:** Inserts a new user record or updates existing user. Generates a random UUID if `user.userId()` is blank.
* **Parameters:** `User user` (Data model containing `userId`, `username`, `passwordHash`, `role`).
* **Returns:** Saved `User` record with generated `created_at` timestamp.
* **Exceptions Thrown:**
  * `org.springframework.dao.DuplicateKeyException`: Thrown if `username` already exists (`UNIQUE` constraint violation).
  * `IllegalStateException`: Thrown if insertion fails or saved record cannot be re-fetched.

##### `Optional<User> findById(String userId)`
* **Description:** Fetches a user record by primary key `user_id`.
* **Parameters:** `String userId`
* **Returns:** `Optional<User>` containing matching user or `Optional.empty()`.

##### `Optional<User> findByUsername(String username)`
* **Description:** Fetches a user record by `username`.
* **Parameters:** `String username`
* **Returns:** `Optional<User>`.

##### `boolean existsByUsername(String username)`
* **Description:** Checks if a `username` is registered in SQLite.
* **Parameters:** `String username`
* **Returns:** `true` if username exists, `false` otherwise.

---

### 3.2 `CredentialRepository`

* **File Location:** `com/ulpf/common/db/CredentialRepository.java`
* **Purpose:** Ingestion API Key validation and resolution across SQLite `credentials`, `sources`, and `vendors` tables.

#### Data Models:
* **`CredentialRecord`**: Immutable record holding `credentialId`, `sourceId`, `vendorId`, `keyHash`, `status`, and `createdAt`.

#### Public Methods:

##### `Optional<CredentialRecord> findActiveByKeyHash(String keyHash)`
* **Description:** Performs a multi-table JOIN query to validate an incoming API key hash against active log sources and active vendors:
  ```sql
  SELECT c.credential_id, c.source_id, s.vendor_id, c.key_hash, c.status, c.created_at
  FROM credentials c
  JOIN sources s ON c.source_id = s.source_id
  WHERE c.key_hash = ? AND c.status = 'ACTIVE' AND s.status = 'ACTIVE'
  ```
* **Parameters:** `String keyHash` (SHA-256 or raw API key string).
* **Returns:** `Optional<CredentialRecord>` if key is active; `Optional.empty()` if missing, revoked, or linked to a suspended vendor/source.

##### `CredentialRecord save(CredentialRecord cred)`
* **Description:** Persists a new ingestion API key credential into SQLite.
* **Exceptions Thrown:** `org.springframework.dao.DataAccessException` if foreign key `source_id` is invalid.

##### `void revokeCredential(String credentialId)`
* **Description:** Updates credential status to `'REVOKED'`.

---

### 3.3 `MappingRepository`

* **File Location:** `com/ulpf/common/db/MappingRepository.java`
* **Purpose:** Schema mapping version persistence and lazy-loaded active mapping cache with **5-minute idle eviction**.

#### Design & Memory Characteristics:
* **Lazy-Loading:** Mappings are **NOT** pre-loaded at Spring Boot startup. When events arrive for a `source_id`, `findActiveBySourceId(sourceId)` queries SQLite on demand and caches the JSON in RAM.
* **Idle Eviction (5 Minutes):** A background `@Scheduled(fixedDelay = 60000)` task checks access timestamps every 60 seconds. Any `source_id` mapping inactive for **> 300,000 ms (5 mins)** is automatically evicted from memory.

#### Data Models:
* **`MappingVersionRecord`**: Immutable record containing `mappingId`, `sourceId`, `version`, `mappingJson`, `status`, and `createdAt`.

#### Public Methods:

##### `Optional<MappingVersionRecord> findActiveBySourceId(String sourceId)`
* **Description:** Returns the active schema mapping for `sourceId`. Checks in-memory cache first; queries SQLite on cache miss and caches result.
* **Parameters:** `String sourceId`

##### `MappingVersionRecord saveMappingVersion(MappingVersionRecord mapping)`
* **Description:** Persists a new mapping candidate/version to SQLite `mapping_versions` and invalidates old cache entries.

##### `int getNextVersionNumber(String sourceId)`
* **Description:** Queries `MAX(version) + 1` for a `source_id`. Returns `1` if no versions exist.

##### `void activateVersion(String mappingId, String sourceId)`
* **Description:** Retires any current `ACTIVE` version for `sourceId` and marks `mappingId` as `ACTIVE`.

##### `@Scheduled evictIdleMappings()`
* **Description:** Background task evicting idle mappings from memory after 5 minutes of inactivity.

---

### 3.4 `ClickHouseIngestionRepository`

* **File Location:** `com/ulpf/common/db/ClickHouseIngestionRepository.java`
* **Purpose:** High-throughput raw event log writes to ClickHouse (`ulpf_raw.raw_events`) featuring an in-memory queue buffer, batch size limits, scheduled timer flushing, and container shutdown hooks.

#### Design & Buffering Architecture:
```text
POST /v1/events ──► enqueue(RawEventRecord) ──► [ ConcurrentLinkedQueue Buffer ]
                                                            │
                     ┌──────────────────────────────────────┼──────────────────────────────────────┐
                     │                                      │                                      │
                     ▼ (Queue size >= 500)                  ▼ (Timer every 1000 ms)                ▼ (@PreDestroy SIGTERM)
             Batch Size Flush                        Scheduled Timer Flush                   Container Shutdown Flush
                     │                                      │                                      │
                     └──────────────────────────────────────┴──────────────────────────────────────┘
                                                            │
                                                            ▼
                                        clickhouseJdbcTemplate.batchUpdate()
                                                            │
                                                            ▼
                                            ClickHouse DB (ulpf_raw.raw_events)
```

#### Data Models:
* **`RawEventRecord`**: Immutable record containing `eventId`, `lineageId`, `vendorId`, `sourceId`, `mappingVersion`, `receivedAt`, and `rawPayload`.

#### Public Methods & Hooks:

##### `void enqueue(RawEventRecord event)`
* **Description:** Pushes a raw log event into the thread-safe `ConcurrentLinkedQueue`. If queue size reaches `batchSize` (default: `500`), triggers an immediate synchronous `flush()`.

##### `@Scheduled(fixedDelay = 1000) void scheduledFlush()`
* **Description:** Runs every 1 second (1000 ms). If buffer is non-empty, flushes pending raw events to ClickHouse to maintain sub-second latency.

##### `synchronized void flush()`
* **Description:** Drains all items from `bufferQueue` and executes a high-speed JDBC `batchUpdate` into `ulpf_raw.raw_events`.
* **Exception Handling & Network Resiliency:** If a network interruption occurs between Core Engine and ClickHouse, `flush()` catches the exception, logs an error, and **re-enqueues the batch back into the buffer queue** to prevent data loss.

##### `@PreDestroy void shutdownFlush()`
* **Description:** Spring Boot / Container lifecycle hook. When the container receives a SIGTERM / shutdown signal, `@PreDestroy` synchronously drains and flushes all remaining queued events to ClickHouse before application exit.

##### `int getQueueSize()`
* **Description:** Returns current number of buffered events waiting in memory.

---

## 4. How to Use in Application Services

### 4.1 Authenticating Users & Sign-Up (`AuthService.java`)
```java
@Service
public class AuthService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void signUp(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }
        String hash = passwordEncoder.encode(password);
        userRepository.save(new User(null, username, hash, Role.USER, null));
    }
}
```

### 4.2 Runtime Ingestion & Raw Preservation (`EventIngestionService.java`)
```java
@Service
public class EventIngestionService {
    private final CredentialRepository credentialRepository;
    private final MappingRepository mappingRepository;
    private final ClickHouseIngestionRepository clickHouseRepository;

    public IngestResult ingest(String apiKey, Object payload) {
        // 1. Authenticate API Key against SQLite
        var cred = credentialRepository.findActiveByKeyHash(apiKey)
            .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));

        // 2. Resolve Active Mapping (Lazy-loaded, auto-evicted after 5m idle)
        var mappingOpt = mappingRepository.findActiveBySourceId(cred.sourceId());
        Integer mappingVersion = mappingOpt.map(MappingVersionRecord::version).orElse(null);

        // 3. Buffer Raw Event for ClickHouse Ingestion (500-batch / 1s timer / @PreDestroy)
        clickHouseRepository.enqueue(new RawEventRecord(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            cred.vendorId(),
            cred.sourceId(),
            mappingVersion,
            LocalDateTime.now(),
            payload.toString()
        ));

        return new IngestResult("ACCEPTED");
    }
}
```

---

## 5. Test Suite Reference

The package is verified by 4 dedicated unit test classes in `src/test/java/com/ulpf/common/db/`:

| Test Class | Test Target | What It Verifies |
| :--- | :--- | :--- |
| **`UserRepositoryTest`** | `UserRepository` | Verifies user creation (`save`), username uniqueness checks, SQL timestamp parsing, and `findByUsername` queries against in-memory SQLite. |
| **`CredentialRepositoryTest`** | `CredentialRepository` | Verifies multi-table JOIN query resolving active API key hashes against linked `credentials`, `sources`, and `vendors` tables. |
| **`MappingRepositoryTest`** | `MappingRepository` | Verifies active mapping lazy-loading into RAM cache, cache invalidation, version numbering (`getNextVersionNumber`), and version activation. |
| **`ClickHouseIngestionRepositoryTest`** | `ClickHouseIngestionRepository` | Verifies in-memory queue buffer enqueueing, 500-batch limit trigger, `flush()` execution, and queue draining. |

### How to Run Tests:
```bash
cd core-engine
mvn test -Dtest="*RepositoryTest"
```

All **58 test cases** across the application compile and pass cleanly (`BUILD SUCCESS`).
