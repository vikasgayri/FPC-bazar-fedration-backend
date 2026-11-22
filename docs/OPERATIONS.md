# Local running, email and backups

## Local development (recommended)

Requires Java 21 or newer, Python 3.11+, MongoDB Community Server (`mongod`) and MongoDB Database Tools for backups.

```sh
./mvnw verify
python3 scripts/check-env.py
python3 scripts/run-local.py
```

The API uses **http://localhost:8081** and a separate MongoDB at `127.0.0.1:27018/fpcbazaar_local`.
This runner overrides the Atlas URI and storage location, disables demo seeding and reads `LOCAL_MAIL_SANDBOX` from the environment or `.env`. A fresh setup defaults to sandbox; this configured laptop explicitly uses live email.
It does not mutate the configured Atlas database. Its files live in `data/local/` and survive restarts.
Use the configured `ADMIN_USERNAME` / `ADMIN_PASSWORD` for the first local admin. Bootstrap does not reset an existing admin.
Ctrl+C stops the API and the MongoDB child process. Port conflicts fail clearly; override `LOCAL_API_PORT` and
`LOCAL_MONGO_PORT` if needed. `MONGOD_BIN` can point to a custom MongoDB executable.

Frontend integration is configured in `/Users/vikasgayri/Downloads/FPC-Bazaar-Federation-main/.env.local` with `VITE_API_URL=http://localhost:8081`. Run `npm run dev -- --host 127.0.0.1` there. Browser checks cover contact submission, enquiry updates, vendor upload/review/public listing, protected downloads, Excel export, users, audit, mail status, first-login gate and Hindi NGO rendering.
`CORS_ORIGINS` must include the actual frontend origin.

To run against the **configured database** instead, use `python3 scripts/run.py`. This loads `.env` as data,
without executing shell code. The documented `MAIL_FROM` setting also pins Spring's `APP_MAIL_FROM` alias so an unrelated inherited alias cannot silently change the sender. `check-env.py` validates the effective configuration including shell overrides. It can connect to Atlas and create a bootstrap admin; use intentionally.
The backend binds to loopback by default. Do not use demo seeding on a shared/live database.

## Email configuration and diagnosis

Set `MAIL_ENABLED=true`, `BREVO_API_KEY`, `MAIL_FROM`, `MAIL_FROM_NAME`, `MAIL_ENCRYPTION_KEY`,
`PUBLIC_BASE_URL` and `FRONTEND_URL`. Generate the encryption key with `openssl rand -base64 32`.
The API key is used with the Brevo HTTPS API (it is not an SMTP password).
Keep the encryption key stable and separately backed up: restoring queued emails requires the same key.

```sh
python3 scripts/test-brevo.py --to YOUR_OWN_EMAIL
# Explicitly sends one email:
python3 scripts/test-brevo.py --to YOUR_OWN_EMAIL --send
# Explicitly allow delivery for all new events during a local run:
LOCAL_MAIL_SANDBOX=false python3 scripts/run-local.py
```

Sandbox validates requests but does not deliver mail. The runner reads `LOCAL_MAIL_SANDBOX` independently from `MAIL_SANDBOX`; shell environment overrides `.env`. The current `.env` has both set to false. Set `LOCAL_MAIL_SANDBOX=true` for synthetic test runs.
Notifications cover registration/enquiry receipts (where an email exists), verified/rejected decisions (including rejection reason),
email-based login creation, admin password resets and self-service reset links. Legacy username-only logins cannot receive email;
they remain admin-managed. Forms whose schema has no email field cannot receive an acknowledgement. SMS is not implemented or enabled.

The MongoDB outbox stores recipients and message content encrypted with AES-256-GCM. It retries network errors, 408, 429 and 5xx,
with exponential delays and a configurable attempt limit. Permanent failures remain visible to an admin.
Completed payloads are removed; job metadata and failed encrypted payloads expire after 30 days.
Credential emails stop retrying after their deadline. A temporary login password must be changed at first sign-in;
the 24-hour outbox deadline limits email delivery, not the underlying temporary password's lifetime.

Admin API (bearer token required, admin role only):

- `GET /api/admin/notifications?limit=100`: status, attempt count, safe error code and provider message ID; no recipient/body/password.
- `POST /api/admin/notifications/{id}/retry`: retries a failed, unexpired message and records the action in the audit log.

`accepted` means Brevo accepted the request, **not inbox delivery**. `sandboxed` means no delivery was attempted.
Use the provider message ID in Brevo transactional logs to distinguish delivery, bounce and spam outcomes.
Queue leases recover after a process crash. Delivery is at-least-once: a crash after provider acceptance can cause a duplicate email.
Saving a business record and queuing its notification are not a distributed transaction. If MongoDB fails between those operations,
the business action stays saved; the application logs the queue failure. Review/reissue credential emails through an admin reset.

## Password reset API and built-in page

