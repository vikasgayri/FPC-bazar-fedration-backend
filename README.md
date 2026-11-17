# FPC Bazaar Federation – Backend API

Java 21 · Spring Boot 4 · MongoDB Atlas. Frontend: `FPC-Bazaar-Federation-main` (contract in its `docs/ADMIN_API.md`).

## Status
- **Step 1 (done):** project setup, MongoDB connection, all settings from environment, login with roles
  (Admin > State Head > Project Head > Vendor > FPO), first admin created automatically, one JSON error format.
- Step 2: public forms + document upload, admin verify/reject, public verified vendors.
- Step 3: admin user management, vendor/FPO logins, enquiries inbox, NGO form, Excel export.
- Step 4: Email + SMS notifications, backups, deploy on Infinitive Cloud.

## Run on your laptop
Needs Java 21 (`java -version`). Maven is not needed, the wrapper downloads it.

```bash
cp .env.example .env          # fill MONGODB_URI, JWT_SECRET, ADMIN_PASSWORD
set -a; source .env; set +a
./mvnw spring-boot:run        # API on http://localhost:8080
./mvnw test                   # tests use an embedded MongoDB, no Atlas needed
```

Then in the frontend `.env`: `VITE_API_URL=http://localhost:8080` and restart `npm run dev`.

## API (Step 1)
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | anyone | `{username, password}` → `{token, role, name, state?, recordId?}` |
| GET | `/api/auth/me` | signed in | current user |
| GET | `/actuator/health` | anyone | for the server monitor |

Send `Authorization: Bearer <token>`. Tokens last 12 hours (`JWT_TTL`).

Every error looks like `{ "ok": false, "message": "...", "errors": { "field": "..." } }`.

## Security notes
- Passwords stored as BCrypt hashes. Usernames are case-insensitive.
- 5 wrong passwords on one username → that username is locked for 15 minutes (20 per IP, since an office shares one IP).
- Everything not listed above is closed by default.
- Roles: `admin` (all, decides), `state_head` (own state, decides), `project_head` (own state, view only),
  `vendor` / `fpo` (only their own record).
