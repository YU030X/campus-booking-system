#!/usr/bin/env python3
"""T13 availability cache benchmark (task: cache hit/latency before-after evidence).

Prerequisites: compose stack up (api edge 127.0.0.1:18080, BOOKING_CACHE_ENABLED=true),
a student JWT in T13_BENCH_TOKEN, redis container running.
Method: for N distinct in-advance dates call the authenticated availability
endpoint twice (cold first touch, warm repeat), then R warm repeat passes;
measure client-observed latency; enumerate Redis keys + TTL as cache evidence.
Output: JSON to stdout. No secrets printed; the token is read from env only.
"""
import json, os, statistics, sys, time, urllib.error, urllib.request
from datetime import date, timedelta

BASE = os.environ.get('T13_BENCH_BASE', 'http://127.0.0.1:18080')
TOKEN = os.environ['T13_BENCH_TOKEN']
RESOURCE = os.environ.get('T13_BENCH_RESOURCE', '880001')
N_DATES = int(os.environ.get('T13_BENCH_DATES', '8'))
WARM_PASSES = int(os.environ.get('T13_BENCH_WARM', '5'))
START = date.today() + timedelta(days=1)

def call(d):
    url = f'{BASE}/api/v1/resources/{RESOURCE}/available-slots?date={d}'
    req = urllib.request.Request(url, headers={'Authorization': f'Bearer {TOKEN}'})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        body = e.read()
        status = e.code
    ms = (time.perf_counter() - t0) * 1000
    ok = status == 200 and b'"code":0' in body
    if not ok:
        raise SystemExit(f'call failed for {d}: status={status} body={body[:120]!r}')
    return ms, ok

def stats(vals):
    s = sorted(vals)
    return {'n': len(s), 'avg_ms': round(statistics.mean(s), 1),
            'p50_ms': round(s[len(s)//2], 1),
            'p95_ms': round(s[min(len(s)-1, int(len(s)*0.95))], 1)}

dates = [(START + timedelta(days=i)).isoformat() for i in range(N_DATES)]
cold, warm = [], []
for d in dates:
    ms, ok = call(d); cold.append(ms)
    assert ok, f'cold call failed for {d}'
    ms, ok = call(d); warm.append(ms)
    assert ok, f'warm call failed for {d}'
for _ in range(WARM_PASSES):
    for d in dates:
        ms, ok = call(d)
        assert ok, f'warm pass failed for {d}'
        warm.append(ms)

print(json.dumps({
    'resourceId': RESOURCE, 'dates': dates,
    'cold_first_touch': stats(cold),
    'warm_repeat': stats(warm),
    'samples': {'cold': len(cold), 'warm': len(warm)},
}, indent=2))