1. `POST /api/auth/forgot-password` with `{ "username": "person@example.org" }`.
2. The email links to `PUBLIC_BASE_URL/reset-password#token=...` (served by this backend).
3. The page posts `{ "token": "...", "newPassword": "..." }` to `POST /api/auth/reset-password`.

Unknown, disabled and throttled account lookups share the same public response. Requests have IP/account limits.
Tokens are 32 random bytes; only their SHA-256 hash is stored in the user document. They expire after 15 minutes by default,
are consumed atomically and cannot be reused. Password reset invalidates old sessions. Admin resets, account deactivation and
other token-version changes invalidate outstanding reset links. The page removes the token from the address bar, disables caching
and referrers, and uses a restrictive Content Security Policy. No external assets are loaded.

## Backups and restore

```sh
# For the isolated local database, override MONGODB_URI and STORAGE_DIR explicitly:
MONGODB_URI=mongodb://127.0.0.1:27018/fpcbazaar_local \
STORAGE_DIR="$PWD/data/local/uploads" python3 scripts/backup.py --check
```

For a consistent database/files backup, stop **all API writers** while keeping MongoDB running, then run
`python3 scripts/backup.py --maintenance-confirmed`. The script requires a named database, creates a restricted-permission
MongoDB archive and copy of uploads, and writes a SHA-256 manifest. An incomplete dump is not published as a completed backup.
A backup does not contain `.env` or encryption keys. Protect those separately in a secrets manager.

`BACKUP_DIR` should be on a separate disk/storage location. Local backups contain personal data and are not encrypted by this script.
Configure `RESTIC_REPOSITORY`, `RESTIC_PASSWORD_FILE` and provider credentials for an encrypted offsite copy. Install and initialize
restic first. No offsite repository has been configured or tested in this project. After each successful backup, local backups older
than `BACKUP_KEEP_DAYS` (default 14) are deleted, always keeping the newest 3; only folders named `fpc-<timestamp>Z` are touched.
Offsite (restic) retention is separate: set it with `restic forget --keep-daily ...` when the offsite storage is chosen.

```sh
python3 scripts/verify-backup.py /absolute/path/to/backup
# Always test into a NEW database first; this example restores the local-development namespace:
mongorestore --uri=mongodb://127.0.0.1:27018 --gzip \
  --archive=/absolute/path/to/backup/mongodb.archive.gz \
  --nsFrom='fpcbazaar_local.*' --nsTo='fpcbazaar_restorecheck.*'
```

For Atlas credentials, put `uri: "mongodb+srv://..."` in a mode-0600 temporary YAML file and use `--config=...` so
credentials do not appear in process arguments. Adapt the source namespace to the backed-up database. Restore uploads into a new
empty directory, compare checksums and database counts, and only then switch the application's URI/storage to the restored copies.
Do not use `--drop` against the live database as a routine restore check.

## Deployment later

Deployment is awaiting server IP/SSH access, domains/DNS and offsite backup configuration. `deploy/` contains **uninstalled templates**, not an active deployment:

- `fpc-bazaar.service`: dedicated service user, loopback API, restricted writes under `/var/lib/fpc-bazaar`.
- `nginx.conf.example`: domain placeholder, safe access log omitting query tokens, explicit proxy IP headers.
- `fpc-backup.service` / `.timer`: daily 02:30 India-time backup with a short API maintenance window; restarts the API even if backup fails,
  and stops a hung backup after 30 minutes (`TimeoutStartSec`) so the website cannot stay down.

Before installing: create the service user/directories, install Java 21 and Python 3.11+, copy the JAR and scripts under
`/opt/fpc-bazaar`, create `/etc/fpc-bazaar/backend.env`, set absolute `STORAGE_DIR`/`BACKUP_DIR`, real HTTPS URLs and origins,
configure DNS/TLS, and select/verify offsite backup storage. The environment file is read by systemd; quote values containing spaces.
Keep MongoDB private or use Atlas network restrictions. Replace the nginx placeholder and add HTTPS before exposing the API.
Use `SEED_DEMO=false` and `MAIL_SANDBOX=false` on the intended live server.

Existing demo accounts are not disabled by turning seeding off. On 2026-11-21, the five known Atlas demo accounts were verified against their original default credentials and disabled; their token versions were incremented. Zero demo accounts remain active and the configured real admin remains active. Records/history were retained. The in-memory rate limiter and last-admin update lock assume one API process; use a shared limiter and transactional admin changes before scaling to multiple instances.
The new unique account-per-registration index was checked
against Atlas data: no duplicate linked accounts were found at inspection time.

## Provider references

- Brevo transactional API: https://developers.brevo.com/reference/send-transac-email
- Sandbox behavior: https://developers.brevo.com/docs/using-sandbox-mode
- Delivery events: https://developers.brevo.com/reference/get-email-event-report
- Delivered vs inbox placement: https://help.brevo.com/hc/en-us/articles/35699922048146-View-and-export-your-event-logs
