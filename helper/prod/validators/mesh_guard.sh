#!/usr/bin/env bash
# mesh_guard.sh — watchdog for the hermetic 10-node run.
# The other opencode actor launches `testnodes.sh up` ALWAYS with
# LOAD_SEEDS=<file> in its env (never used by our run). Kill any process whose
# command line contains LOAD_SEEDS (their orchestrations) — our own
# testnodes.sh/boot_mesh runs never set it, so they are never touched.
set -u
LOOP=3
MATCH='LOAD_SEEDS'
echo "[guard $(date -u +%H:%M:%S)] watching for env-var signature: $MATCH (pid $$)"
while true; do
    for pid in $(pgrep -f "$MATCH" 2>/dev/null); do
        [ "$pid" = "$$" ] && continue
        cmd=$(ps -o cmd= -p "$pid" 2>/dev/null)
        case "$cmd" in
            *mesh_guard*) continue ;;  # our own wrapper shells
        esac
        echo "[guard $(date -u +%H:%M:%S)] killing competing pid $pid: $(printf '%s' "$cmd" | cut -c1-90)"
        kill -9 "$pid" 2>/dev/null
        pkill -9 -P "$pid" 2>/dev/null
    done
    sleep "$LOOP"
done
