# syntax=docker/dockerfile:1
#
# Standalone sidecar image: nightly sync of the iphoto photo/video assets (iphoto-storage volume,
# see docker-compose.yml) and a Postgres dump (iphoto-db) to a Synology NAS on the same LAN, over
# rsync-over-SSH. Runs as its own container next to iphoto-app/iphoto-db - it never touches the
# app image or jar, just reads the shared volume and talks to iphoto-db over the compose network.
#
# postgresql16-client (not 15/17) on purpose - must match the major version of the postgres:16-alpine
# server image iphoto-db runs (see docker-compose.yml), since pg_dump refuses to talk to a server
# newer than itself and its dump format can be version-sensitive.
FROM alpine:3.20

RUN apk add --no-cache \
        bash \
        rsync \
        openssh-client \
        postgresql16-client \
        dcron \
        tzdata \
        coreutils \
        findutils \
        gzip

COPY backup.sh /usr/local/bin/backup.sh
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY crontab /etc/crontabs/root
RUN chmod 0755 /usr/local/bin/backup.sh /usr/local/bin/entrypoint.sh \
    && chmod 0600 /etc/crontabs/root \
    && mkdir -p /backup/db-dumps /backup/reports /var/log/nas-backup

# Mount points:
#  /data/iphoto        - read-only, the same named volume iphoto-app writes originals/thumbnails
#                         into (iphoto-storage in docker-compose.yml) - this container only ever
#                         reads from it, it has no business writing here.
#  /backup/db-dumps     - local staging + retention window for pg_dump output before/after it's
#                         mirrored to the NAS (see DB_DUMP_RETENTION_DAYS in backup.sh).
#  /backup/reports      - one text report per run, also mirrored to the NAS.
#  /run/secrets/nas_ssh_key - private key for the NAS SSH user, bind-mounted read-only from the
#                         host (never baked into the image or committed - see nas-backup/README.md).
VOLUME ["/data/iphoto", "/backup/db-dumps", "/backup/reports"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
