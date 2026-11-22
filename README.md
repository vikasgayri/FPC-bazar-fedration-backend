# FPC Bazaar Federation – Backend API

Java 21 · Spring Boot 4 · MongoDB Atlas. Frontend: `FPC-Bazaar-Federation-main` (contract in its `docs/ADMIN_API.md`).

## Status
- **Step 1 (done):** project setup, MongoDB connection, all settings from environment, login with roles
  (Admin > State Head > Project Head > Vendor > FPO), first admin created automatically, one JSON error format.
- **Step 2 (done):** all public forms (`POST /api/forms/{formKey}`), document upload (PDF/JPG/PNG, 5 MB),
  registrations with Pending / Verified / Rejected + reason + audit history, role scope on every admin call,
  public verified vendors (`/vendor/{slug}`) and verified FPOs.
- **Step 3 (done):** login management (State Head / Project Head / vendor / FPO), one-time passwords that must be
  changed at first sign-in, vendor / FPO login created automatically on verify (username = their email), NGO form,
  enquiries inbox, Excel export, dashboard numbers, live state counts for the map, activity log.
- **Step 4 (local implementation complete):** Brevo email notifications with encrypted retry queue,
  password-reset API and page, local backup/restore tooling. SMS remains off. Server deployment awaits server/SSH/domain details.
  See [operations guide](docs/OPERATIONS.md) for configuration, limitations and deployment templates.

## Run on your laptop
Needs Java 21 (`java -version`). Maven is not needed, the wrapper downloads it.

```bash
cp .env.example .env          # only on first setup; do not overwrite an existing .env
# Fill configuration, then:
./mvnw verify
python3 scripts/check-env.py
python3 scripts/run-local.py  # isolated MongoDB + API on http://localhost:8081
```

Local running also needs Python 3.11+ and MongoDB Community Server (`mongod`).
The runner keeps Atlas unchanged. `LOCAL_MAIL_SANDBOX` controls local delivery; the current private `.env` explicitly sets it to `false` for real email. A fresh setup defaults to sandbox.
To intentionally use the configured database instead: `python3 scripts/run.py`.


Then in the frontend `.env`: `VITE_API_URL=http://localhost:8081` and restart `npm run dev`.

## API
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | anyone | `{username, password}` → `{token, role, name, state?, recordId?}` |
| POST | `/api/auth/forgot-password` | anyone | `{username}`; generic response, email link if eligible |
| POST | `/api/auth/reset-password` | anyone | `{token, newPassword}`; single-use, expires in 15 minutes |
| GET | `/reset-password` | anyone | built-in reset form; token comes from email URL fragment |
| GET | `/api/admin/notifications` | admin | safe queue status and provider message IDs |
| POST | `/api/admin/notifications/{id}/retry` | admin | retry a failed, unexpired mail |
| GET | `/api/auth/me` | signed in | current user |
| GET | `/actuator/health` | anyone | for the server monitor |
| POST | `/api/forms/{formKey}` | anyone | JSON, or multipart when files are attached. 10 per minute per IP |
| GET | `/api/admin/registrations?kind=&status=&state=&q=` | signed in | `{items}`, only records in the caller's scope |
| GET | `/api/admin/registrations/{id}` | signed in | one record, with `history` |
| PATCH | `/api/admin/registrations/{id}` | admin, state_head | `{status: verified\|rejected\|pending, reason}`; reason required for rejected. Verifying a vendor / FPO returns `account: {created, username, tempPassword, message}` |
| GET | `/api/admin/registrations/{id}/files/{key}` | signed in | opens the document; `?token=` allowed only here |
| GET | `/api/public/vendors` | anyone | verified vendors only, no GST/turnover/email |
| GET | `/api/public/vendors/{slug}` | anyone | one verified vendor |
| GET | `/api/public/vendors/{slug}/logo` | anyone | logo image of a verified vendor |
| GET | `/api/public/fpos` | anyone | verified FPOs: name, state, district, kisan count, ODOP |
| GET | `/api/public/stats` | anyone | verified FPOs / vendors per state (17 States Map) |
| POST | `/api/auth/change-password` | signed in | `{currentPassword, newPassword}` → fresh session; old tokens stop working |
| GET | `/api/admin/users?role=&state=&q=` | admin, state_head | state_head sees the Project Heads of their state |
| POST | `/api/admin/users` | admin, state_head | `{username, name, role, state?, recordId?}` → `{user, tempPassword}` (shown once) |
| PATCH | `/api/admin/users/{id}` | admin, state_head | `{name?, state?, active?}` |
| POST | `/api/admin/users/{id}/reset-password` | admin, state_head | new one-time password |
| GET | `/api/admin/enquiries?formKey=&status=&q=&from=&to=` | admin | inbox; dates as `2026-11-19` |
| PATCH | `/api/admin/enquiries/{id}` | admin | `{status: new\|replied\|closed, note}` |
| GET | `/api/admin/export/registrations?kind=&status=&state=&q=` | admin, state_head, project_head | `.xlsx`, own state only for heads |
| GET | `/api/admin/export/enquiries?formKey=&status=&q=&from=&to=` | admin | `.xlsx` |
| GET | `/api/admin/stats` | signed in | pending / verified / rejected per kind, in the caller's scope |
| GET | `/api/admin/audit?action=&limit=` | admin | activity log (logins, user changes, decisions, exports) |

Send `Authorization: Bearer <token>`. Tokens last 12 hours (`JWT_TTL`).

Registration forms: `fpo`, `vendor`, `ngo`, `projecthead`, `member`, `job` (schemas in `forms/FormSchemas.java`, same as the
frontend's `src/config/forms.js`; change both together). All other forms (`contact`, `register`, `eauto`, `amul`,
`investor`, `product`, `quote`, `supplier`, `sourcing`, `connect`) are saved as enquiries.

Every error looks like `{ "ok": false, "message": "...", "errors": { "field": "..." } }`.

## Security notes
- Passwords stored as BCrypt hashes (10 characters minimum, 72 bytes maximum). Usernames are case-insensitive.
- 5 wrong passwords on one username → that username is locked for 15 minutes (20 per IP, since an office shares one IP).
  An attempt is counted before the password is checked, so requests sent at the same moment cannot pass the limit.
- Existing logins are changed only with small atomic updates (`UserWrites`), so a sign-in can never undo a switch-off.
- Verifying a registration never switches a login back on; only an admin can, in Users.
- One pending/verified record per CIN / GST / Darpan ID, also when a rejected record is moved back
  (checked in code and by the `unique_active` database index).
- Everything not listed above is closed by default.
- Roles: `admin` (all, decides), `state_head` (own state, decides), `project_head` (own state, view only),
  `vendor` / `fpo` (only their own record).
- Files are checked by their content (a renamed .exe is refused), saved under `STORAGE_DIR/yyyy/MM/<random>.ext`,
  never inside the web root, and served only to signed-in users within their scope.
- Pending and rejected records are never returned by a public endpoint. Out-of-scope records answer 404.
- The same CIN (FPO) or GST (vendor) cannot be registered twice while pending or verified.
- `SEED_DEMO=true` also adds a few sample registrations (`sample: true`). Keep it `false` on the live server.
- New logins and password resets get a one-time password; until it is changed, that login can only call
  `/api/auth/me` and `/api/auth/change-password` (others answer 403 with `code: password_change_required`).
- Switching a login off, resetting or changing its password, or moving it to another state ends its old sessions at once.
- The last active admin cannot be switched off. Passwords: at least 10 characters with letters and numbers.
- Excel cells are always written as text, never as formulas.
