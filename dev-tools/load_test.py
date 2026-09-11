#!/usr/bin/env python3
import time
import json
import math
import random
import sys
import threading
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

API_KEY = "ulpf_live_e7a7def927574c48adf9430dd73eda12b783605d"
INGEST_URL = "http://localhost:3000/v1/events"

BASE_PAYLOAD = {
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

def generate_payload():
    payload = dict(BASE_PAYLOAD)
    payload["timestamp"] = time.strftime("%Y-%m-%dT%H:%M:%S.") + f"{random.randint(100, 999)}Z"
    payload["user"] = {
        "username": f"user_{random.randint(1000, 9999)}",
        "attempt_count": random.randint(1, 10)
    }
    return payload

def calc_percentile(sorted_data, p):
    if not sorted_data:
        return 0.0
    k = (len(sorted_data) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_data[int(k)]
    return sorted_data[int(f)] * (c - k) + sorted_data[int(c)] * (k - f)

def run_stress_step(concurrency, duration_sec):
    success_count = 0
    error_count = 0
    total_bytes = 0
    latencies = []
    lock = threading.Lock()
    running = True

    def worker():
        nonlocal success_count, error_count, total_bytes
        while running:
            payload_data = generate_payload()
            json_bytes = json.dumps(payload_data).encode("utf-8")
            payload_len = len(json_bytes)
            
            req = urllib.request.Request(
                url=INGEST_URL,
                data=json_bytes,
                headers={
                    "Content-Type": "application/json",
                    "X-API-Key": API_KEY,
                    "Connection": "keep-alive"
                },
                method="POST"
            )
            
            t0 = time.perf_counter()
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    t1 = time.perf_counter()
                    lat_ms = (t1 - t0) * 1000.0
                    if resp.status in (200, 201, 202):
                        with lock:
                            success_count += 1
                            total_bytes += payload_len
                            latencies.append(lat_ms)
                    else:
                        with lock:
                            error_count += 1
            except Exception:
                with lock:
                    error_count += 1

    executor = ThreadPoolExecutor(max_workers=concurrency)
    for _ in range(concurrency):
        executor.submit(worker)

    step_start = time.time()
    time.sleep(duration_sec)
    running = False
    executor.shutdown(wait=False)
    actual_duration = time.time() - step_start

    with lock:
        s_count = success_count
        e_count = error_count
        t_bytes = total_bytes
        lats = sorted(latencies)

    eps = s_count / actual_duration if actual_duration > 0 else 0
    mbps = (t_bytes / (1024 * 1024)) / actual_duration if actual_duration > 0 else 0
    p50 = calc_percentile(lats, 50)
    p95 = calc_percentile(lats, 95)
    p99 = calc_percentile(lats, 99)
    err_rate = (e_count / (s_count + e_count) * 100.0) if (s_count + e_count) > 0 else 0

    return {
        "concurrency": concurrency,
        "duration": actual_duration,
        "total_requests": s_count + e_count,
        "success": s_count,
        "errors": e_count,
        "err_rate": err_rate,
        "eps": eps,
        "mbps": mbps,
        "p50": p50,
        "p95": p95,
        "p99": p99
    }

def main():
    concurrency_steps = [16, 32, 64, 128, 256, 512]
    step_duration = 6  # seconds per step

    print("\n" + "=" * 90)
    print("🔥 ULPF AUTO-RAMPING SATURATION & LATENCY PERCENTILE BENCHMARK")
    print(f"Target Endpoint: {INGEST_URL}")
    print("Pushing concurrency steps until latency degrades / system saturates...")
    print("=" * 90)
    print(f"{'Concurrency':<12} | {'Throughput (EPS)':<18} | {'Bandwidth (MB/s)':<18} | {'P50 (ms)':<10} | {'P95 (ms)':<10} | {'P99 (ms)':<10} | {'Error Rate':<10}")
    print("-" * 90)

    results = []
    saturation_detected = False

    for c in concurrency_steps:
        res = run_stress_step(c, step_duration)
        results.append(res)

        print(f"{res['concurrency']:<12} | {res['eps']:<18.1f} | {res['mbps']:<18.2f} | {res['p50']:<10.2f} | {res['p95']:<10.2f} | {res['p99']:<10.2f} | {res['err_rate']:<10.1f}%")

        # Detect saturation: P95 latency > 250ms or Error rate > 5% or throughput drop
        if res["p95"] > 250 or res["err_rate"] > 5.0:
            print("-" * 90)
            print(f"⚠️ SYSTEM SATURATION DETECTED at concurrency {c}! (P95 latency: {res['p95']:.2f}ms, Errors: {res['err_rate']:.1f}%)")
            saturation_detected = True
            break

    print("=" * 90)
    print("📊 BENCHMARK SATURATION SUMMARY & LATENCY PROFILE")
    print("-" * 90)
    best_step = max(results, key=lambda x: x["eps"])
    print(f"🌟 Peak Engine Throughput:   {best_step['eps']:.1f} Events/Sec (EPS)")
    print(f"🚀 Peak Bandwidth:           {best_step['mbps']:.2f} MB/s (~{best_step['mbps']*8:.2f} Mbps)")
    print(f"🎯 Optimal Concurrency:      {best_step['concurrency']} Threads")
    print(f"⏱️  Median Latency (P50):      {best_step['p50']:.2f} ms")
    print(f"⏱️  95th Percentile (P95):   {best_step['p95']:.2f} ms")
    print(f"⏱️  99th Percentile (P99):   {best_step['p99']:.2f} ms")
    print("=" * 90 + "\n")

if __name__ == "__main__":
    main()
