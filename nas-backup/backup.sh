#!/usr/bin/env bash
# Nightly job (see ../crontab, 02:00): mirrors /data/iphoto (the iphoto-storage volume - originals,
# thumbnails, everything iphoto-app owns) and a fresh pg_dump of the iphoto database to a Synology
# NAS on the same LAN, over rsync-over-SSH, then writes a report of what happened.
#
# Two very different retention rules, both by design:
#   - photo/video assets: NEVER removed from the NAS side, even if a file disappears locally
#     (rsync below has no --delete for this leg) - iphoto itself never deletes originals either
#     (see Dockerfile's iphoto-logs comment), so this backup shouldn't silently start deleting the
#     one copy that would let you undo a mistake.
#   - Postgres dumps: a gzipped plain-text dump is created locally, moved to the NAS, and the
#     local copy is removed. Dumps older than DB_DUMP_RETENTION_DAYS (default 30) are pruned
#     from the NAS.
#
# To restore a database dump:
#   1. Copy the desired dump file from the NAS (e.g., iphoto-2026-10-11_020000.sql.gz).
#   2. Run the following command, replacing placeholders for your environment. This example
#      uses `docker exec` to run psql inside the running database container (likely 'iphoto-db').
#      gunzip -c iphoto-2026-10-11_020000.sql.gz | docker exec -i iphoto-db psql -U "${PGUSER}" -d "${PGDATABASE}"
#
#   3. Alternatively, if connecting directly to the database from a machine with psql:
#      gunzip -c iphoto-2026-10-11_020000.sql.gz | psql -h "${PGHOST}" -p "${PGPORT:-5432}" -U "${PGUSER}" -d "${PGDATABASE}"
#
set -uo pipefail

# --- config / validation -----------------------------------------------------------------------
# Cron invokes this script with a near-empty environment - pull back the snapshot entrypoint.sh
# took of the container's real environment at startup, when running under cron. Interactively
# (docker exec, RUN_BACKUP_ON_STARTUP) the variables are already in the environment, so only
# source the file if it's actually there.
if [ -f /etc/nas-backup.env ]; then
    set -a
    # shellcheck disable=SC1091
    source /etc/nas-backup.env
    set +a
fi

: "${NAS_HOST:?NAS_HOST must be set (Synology hostname/IP on the LAN)}"
: "${NAS_USER:?NAS_USER must be set (SSH user on the NAS)}"
: "${NAS_ASSETS_PATH:?NAS_ASSETS_PATH must be set (remote path for photo/video assets)}"
: "${NAS_DB_DUMPS_PATH:?NAS_DB_DUMPS_PATH must be set (remote path for postgres dumps)}"
: "${NAS_REPORTS_PATH:?NAS_REPORTS_PATH must be set (remote path for run reports)}"
: "${PGHOST:?PGHOST must be set (iphoto-db service name)}"
: "${PGUSER:?PGUSER must be set}"
: "${PGDATABASE:?PGDATABASE must be set}"
: "${PGPASSWORD:?PGPASSWORD must be set}"

NAS_SSH_PORT="${NAS_SSH_PORT:-22}"
DB_DUMP_RETENTION_DAYS="${DB_DUMP_RETENTION_DAYS:-30}"
SSH_KEY="/root/.ssh/nas_ssh_key"
ASSETS_SRC="/data/iphoto"
DUMP_DIR="/backup/db-dumps"
REPORT_DIR="/backup/reports"

RUN_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUN_STAMP="$(date -u +%Y-%m-%d_%H%M%S)"
DUMP_FILE="${DUMP_DIR}/iphoto-${RUN_STAMP}.sql.gz"
REPORT_FILE="${REPORT_DIR}/report-${RUN_STAMP}.txt"
ASSETS_RSYNC_LOG="$(mktemp)"

SSH_OPTS=(-p "${NAS_SSH_PORT}" -o BatchMode=yes -o ConnectTimeout=15)
if [ -f "${SSH_KEY}" ]; then
    SSH_OPTS+=(-i "${SSH_KEY}")
