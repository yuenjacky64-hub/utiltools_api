#!/bin/bash
B=http://localhost:8080/api/v1/utils
say() { echo; echo "### $*"; }

say "P1 DOD-02 bad hash algorithm -> 400 envelope carrying the same X-Request-Id"
curl -s -D /tmp/p1.h -X POST $B/crypto/hash -H 'Content-Type: application/json' \
  -H 'X-Request-Id: qa-dod02-0001' -d '{"content":"hello","algorithm":"NOPE-1"}' -o /tmp/p1.b
head -1 /tmp/p1.h; grep -i '^X-Request-Id' /tmp/p1.h; cat /tmp/p1.b; echo
curl -s "$B/audit/entries?state=FAILED&limit=5" -o /tmp/p1_list.json
FAILID=$(grep -o '"id":"[^"]*"' /tmp/p1_list.json | head -1 | cut -d'"' -f4)
[ -z "$FAILID" ] && { echo "FATAL: no FAILED entry found"; exit 1; }
echo "-- journal view (state=FAILED) --"
python3 -c 'import json;d=json.load(open("/tmp/p1_list.json"))["data"];[print(json.dumps(e)) for e in d if e["requestId"]=="qa-dod02-0001"]'

say "P2 DOD-03 replay FAILED entry -> 409 REPLAY_CONFLICT, entry unchanged"
curl -s "$B/audit/entries/$FAILID" -o /tmp/p2_before.json; echo "-- before --"; cat /tmp/p2_before.json; echo
curl -s -o /tmp/p2_replay.json -w 'http=%{http_code}\n' -X POST "$B/audit/entries/$FAILID/replay"; cat /tmp/p2_replay.json; echo
curl -s "$B/audit/entries/$FAILID" -o /tmp/p2_after.json; echo "-- after --"; cat /tmp/p2_after.json; echo
python3 -c 'import json;b=json.load(open("/tmp/p2_before.json"))["data"];a=json.load(open("/tmp/p2_after.json"))["data"];print("state:",a["state"],"| completedAt unchanged:",b["completedAt"]==a["completedAt"],"| result:",a["result"],"| errorCode:",a["errorCode"])'

say "P3 DOD-04 successful hash replay -> VERIFIED, original unchanged"
curl -s -X POST $B/crypto/hash -H 'Content-Type: application/json' -H 'X-Request-Id: qa-dod04-0001' \
  -d '{"content":"hello","algorithm":"SHA-256"}' -o /tmp/p3_resp.json; cat /tmp/p3_resp.json; echo
curl -s "$B/audit/entries?limit=20" -o /tmp/p3_list.json
OKID=$(python3 -c 'import json;d=json.load(open("/tmp/p3_list.json"))["data"];print([e["id"] for e in d if e["requestId"]=="qa-dod04-0001"][0])')
echo "entry=$OKID"
curl -s "$B/audit/entries/$OKID" -o /tmp/p3_before.json
curl -s -o /tmp/p3_replay.json -w 'http=%{http_code}\n' -X POST "$B/audit/entries/$OKID/replay"; cat /tmp/p3_replay.json; echo
curl -s "$B/audit/entries/$OKID" -o /tmp/p3_after.json
python3 -c 'import json;b=json.load(open("/tmp/p3_before.json"))["data"];a=json.load(open("/tmp/p3_after.json"))["data"];print("original state:",a["state"],"| result unchanged:",b["result"]==a["result"],"| completedAt unchanged:",b["completedAt"]==a["completedAt"]);r=json.load(open("/tmp/p3_replay.json"))["data"]["replay"];print("replay.state:",r["state"],"| replay.result:",json.dumps(r.get("result"))[:140])'

say "P4 DOD-06 request id echo / ThreadLocal isolation"
echo "-- success with header --"
curl -s -D /tmp/p4s.h $B/datetime/current -H 'X-Request-Id: qa-echo-0001' -o /tmp/p4s.b; grep -i '^X-Request-Id' /tmp/p4s.h; grep -o '"requestId":"[^"]*"' /tmp/p4s.b
echo "-- error with header --"
curl -s -D /tmp/p4e.h -X POST $B/crypto/hash -H 'Content-Type: application/json' -H 'X-Request-Id: qa-echo-0002' -d '{"algorithm":"NOPE-2"}' -o /tmp/p4e.b; head -1 /tmp/p4e.h; grep -i '^X-Request-Id' /tmp/p4e.h; grep -o '"requestId":"[^"]*"' /tmp/p4e.b
echo "-- two headerless requests --"
curl -s -D /tmp/p4n1.h $B/datetime/current -o /tmp/p4n1.b; curl -s -D /tmp/p4n2.h $B/datetime/current -o /tmp/p4n2.b
grep -i '^X-Request-Id' /tmp/p4n1.h /tmp/p4n2.h; grep -o '"requestId":"[^"]*"' /tmp/p4n1.b /tmp/p4n2.b
python3 -c 'import re;ids={re.search(r"requestId\":\"([^\"]+)",open(f).read()).group(1) for f in ["/tmp/p4n1.b","/tmp/p4n2.b"]};print("distinct headerless ids:",len(ids)==2,ids)'
echo "-- malformed header (too short) replaced by generated uuid --"
curl -s -D /tmp/p4m.h $B/datetime/current -H 'X-Request-Id: short' -o /tmp/p4m.b; grep -i '^X-Request-Id' /tmp/p4m.h

say "P5 DOD-05 secretKey never returned in audit output"
curl -s -X POST $B/crypto/hash -H 'Content-Type: application/json' -H 'X-Request-Id: qa-secret-0001' \
  -d '{"content":"hello","algorithm":"HmacSHA256","secretKey":"SUPER-SECRET-KEY-VALUE"}' -o /tmp/p5.b; cat /tmp/p5.b; echo
curl -s "$B/audit/entries?limit=200" -o /tmp/p5_list.json
echo -n "secretKey occurrences in journal list: "; grep -o 'SUPER-SECRET-KEY-VALUE' /tmp/p5_list.json | wc -l
