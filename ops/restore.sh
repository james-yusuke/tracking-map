#!/bin/sh
set -eu

if [ "$#" -ne 1 ] || [ -z "${BACKUP_PASSPHRASE:-}" ] || [ -z "${MONGODB_URI:-}" ]; then
  echo "Usage: BACKUP_PASSPHRASE=... MONGODB_URI=... $0 backups/file.archive.gz.enc" >&2
  exit 1
fi

openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE -in "$1" | \
  mongorestore --uri="${MONGODB_URI}" --archive --gzip --drop

