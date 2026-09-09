#!/usr/bin/env bash
# Container entrypoint: one-time setup, then hand off to crond in the foreground so the
# container's lifecycle IS the cron daemon's (no separate supervisor needed).
set -euo pipefail

# dcron jobs run with a bare environment (HOME/PATH/LOGNAME only) - none of the NAS_*/PG*/
# DB_DUMP_RETENTION_DAYS variables docker-compose.yml sets on this container would otherwise be
# visible inside backup.sh when cron invokes it. Snapshot the environment once, here, while it's
# still fully populated, and have backup.sh source it back in. Compose recreates this container on
# any env change anyway, so a one-time snapshot at startup never goes stale.
env | grep -Ev '^(HOME|PATH|PWD|SHLVL|_)=' > /etc/nas-backup.env
chmod 0600 /etc/nas-backup.env

# TZ (default UTC) drives when "2am" actually is - dcron reads /etc/localtime, it doesn't look at
# $TZ itself.
if [ -n "${TZ:-}" ] && [ -f "/usr/share/zoneinfo/${TZ}" ]; then
    ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime
    echo "${TZ}" > /etc/timezone
fi

# The mounted private key needs to be group/world-unreadable or ssh refuses it outright
# ("UNPROTECTED PRIVATE KEY FILE") - a bind mount from the host commonly carries the host's own
# (looser) permissions, so copy it into a container-local, correctly-permissioned location rather
# than relying on the operator to chmod the host file just right.
if [ -f /run/secrets/nas_ssh_key ]; then
    install -m 0600 -D /run/secrets/nas_ssh_key /root/.ssh/nas_ssh_key
fi

# Optional immediate run for testing a fresh setup without waiting for 2am - off by default so
# the schedule below is the only trigger in normal operation.
if [ "${RUN_BACKUP_ON_STARTUP:-false}" = "true" ]; then
    /usr/local/bin/backup.sh || true
fi

echo "nas-backup: crond starting, schedule: $(grep -v '^#' /etc/crontabs/root)"
# -f: stay in the foreground, so the container's lifecycle is the cron daemon's. Deliberately no
# -l/-d loglevel flag - dcron and busybox crond give that flag incompatible meanings (syslog
# facility vs. debug level), and backup.sh's own output already goes to cron.log via the crontab
# entry's redirection, so crond's own logging verbosity doesn't matter here.
exec crond -f
