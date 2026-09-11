#!/usr/bin/env python3
import json
import sys
import urllib.request
import urllib.error

# Hardcoded test credentials & payload
API_KEY = "ulpf_live_e7a7def927574c48adf9430dd73eda12b783605d"
INGEST_URL = "http://localhost:3000/v1/events"

PAYLOAD = {
    "timestamp": "2026-09-10T11:45:32.124Z",
    "level": "ERROR",
    "service": "auth-service",
    "environment": "production",
    "http": {
        "method": "POST",
        "path": "/v1/auth/login",
        "status_code": 401,
        "latency_ms": 42.5,
        "user_agent": "Mozilla/5.0 (X11; Linux x86_64)"
    },
    "network": {
        "client_ip": "192.168.1.105",
        "server_ip": "10.0.4.12"
    },
    "user": {
        "username": "admin_guest",
        "attempt_count": 5
    },
    "message": "Authentication failed: invalid credentials supplied"
}

def main():
    print(f"🚀 Sending log payload to ULPF Ingestion Service ({INGEST_URL})...")
    print(f"🔑 API Key: {API_KEY}\n")

    json_bytes = json.dumps(PAYLOAD).encode("utf-8")
    
    req = urllib.request.Request(
        url=INGEST_URL,
        data=json_bytes,
        headers={
            "Content-Type": "application/json",
            "X-API-Key": API_KEY
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(req) as response:
            status_code = response.getcode()
            response_body = response.read().decode("utf-8")
            print(f"✅ SUCCESS [HTTP {status_code}]")
            print("Response Body:")
            print(json.dumps(json.loads(response_body), indent=2))
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        print(f"❌ ERROR [HTTP {e.code}]: {error_body}")
        sys.exit(1)
    except Exception as e:
        print(f"❌ CONNECTION FAILED: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
