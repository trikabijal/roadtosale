#!/usr/bin/env bash
# AuditPro backend — one-command restart.
#
# Usage:   ./bin/restart.sh
#
# What it does:
#   1. Kills any running JVM that's serving smartcomply-0.0.1-SNAPSHOT.war
#   2. Boots a fresh one with --spring.profiles.active=local in the background
#   3. Tails the log until "Started Application" appears (or fails)
#   4. Prints the port it bound, the PID, and a tiny health check
#
# Logs land in data/server.log. PID is held in data/server.pid.
# Survives terminal close (nohup + disown). To stop:  kill $(cat data/server.pid)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WAR="$ROOT/target/smartcomply-0.0.1-SNAPSHOT.war"
LOG_DIR="$ROOT/data"
LOG="$LOG_DIR/server.log"
PID="$LOG_DIR/server.pid"
PROFILE="${SPRING_PROFILE:-local}"

mkdir -p "$LOG_DIR"

echo "──────────────────────────────────────────────────────────────"
echo " AuditPro restart  ·  profile=$PROFILE"
echo "──────────────────────────────────────────────────────────────"

# 1. Stop any running instance — both via tracked PID and any orphans
#    that might still hold the port (e.g. from a previous nohup run).
if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; then
  OLD_PID="$(cat "$PID")"
  echo "Stopping old PID $OLD_PID..."
  kill "$OLD_PID" 2>/dev/null || true
  # Wait up to 10s for graceful shutdown, then SIGKILL.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$OLD_PID" 2>/dev/null || break
    sleep 1
  done
  kill -9 "$OLD_PID" 2>/dev/null || true
fi
# Belt-and-braces: kill any orphan JVM serving the same WAR.
ORPHANS="$(pgrep -f "smartcomply-0.0.1-SNAPSHOT.war" || true)"
if [ -n "$ORPHANS" ]; then
  echo "Killing orphan JVM(s): $ORPHANS"
  echo "$ORPHANS" | xargs kill -9 2>/dev/null || true
fi
rm -f "$PID"

# 2. Make sure the WAR exists.
if [ ! -f "$WAR" ]; then
  echo "WAR not found at $WAR — building first..."
  (cd "$ROOT" && ./mvnw package -DskipTests -q)
fi

# 3. Boot in the background, detach from this shell, redirect log.
echo "Starting fresh JVM..."
: > "$LOG"   # truncate so the readiness grep below only sees this run
nohup java -jar "$WAR" --spring.profiles.active="$PROFILE" >> "$LOG" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID"
disown "$NEW_PID" 2>/dev/null || true

# 4. Wait for readiness — up to 90s. Spring Boot writes a "Started Application"
#    line on success and "APPLICATION FAILED" on boot failure.
echo "Waiting for startup (PID $NEW_PID)..."
DEADLINE=$(( $(date +%s) + 90 ))
while :; do
  if grep -q "APPLICATION FAILED TO START" "$LOG" 2>/dev/null; then
    echo "❌ Startup failed — last 25 lines of $LOG:"
    tail -n 25 "$LOG"
    exit 1
  fi
  if grep -qE "Started Application in .* seconds" "$LOG" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$NEW_PID" 2>/dev/null; then
    echo "❌ JVM exited before startup completed — last 25 lines of $LOG:"
    tail -n 25 "$LOG"
    exit 1
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "❌ Startup timed out after 90s — last 25 lines of $LOG:"
    tail -n 25 "$LOG"
    exit 1
  fi
  sleep 1
done

# 5. Pull the bound port from the log and run a tiny smoke check.
PORT="$(grep -oE "Tomcat started on port\(s\): [0-9]+" "$LOG" | tail -n 1 | grep -oE "[0-9]+$" || true)"
[ -z "$PORT" ] && PORT="$(grep -oE "Tomcat started on port[^0-9]+[0-9]+" "$LOG" | tail -n 1 | grep -oE "[0-9]+$" || true)"
[ -z "$PORT" ] && PORT="8089"   # fallback to the configured default

LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<lan-ip>")"
HEALTH="$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/api/audit/list" -H "Authorization: Bearer x" || echo "??")"

cat <<EOF

✅ AuditPro backend is up.

   PID         : $NEW_PID  (tracked in $PID)
   Profile     : $PROFILE
   Port        : $PORT
   Local URL   : http://localhost:$PORT/api
   LAN URL     : http://$LAN_IP:$PORT/api
   Smoke check : HTTP $HEALTH on /api/audit/list  (401 = up + auth-gated, expected)
   Log         : $LOG

   Tail log    :  tail -f $LOG
   Stop        :  kill \$(cat $PID)

EOF
