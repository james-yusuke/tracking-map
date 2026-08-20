#!/bin/sh
set -eu

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="/backups/family-orbit-${timestamp}.archive.gz"

if [ -z "${BACKUP_PASSPHRASE:-}" ]; then
  echo "BACKUP_PASSPHRASE is required" >&2
  exit 1
fi

mongodump --uri="${MONGODB_URI}" --archive --gzip | \
  openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_PASSPHRASE -out "${archive}.enc"
find /backups -type f -name '*.enc' -mtime +7 -delete
echo "Encrypted backup created: ${archive}.enc"

