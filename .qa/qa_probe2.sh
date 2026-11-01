#!/bin/bash
B=http://localhost:8080/api/v1/utils
say() { echo; echo "### $*"; }

say "P5 DOD-05 secretKey redaction (HMAC path: algorithm=SHA-256 + secretKey)"
curl -s -X POST $B/crypto/hash -H 'Content-Type: application/json' -H 'X-Request-Id: qa-secret-0002' \
  -d '{"content":"hello","algorithm":"SHA-256","secretKey":"SUPER-SECRET-KEY-VALUE"}' -o /tmp/p5b.json; cat /tmp/p5b.json; echo
curl -s "$B/audit/entries?limit=200" -o /tmp/p5b_list.json
echo -n "SUPER-SECRET-KEY-VALUE occurrences across journal list+detail: "
curl -s "$B/audit/entries?limit=200" > /tmp/p5b_all.json
SID=$(python3 -c 'import json;d=json.load(open("/tmp/p5b_all.json"))["data"];print([e["id"] for e in d if e["requestId"]=="qa-secret-0002"][0])')
curl -s "$B/audit/entries/$SID" > /tmp/p5b_detail.json
cat /tmp/p5b_all.json /tmp/p5b_detail.json | grep -o 'SUPER-SECRET-KEY-VALUE' | wc -l
echo "-- stored request redaction --"
python3 -c 'import json;e=json.load(open("/tmp/p5b_detail.json"))["data"];print("request:",json.dumps(e["request"]),"| result.algorithm:",e["result"]["algorithm"],"| isHmac:",e["result"]["isHmac"])'

say "P6 DOD-05 journal bound: insert > max-entries (200) and verify bound + oldest evicted"
python3 -c 'import json;d=json.load(open("/tmp/p5b_all.json"))["data"];print("entries before flood:",len(d))'
for i in $(seq 1 215); do
  curl -s -o /dev/null -X POST $B/crypto/base64 -H 'Content-Type: application/json' \
    -H "X-Request-Id: qa-flood-$(printf '%04d' $i)" -d '{"content":"x","action":"ENCODE"}'
done
curl -s "$B/audit/entries?limit=1000" -o /tmp/p6_all.json
python3 - <<'PY'
import json
d=json.load(open('/tmp/p6_all.json'))['data']
print('entries after 215 inserts:',len(d))
print('max-entries bound respected (<=200):',len(d)<=200)
ids=[e['requestId'] for e in d]
print('oldest qa-echo/dod entries evicted:', 'qa-dod04-0001' not in ids and 'qa-secret-0002' not in ids)
print('newest entry present:', 'qa-flood-0215' in ids)
PY

say "P7 DOD-07 audit endpoints in /v3/api-docs"
curl -s http://localhost:8080/v3/api-docs -o /tmp/docs.json
grep -o '"/api/v1/utils/audit[a-z/{}]*"' /tmp/docs.json | sort -u
python3 -c 'import json;d=json.load(open("/tmp/docs.json"));print("paths count:",len(d["paths"]));[print(p,list(d["paths"][p].keys())) for p in d["paths"] if "audit" in p]'

say "P8 DOD-03 unknown id -> 404 NOT_FOUND; non-deterministic entry -> 409"
curl -s -o /tmp/p8a.json -w 'unknown id http=%{http_code}\n' "$B/audit/entries/00000000-0000-0000-0000-000000000000"; cat /tmp/p8a.json; echo
curl -s -o /dev/null -X POST $B/datetime/current -H 'X-Request-Id: qa-nondet-001'
curl -s "$B/audit/entries?limit=200" -o /tmp/p8_all.json
NID=$(python3 -c 'import json;d=json.load(open("/tmp/p8_all.json"))["data"];print([e["id"] for e in d if e["requestId"]=="qa-nondet-001"][0])')
curl -s -o /tmp/p8b.json -w 'non-deterministic replay http=%{http_code}\n' -X POST "$B/audit/entries/$NID/replay"; cat /tmp/p8b.json; echo

say "P9 DOD-02 bad query params -> 400 with correlation id"
curl -s -D /tmp/p9.h -o /tmp/p9.json -w 'bad state http=%{http_code}\n' "$B/audit/entries?state=BOGUS" -H 'X-Request-Id: qa-badq-0001'; grep -i '^X-Request-Id' /tmp/p9.h; cat /tmp/p9.json; echo
curl -s -o /tmp/p9b.json -w 'bad limit http=%{http_code}\n' "$B/audit/entries?limit=0"; cat /tmp/p9b.json; echo
