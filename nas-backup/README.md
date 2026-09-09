# nas-backup

Sidecar container for `docker-compose.yml`'s `iphoto-nas-backup` service. Every night at 02:00
(container-local time, see `crontab`) it:

1. `rsync`s the entire `iphoto-storage` volume (everything under `/data/iphoto` - originals,
   thumbnails, everything `iphoto-app` owns) to a Synology NAS on the same LAN, over SSH.
   **Never deletes anything on the NAS side** for this leg, even if a file has disappeared
   locally - if you want a file gone from the backup too, remove it manually on the NAS.
2. Runs `pg_dump` against `iphoto-db` and copies the dump to the NAS as well.
3. Prunes dumps older than `DB_DUMP_RETENTION_DAYS` (default 30), both locally and on the NAS -
   this is the *only* place this container ever deletes anything on the NAS, and only inside the
   dump folder.
4. Writes a text report of what happened (files/bytes transferred, dump size, what got pruned,
   overall success/failure) to `/backup/reports` and copies it to the NAS too.

## One-time setup

1. **Create a dedicated SSH user on the Synology** (Control Panel → User & Group), e.g.
   `iphoto-backup`, with write access to wherever you want the backup to live (e.g. a
   `iphoto-backup` shared folder). Enable SSH on the NAS (Control Panel → Terminal & SNMP) if it
   isn't already.

2. **Generate a dedicated, passphrase-less key pair** on the Docker host (cron can't prompt for a
   passphrase):

   ```bash
   ssh-keygen -t ed25519 -N "" -f nas-backup/secrets/id_ed25519
   ```

   `nas-backup/secrets/` is gitignored - the key never gets committed. Copy the public half to the
   NAS user's `~/.ssh/authorized_keys` (or use Synology's own UI for it, if offered).

3. **Verify it works** before relying on cron:

   ```bash
   ssh -i nas-backup/secrets/id_ed25519 iphoto-backup@nas.local true && echo OK
   ```

4. **Fill in `.env`** (see `.env.example` for the full list): `NAS_HOST`, `NAS_USER`, and
   optionally `NAS_SSH_PORT`, `NAS_ASSETS_PATH` / `NAS_DB_DUMPS_PATH` / `NAS_REPORTS_PATH`,
   `DB_DUMP_RETENTION_DAYS`, `BACKUP_TZ`.

5. `docker compose up -d --build iphoto-nas-backup`.

The remote directories (`NAS_ASSETS_PATH`, `NAS_DB_DUMPS_PATH`, `NAS_REPORTS_PATH`) are created
automatically on first run if they don't already exist - the NAS user just needs write permission
on their parent.

## Testing without waiting for 2am

Either run the script directly:

```bash
docker compose exec iphoto-nas-backup /usr/local/bin/backup.sh
```

or set `RUN_BACKUP_ON_STARTUP=true` in the service's environment and recreate the container to
have it run once immediately in addition to the nightly schedule.

## Restoring a dump

The dump is written with `pg_dump -Fc` (custom format), so restore with `pg_restore`, not `psql`:

```bash
pg_restore -h <host> -U iphoto -d iphoto --clean --if-exists /path/to/iphoto-<timestamp>.dump
```