fi
# accept-new (not "no"): trusts the NAS's host key the first time this container ever connects to
# it and pins it for every run after, so a genuinely different host key later (the actual MITM
# scenario StrictHostKeyChecking guards against) still fails loudly instead of being silently
# accepted.
SSH_OPTS+=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/root/.ssh/known_hosts)
mkdir -p /root/.ssh && chmod 0700 /root/.ssh

RSYNC_SSH="ssh ${SSH_OPTS[*]}"
REMOTE="${NAS_USER}@${NAS_HOST}"

STATUS=0
{
    echo "iphoto NAS backup report"
    echo "run started: ${RUN_TS}"
    echo "nas host:    ${NAS_HOST}:${NAS_SSH_PORT}"
    echo
} > "${REPORT_FILE}"

fail() {
    echo "ERROR: $*" | tee -a "${REPORT_FILE}" >&2
    STATUS=1
}

# --- 1. sync photo/video assets to the NAS (never deletes on the remote side) -------------------
echo "== assets sync ==" | tee -a "${REPORT_FILE}"
mkdir -p "${DUMP_DIR}" "${REPORT_DIR}"

if ssh "${SSH_OPTS[@]}" "${REMOTE}" "mkdir -p '${NAS_ASSETS_PATH}' '${NAS_DB_DUMPS_PATH}' '${NAS_REPORTS_PATH}'"; then
    if rsync -a --human-readable --stats \
            -e "${RSYNC_SSH}" \
            "${ASSETS_SRC}/" "${REMOTE}:${NAS_ASSETS_PATH}/" \
            > "${ASSETS_RSYNC_LOG}" 2>&1; then
        grep -E "Number of (regular files transferred|created files|deleted files)|Total transferred file size|Total file size" \
            "${ASSETS_RSYNC_LOG}" >> "${REPORT_FILE}" || true
        echo "assets sync: OK" >> "${REPORT_FILE}"
    else
        cat "${ASSETS_RSYNC_LOG}" >> "${REPORT_FILE}"
        fail "rsync of ${ASSETS_SRC} to ${REMOTE}:${NAS_ASSETS_PATH} failed"
    fi
else
    fail "could not reach ${REMOTE} over SSH to create remote backup directories"
fi
echo >> "${REPORT_FILE}"
rm -f "${ASSETS_RSYNC_LOG}"

# --- 2. dump the full iphoto database -----------------------------------------------------------
echo "== database dump ==" | tee -a "${REPORT_FILE}"
export PGPASSWORD
# Create a plain-text SQL dump and compress it with gzip.
if pg_dump -h "${PGHOST}" -p "${PGPORT:-5432}" -U "${PGUSER}" -d "${PGDATABASE}" | gzip > "${DUMP_FILE}"; then
    DUMP_SIZE="$(du -h "${DUMP_FILE}" | cut -f1)"
    echo "dump file: ${DUMP_FILE} (${DUMP_SIZE})" >> "${REPORT_FILE}"
else
    fail "pg_dump of database '${PGDATABASE}' on ${PGHOST} failed"
    rm -f "${DUMP_FILE}"
fi
echo >> "${REPORT_FILE}"

# --- 3. sync dump to NAS, then prune old dumps --------------------------------------------------
echo "== database sync and prune ==" | tee -a "${REPORT_FILE}"

# Sync the new dump to the NAS. If successful, remove the local copy.
if rsync -a --human-readable \
        -e "${RSYNC_SSH}" \
        "${DUMP_FILE}" "${REMOTE}:${NAS_DB_DUMPS_PATH}/" >> "${REPORT_FILE}" 2>&1; then
    echo "dump sync to NAS: OK" >> "${REPORT_FILE}"
    rm -f "${DUMP_FILE}"
else
    fail "rsync of ${DUMP_FILE} to ${REMOTE}:${NAS_DB_DUMPS_PATH} failed"
fi

