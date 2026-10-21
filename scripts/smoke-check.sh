#!/usr/bin/env bash
# RelayDesk end-to-end smoke check against a running local stack.
# Usage: CHANNEL=http://127.0.0.1:19090 smoke-check.sh
# Exercises: login (4 roles), role redirects, customer ticket create + reply + attachment,
# agent claim/reply/note/status, escalation lifecycle, KB publish flow, exports, API keys,
# cross-org authorization denials, internal-note invisibility, audit log, notifications.
set -u
CH=${CHANNEL:-http://127.0.0.1:19090}
PGH=${PG_HOST:-127.0.0.1}; PGP=${PG_PORT:-5434}
Q() { psql -h "$PGH" -p "$PGP" -U relay -d channel -tAc "$1"; }
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got: $2)"; fi; }

JAR=$(mktemp -d); trap "rm -rf $JAR" EXIT

login() { # $1=email $2=password -> cookie jar
  local jar=$JAR/cj
  curl -s -c $jar -o /dev/null "$CH/login"
  local tok=$(grep -o '_csrf" content="[^"]*' /dev/null 2>/dev/null)
  # extract csrf from login page
  local page=$(curl -s -c $jar "$CH/login")
  local csrf=$(echo "$page" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  curl -s -b $jar -c $jar -o /dev/null -w "%{http_code} %{redirect_url}" -X POST "$CH/login" \
    --data-urlencode "username=$1" --data-urlencode "password=$2" --data-urlencode "_csrf=$csrf"
}

echo "== 1. login flows =="
r=$(login admin@relaydesk.test Admin@Relay1); chk "admin login+redirect" "$r" "302 $CH/admin"
r=$(login jordan.lee@acme-corp.test Customer@Relay1); chk "customer login+redirect" "$r" "302 $CH/portal"
r=$(login agent.devon@relaydesk.test Agent@Relay1); chk "agent login+redirect" "$r" "302 $CH/desk"
r=$(login lead.billing@relaydesk.test Lead@Relay1); chk "lead login+redirect" "$r" "302 $CH/desk"
r=$(login nobody@nowhere.test wrongpass); chk "bad credentials" "${r%% *}" "302"
echo "$r" | grep -q "error" && ok "bad credentials -> ?error" || bad "bad credentials no error flag"

echo "== 2. customer journey =="
CJ=$JAR/customer.jar
csrf() { curl -s -b "$1" "$CH$2" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1; }
c=$(csrf /dev/null /login); # noop
# login customer properly
curl -s -c $CJ -o /dev/null "$CH/login"
p=$(curl -s -c $CJ "$CH/login"); t=$(echo "$p" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b $CJ -c $CJ -o /dev/null -X POST "$CH/login" --data-urlencode "username=jordan.lee@acme-corp.test" --data-urlencode "password=Customer@Relay1" --data-urlencode "_csrf=$t"
# portal dashboard renders
code=$(curl -s -b $CJ -o $JAR/portal.html -w "%{http_code}" "$CH/portal"); chk "portal dashboard 200" "$code" "200"
grep -q "Open tickets" $JAR/portal.html && ok "dashboard shows stats" || bad "dashboard stats missing"
# create ticket with attachment
t=$(curl -s -b $CJ "$CH/portal/tickets/new" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
catid=$(Q "select id from categories where name='Billing & Payments'")
echo "smoke test invoice question" > $JAR/up.txt
loc=$(curl -s -b $CJ -o /dev/null -w "%{redirect_url}" -X POST "$CH/portal/tickets" \
  -F "_csrf=$t" -F "subject=Smoke test invoice question" -F "description=Please check my March invoice, it seems too high." \
  -F "categoryId=$catid" -F "priority=MEDIUM" -F "file=@$JAR/up.txt;type=text/plain")
echo "$loc" | grep -q "/portal/tickets/" && ok "ticket created -> $loc" || bad "ticket create failed: $loc"
TID=$(echo "$loc" | sed 's|.*/portal/tickets/||')
# detail shows public description, no internal notes
curl -s -b $CJ -o $JAR/detail.html "$CH/portal/tickets/$TID"
grep -q "March invoice" $JAR/detail.html && ok "detail shows description" || bad "detail missing description"
grep -qi "internal note" $JAR/detail.html && bad "internal note leaked to customer" || ok "no internal-note section for customer"
# customer reply
t=$(curl -s -b $CJ "$CH/portal/tickets/$TID" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" -X POST "$CH/portal/tickets/$TID/reply" --data-urlencode "body=Adding the invoice reference INV-2026-0301." --data-urlencode "_csrf=$t"); chk "customer reply" "$code" "302"
# cross-org denial: fetch a ticket belonging to another org
OTHER=$(Q "select id from tickets where org_name <> 'Acme Corp' limit 1")
if [ -n "$OTHER" ]; then
  code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" "$CH/portal/tickets/$OTHER")
  [ "$code" = "403" ] || [ "$code" = "404" ] && ok "foreign-org ticket blocked ($code)" || bad "foreign-org ticket NOT blocked ($code)"
fi
# customer export
code=$(curl -s -b $CJ -o $JAR/cust.csv -w "%{http_code}" "$CH/portal/tickets/export"); chk "customer export 200" "$code" "200"
grep -qiE "checked logs|internal note body|INV-2026" $JAR/cust.csv && bad "internal notes in customer export" || ok "customer export has no internal notes"

echo "== 3. agent journey =="
AJ=$JAR/agent.jar
curl -s -c $AJ -o /dev/null "$CH/login"
p=$(curl -s -c $AJ "$CH/login"); t=$(echo "$p" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b $AJ -c $AJ -o /dev/null -X POST "$CH/login" --data-urlencode "username=agent.devon@relaydesk.test" --data-urlencode "password=Agent@Relay1" --data-urlencode "_csrf=$t"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/desk"); chk "desk dashboard 200" "$code" "200"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/desk/queue"); chk "agent queue 200" "$code" "200"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/desk/queue/fragment"); chk "queue fragment 200" "$code" "200"
# agent cannot open admin pages
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/admin"); [ "$code" = "403" ] || [ "$code" = "404" ] && ok "agent blocked from admin ($code)" || bad "agent reached admin ($code)"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/admin/users"); chk "agent blocked from users" "$code" "403"
# agent replies on a platform-team ticket
PT=$(Q "select id from tickets where team_id in (select id from teams where name='Platform Engineering') and status='NEW' limit 1")
[ -z "$PT" ] && PT=$(Q "select id from tickets where team_id in (select id from teams where name='Platform Engineering') and status='OPEN' limit 1")
if [ -n "$PT" ]; then
  t=$(curl -s -b $AJ "$CH/desk/tickets/$PT" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/tickets/$PT/claim" --data-urlencode "_csrf=$t"); chk "agent claim" "$code" "302"
  code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/tickets/$PT/reply" --data-urlencode "body=Thanks — we are looking into this." --data-urlencode "_csrf=$t"); chk "agent reply" "$code" "302"
  code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/tickets/$PT/note" --data-urlencode "body=Checked logs: retry storm from 10.2.3.4" --data-urlencode "_csrf=$t"); chk "internal note" "$code" "302"
  # agent on a different team cannot work its ticket
  WT=$(Q "select id from tickets where team_id in (select id from teams where name='Account & Security') and status in ('NEW','OPEN') limit 1")
  if [ -n "$WT" ]; then
    tw=$(curl -s -b $AJ "$CH/desk/tickets/$WT" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
    code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/tickets/$WT/note" --data-urlencode "body=should fail: wrong team" --data-urlencode "_csrf=$tw")
    [ "$code" = "403" ] && ok "wrong-team note blocked (403)" || bad "wrong-team note not blocked (got $code)"
  else
    ok "no wrong-team ticket available (skipped)"
  fi
else
  bad "no platform ticket found for agent journey"
fi

echo "== 4. escalation (lead) =="
LJ=$JAR/lead.jar
curl -s -c $LJ -o /dev/null "$CH/login"
p=$(curl -s -c $LJ "$CH/login"); t=$(echo "$p" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b $LJ -c $LJ -o /dev/null -X POST "$CH/login" --data-urlencode "username=lead.billing@relaydesk.test" --data-urlencode "password=Lead@Relay1" --data-urlencode "_csrf=$t"
code=$(curl -s -b $LJ -o /dev/null -w "%{http_code}" "$CH/desk/escalations"); chk "escalation queue 200" "$code" "200"
ESC=$(Q "select id from escalations where status='REQUESTED' and team_id in (select id from teams where name='Billing & Payments') limit 1")
if [ -z "$ESC" ]; then
  # no seeded REQUESTED escalation left — create one through the real workflow
  BILLT=$(Q "select id from tickets where team_id in (select id from teams where name='Billing & Payments') and status in ('OPEN','PENDING_CUSTOMER','PENDING_INTERNAL') limit 1")
  tl=$(curl -s -b $LJ "$CH/desk/tickets/$BILLT" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  curl -s -b $LJ -o /dev/null -X POST "$CH/desk/tickets/$BILLT/escalate" --data-urlencode "reason=Smoke: needs lead attention" --data-urlencode "slaException=false" --data-urlencode "_csrf=$tl"
  ESC=$(Q "select id from escalations where ticket_id='$BILLT' order by created_at desc limit 1")
fi
if [ -n "$ESC" ]; then
  t=$(curl -s -b $LJ "$CH/desk/escalations/$ESC" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  code=$(curl -s -b $LJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/escalations/$ESC/accept" --data-urlencode "_csrf=$t"); chk "accept escalation" "$code" "302"
  code=$(curl -s -b $LJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/escalations/$ESC/review" --data-urlencode "_csrf=$t"); chk "start review" "$code" "302"
  code=$(curl -s -b $LJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/escalations/$ESC/resolve" --data-urlencode "resolution=Fixed by retrying the webhook with backoff." --data-urlencode "resolveTicket=false" --data-urlencode "_csrf=$t"); chk "resolve escalation" "$code" "302"
else
  bad "no REQUESTED escalation found"
fi
# SLA report export (lead)
code=$(curl -s -b $LJ -o $JAR/sla.csv -w "%{http_code}" "$CH/desk/reports/export/sla"); chk "SLA export 200" "$code" "200"
grep -qiE "total_active|breached" $JAR/sla.csv && grep -qiE "^team,active,breached" $JAR/sla.csv && ok "SLA export has metric+team sections" || bad "SLA export sections missing"

echo "== 5. admin flows =="
ADJ=$JAR/admin.jar
curl -s -c $ADJ -o /dev/null "$CH/login"
p=$(curl -s -c $ADJ "$CH/login"); t=$(echo "$p" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b $ADJ -c $ADJ -o /dev/null -X POST "$CH/login" --data-urlencode "username=admin@relaydesk.test" --data-urlencode "password=Admin@Relay1" --data-urlencode "_csrf=$t"
for u in /admin /admin/users /admin/teams /admin/categories /admin/sla /admin/imports /admin/exports /admin/webhooks /admin/api-keys /admin/audit /admin/settings /admin/orgs; do
  code=$(curl -s -b $ADJ -o /dev/null -w "%{http_code}" "$CH$u"); [ "$code" = "200" ] && ok "admin page $u" || bad "admin page $u -> $code"
done
code=$(curl -s -b $ADJ -o $JAR/audit.csv -w "%{http_code}" "$CH/admin/exports/audit"); chk "audit export 200" "$code" "200"
AL=$(wc -l < $JAR/audit.csv | tr -d " "); [ "$AL" -ge 20 ] && ok "audit export has rows ($AL)" || bad "audit export rows: $AL"
# webhook attempts got recorded
Q "select count(*) from webhook_delivery_attempts" | grep -qvE "^\s*0\s*$" && ok "webhook attempts recorded" || bad "no webhook attempts"
# KB publish flow
KB=$(Q "select id from kb_articles where status='IN_REVIEW' limit 1")
if [ -n "$KB" ]; then
  t=$(curl -s -b $ADJ "$CH/desk/kb/$KB/edit" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  code=$(curl -s -b $ADJ -o /dev/null -w "%{http_code}" -X POST "$CH/desk/kb/$KB/publish" --data-urlencode "_csrf=$t"); chk "kb publish" "$code" "302"
else
  bad "no IN_REVIEW kb article"
fi
# public KB page + search fragment
code=$(curl -s -o /dev/null -w "%{http_code}" "$CH/kb"); chk "public kb 200" "$code" "200"
code=$(curl -s -o /dev/null -w "%{http_code}" "$CH/kb/search?q=invoice"); chk "kb search fragment 200" "$code" "200"
# API key flow
t=$(curl -s -b $ADJ "$CH/admin/api-keys" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
OWNER=$(Q "select id from users where email='admin@relaydesk.test'")
code=$(curl -s -b $ADJ -o /dev/null -w "%{http_code}" -X POST "$CH/admin/api-keys" --data-urlencode "name=smoke" --data-urlencode "ownerId=$OWNER" --data-urlencode "scopes=tickets:create" --data-urlencode "scopes=tickets:read" --data-urlencode "_csrf=$t"); chk "create api key" "$code" "302"
# find the new key via audit? simpler: create via DB check
KEYROW=$(Q "select key_hash from api_keys where name='smoke' limit 1")
[ -n "$KEYROW" ] && ok "api key stored (hashed)" || bad "api key not stored"
# API v1 without key -> 401
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CH/api/v1/tickets" -H "Content-Type: application/json" -d '{"subject":"x","description":"y","categoryId":"00000000-0000-0000-0000-000000000000"}'); chk "api without key -> 401" "$code" "401"

echo "== 6. attachments, etag, search, notifications =="
# customer downloads own customer-attachment; internal document denied
ATT=$(Q "select a.id from attachments a join tickets t on a.ticket_id=t.id where t.org_name='Acme Corp' and a.category='CUSTOMER_ATTACHMENT' limit 1")
if [ -n "$ATT" ]; then
  code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" "$CH/portal/tickets/attachments-redirect/$ATT")
  # route uses ticket id path; find ticket id for the attachment
  TIDA=$(Q "select a.ticket_id from attachments a where a.id='$ATT'")
  code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" "$CH/portal/tickets/$TIDA/attachments/$ATT")
  [ "$code" = "200" ] && ok "customer downloads own attachment ($code)" || bad "customer attachment download -> $code"
else
  ok "no acme customer attachment seeded (skipped)"
fi
INTDOC=$(Q "select a.id from attachments a where a.category='INTERNAL_DOCUMENT' limit 1")
if [ -n "$INTDOC" ]; then
  TIDI=$(Q "select a.ticket_id from attachments a where a.id='$INTDOC'")
  code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" "$CH/portal/tickets/$TIDI/attachments/$INTDOC")
  [ "$code" = "403" ] || [ "$code" = "404" ] && ok "customer blocked from internal document ($code)" || bad "internal document leaked to customer ($code)"
fi
# staff download of internal document works
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/desk/tickets/$TIDI/attachments/$INTDOC")
[ "$code" = "200" ] && ok "agent downloads internal document ($code)" || bad "agent internal document download -> $code"
# ETag endpoint
ET=$(curl -s -b $AJ -D $JAR/etag.h -o /dev/null "$CH/desk/ref/categories")
ETAG=$(grep -i '^ETag:' $JAR/etag.h | tr -d '\r' | cut -d' ' -f2)
[ -n "$ETAG" ] && ok "categories ETag present ($ETAG)" || bad "no ETag on /desk/ref/categories"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" -H "If-None-Match: $ETAG" "$CH/desk/ref/categories")
[ "$code" = "304" ] && ok "ETag 304 round-trip" || bad "ETag If-None-Match -> $code"
# ticket search: in-scope ref resolves, out-of-scope ref falls back to queue (no leak)
PLATREF=$(Q "select ref from tickets where team_id in (select id from teams where name='Platform Engineering') limit 1")
loc=$(curl -s -b $AJ -o /dev/null -w "%{redirect_url}" "$CH/search/ticket?q=$PLATREF")
echo "$loc" | grep -q "/desk/tickets/" && ok "in-scope ticket search -> detail" || bad "in-scope ticket search -> $loc"
BILLREF=$(Q "select ref from tickets where team_id in (select id from teams where name='Billing & Payments') limit 1")
loc=$(curl -s -b $AJ -o /dev/null -w "%{redirect_url}" "$CH/search/ticket?q=$BILLREF")
echo "$loc" | grep -q "/desk/queue" && ok "out-of-scope ticket search falls back to queue (no leak)" || bad "out-of-scope ticket search -> $loc"
# notifications fragment for customer + agent
code=$(curl -s -b $CJ -o /dev/null -w "%{http_code}" "$CH/notifications/fragment"); [ "$code" = "200" ] && ok "customer notifications fragment" || bad "customer notifications fragment -> $code"
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/notifications/fragment"); [ "$code" = "200" ] && ok "agent notifications fragment" || bad "agent notifications fragment -> $code"


echo "== 8. import, password reset, invite, API v1 =="
# --- CSV import (customers) ---
t=$(curl -s -b $ADJ "$CH/admin/imports" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
cat > $JAR/imp.csv <<CSV
organization_name,plan,address,contact_first_name,contact_last_name,contact_email,contact_phone,contact_title
Smoke Test Org,Growth,"1 Test Lane, Springfield",Terry,Kim,terry.kim@smoketestorg.test,+1-555-0199,IT Manager
Acme Corp,Enterprise,"100 Market Street, Springfield",New,Contact,new.contact@acme-corp.test,+1-555-0188,Support Contact
CSV
code=$(curl -s -b $ADJ -o /dev/null -w "%{http_code}" -X POST "$CH/admin/imports/run" -F "_csrf=$t" -F "kind=CUSTOMERS" -F "file=@$JAR/imp.csv;type=text/csv"); chk "import run" "$code" "302"
Q "select rows_ok from import_jobs where kind='CUSTOMERS' order by created_at desc limit 1" | grep -q 2 && ok "import job recorded 2 ok rows" || bad "import job rows_ok != 2"
code=$(curl -s -b $ADJ -o /dev/null -w "%{http_code}" "$CH/admin/orgs?q=Smoke"); chk "imported org visible in directory" "$code" "200"
# --- password reset (on a non-demo customer so demo creds stay intact) ---
RESETMAIL=$(Q "select email from users where role='CUSTOMER_USER' and email not in ('jordan.lee@acme-corp.test') limit 1")
t=$(curl -s -c $JAR/reset.jar "$CH/forgot-password" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
R=$(curl -s -b $JAR/reset.jar -c $JAR/reset.jar -X POST "$CH/forgot-password" --data-urlencode "email=$RESETMAIL" --data-urlencode "_csrf=$t")
LINK=$(echo "$R" | grep -oE "/reset-password\?token=[A-Za-z0-9_-]+" | head -1)
[ -n "$LINK" ] && ok "reset link issued (mock email preview)" || bad "no reset link preview"
tr=$(curl -s -b $JAR/reset.jar "$CH$LINK" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
code=$(curl -s -b $JAR/reset.jar -o /dev/null -w "%{http_code}" -X POST "$CH$LINK" --data-urlencode "password=NewPassword!42" --data-urlencode "confirmPassword=NewPassword!42" --data-urlencode "_csrf=$tr"); chk "reset password post" "$code" "302"
code=$(curl -s -b $JAR/reset.jar -o /dev/null -w "%{http_code}" -X POST "$CH$LINK" --data-urlencode "password=OtherPassword!42" --data-urlencode "confirmPassword=OtherPassword!42" --data-urlencode "_csrf=$tr"); [ "$code" = "200" ] && ok "reset token is single-use (rejected)" || bad "reset token reusable ($code)"
# --- invite a customer ---
INVITE_EMAIL=smoke.invitee@acme-corp.test
if [ "$(Q "select count(*) from users where email='$INVITE_EMAIL'")" = "0" ]; then
  ORGID=$(curl -s -b $ADJ "$CH/admin/orgs?q=Acme" | grep -oE "/admin/orgs/[0-9a-f-]{36}" | head -1 | sed "s|/admin/orgs/||")
  t=$(curl -s -b $ADJ "$CH/admin/users" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  INV=$(curl -s -b $ADJ -c $JAR/inv.jar -X POST "$CH/admin/users/invite" --data-urlencode "email=$INVITE_EMAIL" --data-urlencode "displayName=Smoke Invitee" --data-urlencode "orgId=$ORGID" --data-urlencode "_csrf=$t" -D $JAR/inv.h -o /dev/null)
  LINK=$(curl -s -b $JAR/inv.jar "$CH/admin/users" | grep -oE "/invite/[A-Za-z0-9_-]+" | head -1)
  [ -n "$LINK" ] && ok "invite issued with accept link" || bad "no invite link in flash"
  ti=$(curl -s -c $JAR/inv.jar "$CH$LINK" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
  code=$(curl -s -b $JAR/inv.jar -c $JAR/inv.jar -o /dev/null -w "%{http_code}" -X POST "$CH$LINK" --data-urlencode "password=InviteePass!42" --data-urlencode "confirmPassword=InviteePass!42" --data-urlencode "_csrf=$ti"); chk "invite accept" "$code" "302"
else
  ok "invitee already exists (skipped)"
fi
# --- API v1 with a real key ---
KEYNAME="smoke-api-key-$RANDOM"
OWNER=$(Q "select id from users where email='admin@relaydesk.test'")
t=$(curl -s -b $ADJ -c $JAR/key.jar "$CH/admin/api-keys" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' | head -1)
curl -s -b $JAR/key.jar -c $JAR/key.jar -o /dev/null -X POST "$CH/admin/api-keys" --data-urlencode "name=$KEYNAME" --data-urlencode "ownerId=$OWNER" --data-urlencode "scopes=tickets:create" --data-urlencode "scopes=tickets:read" --data-urlencode "_csrf=$t"
RAWKEY=$(curl -s -b $JAR/key.jar -c $JAR/key.jar "$CH/admin/api-keys" | grep -oE "rdk_[A-Za-z0-9]{32}" | head -1)
if [ -n "$RAWKEY" ]; then
  ok "api key plaintext shown once"
  ORGAPI=$(curl -s -b $ADJ "$CH/admin/orgs?q=Acme" | grep -oE "/admin/orgs/[0-9a-f-]{36}" | head -1 | sed 's|/admin/orgs/||')
  CATAPI=$(Q "select id from categories where name='Platform / Technical'")
  API=$(curl -s -X POST "$CH/api/v1/tickets" -H "Authorization: Bearer $RAWKEY" -H "Content-Type: application/json" -d "{\"orgId\":\"$ORGAPI\",\"subject\":\"API smoke ticket\",\"description\":\"Created through the JSON API.\",\"categoryId\":\"$CATAPI\",\"priority\":\"HIGH\"}" -w "\n%{http_code}")
  CODE=$(echo "$API" | tail -1)
  REF=$(echo "$API" | head -1 | grep -oE '\"ref\":\"[^"]*\"' | cut -d'"' -f4)
  [ "$CODE" = "201" ] && ok "API v1 create ticket ($REF)" || bad "API v1 create -> $CODE $API"
  code=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $RAWKEY" "$CH/api/v1/tickets/$REF"); chk "API v1 read ticket" "$code" "200"
  body=$(curl -s -H "Authorization: Bearer $RAWKEY" "$CH/api/v1/tickets/$REF")
  echo "$body" | grep -qiE "checked logs|internal note" && bad "internal notes leaked via API" || ok "API response has no internal notes"
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CH/api/v1/tickets" -H "Authorization: Bearer rdk_boguskey0000000000000000000000000000" -H "Content-Type: application/json" -d '{"subject":"x","description":"y","categoryId":"00000000-0000-0000-0000-000000000000"}'); chk "API rejects bogus key" "$code" "401"
else
  bad "could not extract api key plaintext"
fi

echo "== 7. session/decision check =="
code=$(curl -s -b $AJ -o /dev/null -w "%{http_code}" "$CH/desk"); chk "agent session still valid" "$code" "200"

echo; echo "RESULT: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