# Prune old dumps from the NAS directly.
echo "pruning old dumps on NAS (older than ${DB_DUMP_RETENTION_DAYS} days)..." >> "${REPORT_FILE}"
SSH_CMD_STR="find '${NAS_DB_DUMPS_PATH}' -maxdepth 1 -name 'iphoto-*.sql.gz' -mtime '+${DB_DUMP_RETENTION_DAYS}' -print -delete"
PRUNED_REMOTE="$(ssh "${SSH_OPTS[@]}" "${REMOTE}" "${SSH_CMD_STR}")"
SSH_EXIT_CODE=$?

if [ ${SSH_EXIT_CODE} -eq 0 ]; then
    if [ -n "${PRUNED_REMOTE}" ]; then
        echo "pruned from NAS:" >> "${REPORT_FILE}"
        echo "${PRUNED_REMOTE}" | sed 's/^/  /' >> "${REPORT_FILE}"
    else
        echo "nothing to prune on NAS" >> "${REPORT_FILE}"
    fi
else
    # NB: `find` can exit with a non-zero status for various reasons, but with `-delete`,
    # a non-zero exit usually indicates a more significant error than "not found".
    fail "remote pruning on ${REMOTE} failed with exit code ${SSH_EXIT_CODE}"
fi
echo >> "${REPORT_FILE}"

# --- 4. Report Retention ------------------------------------------------------------------------
{
    echo "== report retention =="
    echo
    echo "--- local reports ---"
    echo "pruning local reports older than 10 days..."
    PRUNED_LOCAL_REPORTS="$(find "${REPORT_DIR}" -maxdepth 1 -name 'report-*.txt' -mtime +9 -print -delete)"
    if [ -n "${PRUNED_LOCAL_REPORTS}" ]; then
        echo "pruned from local server:"
        echo "${PRUNED_LOCAL_REPORTS}" | sed 's/^/  /'
    else
        echo "nothing to prune locally"
    fi
    echo

    echo "--- remote reports (NAS) ---"
    echo "gzipping reports older than 10 days, deleting reports older than 60 days..."
    REMOTE_CMD="
find '${NAS_REPORTS_PATH}' -maxdepth 1 -name 'report-*.txt' -mtime +9 -print -exec gzip {} +;
find '${NAS_REPORTS_PATH}' -maxdepth 1 -name 'report-*.txt*' -mtime +59 -print -delete;
"
    REMOTE_RETENTION_OUTPUT="$(ssh "${SSH_OPTS[@]}" "${REMOTE}" "${REMOTE_CMD}")"
    SSH_EXIT_CODE=${?}

    if [ ${SSH_EXIT_CODE} -eq 0 ]; then
        if [ -n "${REMOTE_RETENTION_OUTPUT}" ]; then
            echo "actions on NAS:"
            echo "${REMOTE_RETENTION_OUTPUT}" | sed 's/^/  /'
        else
            echo "no reports to gzip or delete on NAS"
        fi
    else
        echo "ERROR: remote report management on ${REMOTE} failed with exit code ${SSH_EXIT_CODE}"
        # Not using fail() here because we want to finalize the report first.
        STATUS=1
    fi
} >> "${REPORT_FILE}"
echo >> "${REPORT_FILE}"


# --- 5. Finalize and ship report ----------------------------------------------------------------
{
    echo "== summary =="
    echo "run finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if [ "${STATUS}" -eq 0 ]; then
        echo "overall status: SUCCESS"
    else
        echo "overall status: FAILED (see errors above)"
    fi
} >> "${REPORT_FILE}"

# Output the full report to the container's logs
cat "${REPORT_FILE}"

# Finally, copy the report to the NAS.
if ! rsync -a -e "${RSYNC_SSH}" "${REPORT_FILE}" "${REMOTE}:${NAS_REPORTS_PATH}/"; then
    echo "WARNING: failed to copy report to ${REMOTE}:${NAS_REPORTS_PATH}" >&2
    # If this fails, the overall run is still a success if everything else worked.
    # The error is logged, which is sufficient.
fi

exit "${STATUS}"
