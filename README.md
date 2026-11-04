# CoopAggregate - Backend

REST API for CoopAggregate, an offline-capable system for delivery records and fair
payment sharing at Koperative Intagamburuzwa, an Irish-potato cooperative in Busogo,
Musanze, Rwanda. The cooperative manager is the only user who logs in, members receive
SMS notifications, and buyers use a public page without logging in. This repository
contains the API only; the React frontend is in a separate repository.

## Links

| What | URL |
|------|-----|
| Frontend repository | https://github.com/EMe-U/coopaggregate-frontend |
| Live API (health check) | https://coopaggregate-backend.onrender.com/api/health |
| Live app | https://coopaggregate-frontend.onrender.com |

## Tech stack

- Java 21
- Spring Boot 3.5
- Spring Data JPA
- Flyway
- PostgreSQL (Neon)
- Spring Security with JWT (OAuth2 Resource Server, HS256)
- springdoc-openapi (Swagger UI)
- JUnit 5, Mockito
- Docker
- Render

## Project structure

The code is organised by feature. Each feature package holds its own controller,
service, repository, entity and DTO classes. Shared code lives in `config`, `error`
and `common`.

```
coopaggregate-backend/
├── src/main/java/com/coopaggregate/
│   ├── CoopAggregateApplication.java
│   ├── config/            Security, JWT, CORS and Swagger configuration
│   ├── error/             Error responses for the whole API
│   ├── common/            Shared code (paging, Rwandan phone numbers)
│   ├── auth/              Manager login and current manager
│   ├── manager/           Manager account
│   ├── ledger/            Append-only, hash-chained ledger
│   ├── member/            Cooperative members
│   ├── health/            Health check endpoint
│   ├── batch/             Batches (kg sold and lost per delivery), sale planning and preview
│   └── grade/, lot/, delivery/, loss/, buyer/, sale/,
│       share/, payment/, dispute/, setting/, sms/   Entities and repositories
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/      Flyway migrations
├── Dockerfile
├── .env.example
└── pom.xml
```

## Getting started

### Prerequisites

- JDK 21 or newer
- A PostgreSQL database (for example a free [Neon](https://neon.tech) project)

Maven does not need to be installed. The project includes the Maven wrapper
(`mvnw` / `mvnw.cmd`).

### Clone

```bash
git clone https://github.com/EMe-U/coopaggregate-backend.git
cd coopaggregate-backend
```

### Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DATABASE_URL` | Yes | JDBC URL: `jdbc:postgresql://host/db?sslmode=require` |
| `DB_USERNAME` | Yes | Database user |
| `DB_PASSWORD` | Yes | Database password |
| `JWT_SECRET` | Yes | Secret for signing tokens, at least 32 characters |
| `FRONTEND_URL` | No | Allowed CORS origin. Default: `http://localhost:5173` |
| `MANAGER_NAME` | First start | Name of the manager account |
| `MANAGER_EMAIL` | First start | Login email of the manager account |
| `MANAGER_PASSWORD` | First start | Login password of the manager account |
| `SMS_PROVIDER` | No | `log` (default: SMS are only saved in `sms_log`), `africastalking` or `traccar` |
| `AT_USERNAME` | With Africa's Talking | Africa's Talking username; `sandbox` for their sandbox |
| `AT_API_KEY` | With Africa's Talking | Africa's Talking API key |
| `AT_SENDER_ID` | No | Sender name shown on members' phones, if your account has one |
| `SMS_TRACCAR_TOKEN` | With Traccar | Token of the Traccar SMS Gateway app. The app does not start without it |
| `SMS_TRACCAR_URL` | No | Gateway URL with Traccar. Default `https://www.traccar.org/sms/`. Must be `https` (`http` only for localhost) |
| `SMS_INBOUND_SECRET` | For SMS replies | Long random string that the SMS reply webhook must send (see SMS) |
| `SMS_LOG_SHOW_CODES` | No | **Development only.** `true` prints the real one-time codes in the console with `SMS_PROVIDER=log` (default `false`, see SMS) |
| `SMS_DEMO_CODE` | No | **Temporary, for the demo only.** 6 digits also accepted as the code of any pending delivery. Not set by default (see Delivery confirmation) |
| `MOSIP_ENABLED` | To register members | `true` switches member identity verification on. Registering a member needs it, so with the default `false` no member can be registered (see Identity verification) |
| `MOSIP_BASE_URL` | With `MOSIP_ENABLED` | The eSignet issuer, for example `http://localhost:8080`. Plain `http` only for localhost. The authorize, token, userinfo and JWKS URLs follow from it |
| `MOSIP_CLIENT_ID` | With `MOSIP_ENABLED` | The client id registered with eSignet |
| `MOSIP_REDIRECT_URI` | With `MOSIP_ENABLED` | The frontend page eSignet sends the member back to, exactly as registered, for example `http://localhost:5173/identity/callback` |
| `MOSIP_PRIVATE_KEY_PATH` | With `MOSIP_ENABLED` | Path to the cooperative's RSA private key file (PKCS#8 PEM or JWK, detected from the content). Keep it outside the repository |
| `MOSIP_KEY_ID` | With `MOSIP_ENABLED` | The `kid` of the matching public JWK |
| `MOSIP_SCOPE`, `MOSIP_ACR_VALUES`, `MOSIP_ASSERTION_AUDIENCE` | No | Defaults: `openid`, `mosip:idp:acr:password`, and `MOSIP_BASE_URL`. The details are asked for with the `claims` parameter, so leave `MOSIP_SCOPE` out (`profile` would also release the picture); the app warns at startup if it asks for more than `openid` and `phone`. `MOSIP_ACR_VALUES` may hold several login methods, space-separated; it is sent as `acr_values` exactly as set |
| `MOSIP_OPTIONAL_CLAIMS` | No | The optional details asked for besides the name and phone, space-separated. Default (and when empty): `birthdate gender email address`. Only those four can be asked for; any other name, `picture` included, is left out and named in a startup warning |
| `MOSIP_NATIONAL_ID_CLAIM` | No | The claim name of the national ID, asked for as optional. Default: empty, not asked for (the local eSignet does not release it). The national ID stays entered by the manager either way |
| `MOSIP_PROMPT_CONSENT` | No | Demo setting. `true` adds `prompt=consent` so eSignet shows its Allow page on every login, not only the first. Default `false` |
| `MOSIP_ALLOWED_ACRS` | No | The id_token `acr` values (login methods) that count as a verification, space-separated, compared exactly. Default: `mosip:idp:acr:generated-code` (the one-time code) |
| `MOSIP_ISSUER` | No | The exact `iss` the ID token must carry (and the userinfo answer, unless the next one is set). Default: `MOSIP_BASE_URL` |
| `MOSIP_USERINFO_ISSUER` | No | The exact `iss` the userinfo answer must carry, for an eSignet that signs it with another issuer than the ID token (the local one: ID token `http://localhost:8080`, userinfo `http://localhost:8088/v1/esignet`). Default: the ID token's issuer |
| `PORT` | No | HTTP port. Default: `8080` |

The manager is the only user who logs in. On startup, if the `manager` table is empty,
the app creates the manager account from `MANAGER_NAME`, `MANAGER_EMAIL` and
`MANAGER_PASSWORD` (the password is stored as a BCrypt hash). If they are not set, the
app logs a warning and starts without a manager. Once the account exists, these
variables are no longer read, so changing them does not change the password.

Neon shows a connection string like `postgresql://user:password@host/db?sslmode=require`.
Turn it into a JDBC URL by adding `jdbc:` at the start and removing `user:password@`,
then put the user and password in `DB_USERNAME` and `DB_PASSWORD`.

You can set the variables in one of two ways.

**IntelliJ run configuration**

Open Run > Edit Configurations, select the `CoopAggregateApplication` configuration,
and add the variables to the Environment variables field, separated by semicolons:

```
DATABASE_URL=jdbc:postgresql://host/db?sslmode=require;DB_USERNAME=...;DB_PASSWORD=...;JWT_SECRET=...;MANAGER_NAME=...;MANAGER_EMAIL=...;MANAGER_PASSWORD=...
```

**A .env file**

Copy `.env.example` to `.env` and fill in your values. `.env` is ignored by Git.
Spring Boot does not read `.env` by itself, so load it into your terminal session
before starting the app.

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
Get-Content .env | Where-Object { $_ -match '^[^#].*=' } | ForEach-Object { $k, $v = $_ -split '=', 2; Set-Item "env:$k" $v }
```

macOS / Linux:

```bash
cp .env.example .env
set -a; source .env; set +a
```

### Run

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS / Linux:

```bash
./mvnw spring-boot:run
```

The API starts on http://localhost:8080.

### Check that it works

```bash
curl http://localhost:8080/api/health
```

Expected response:

```json
{"status":"OK"}
```

On Windows PowerShell, use `curl.exe` instead of `curl`.

## Database

The schema is managed by Flyway. Migrations are in `src/main/resources/db/migration`
and run automatically when the application starts. Hibernate only validates the
schema (`ddl-auto=validate`); it never changes it.

ERD: see `docs/erd.png`.

### V31 and V32: batches and credits

- **V31** makes every delivery a batch: it adds `sold_kg`, `lost_kg` and a generated `remaining_kg`
  to `delivery`, and the tables `sale_allocation` and `loss_allocation`. It fills them for the sales
  and losses that already exist, and stops (the whole migration rolls back) if the batches do not add
  up to the lots' own totals.
- **V32** gives each sale a `grade_id` (old sales take the grade of their lot) and makes
  `sale.lot_id` optional. It adds `gross_rwf` and `deduction_rwf` to `sale_allocation` (empty for
  sales made before credits), allows the `SALE_CREDIT` ledger entry type, and adds a unique index so
  a batch's part of a sale can be credited only once. No ledger row is changed.
- **After V32 only the new build may run.** A sale needs a `grade_id`, which older builds never set,
  and they do not know `SALE_CREDIT`. Rolling the code back does not undo a migration: to go back,
  restore a database backup (for example a Neon branch) taken before the migration.
- **V33** adds `contact_method` (`CALL` or `WHATSAPP`, default `CALL`) and `whatsapp_number` to
  `buyer_request`. Existing requests become `CALL`. It only adds columns, so it is safe to run
  before or after the new build, but only the new build reads and writes the columns.
- **V34** adds to the identity verification table (V27) the PKCE verifier, salted hashes of the
  provider's name, birth date and phone, and an attempts counter, and adds `PASSWORD` to the allowed
  verification methods (the one thing it replaces is that list). No row is changed, so it is safe to run
  before or after the new build.
- **V35** gives each `sms_log` row a `message_type`: `DELIVERY_CODE` (every existing row) or
  `RECEIPT`, and makes the unique index one message per delivery, type and send number. No row is
  changed. Run it before the new build starts: the new build reads and writes the column.
- **V36** brings fixed-size lots (see Lots and losses). It adds:
  - `setting.lot_size_kg` (default 100, more than 0);
  - `lot.capacity_kg` and the statuses `FILLING` and `FULL`, with at most one `FILLING` lot per grade;
  - `delivery.grade_id`, and makes `delivery.lot_id` optional (only deliveries from before have one);
  - the table `lot_portion` (the kg of one delivery in one lot, with sold, lost and a generated
    remaining kg);
  - `lot_portion_id` on `sale_allocation` and `loss_allocation`, and a sale unique per portion instead
    of per delivery.

  It fills in every delivery's grade from its lot (the only row update) and gives every existing
  delivery one portion in the lot it is in, with the same kg. Existing lots keep their size and
  status and get no new kg. No ledger row is touched. A check at the end compares grades, portions
  and lot totals and rolls the whole migration back on any difference. Run it before the new build
  starts. `docs/fixed-lots-checks.sql` checks the result afterwards (read only).
- **V37** adds `verified_name` and `verified_phone` to the identity verification table: the verified
  details a new member is registered with, kept for at most 30 minutes (both set or both empty). It
  empties the salted hashes and the attempts of the old comparison (the only row update); those
  columns stay, unused. A verification started before V37 cannot be used and is simply done again.
  Run it before the new build starts.
- **V38** keeps every detail eSignet can release (see Identity verification). It adds
  `verified_birthdate`, `verified_gender`, `verified_email` and `verified_address` to the identity
  verification table, and `birth_date`, `gender`, `email` and the flags `birth_date_verified`,
  `gender_verified`, `email_verified` and `address_verified` (default `false`) to `member`. The member's
  existing `address` column is reused and made longer (255 to 500 characters), which keeps every value.
  No row is changed. Run it before the new build starts: the new build reads and writes the columns.
- Applied migrations are never edited; a change is a new migration.

## API endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/health` | None | Returns `{"status":"OK"}` |
| POST | `/api/auth/login` | None | Body `{"email", "password"}`. Returns `{token, expiresAt, managerName}` |
| GET | `/api/auth/me` | JWT | Returns `{name, email}` of the logged-in manager |
| POST | `/api/auth/change-password` | JWT | Body `{"currentPassword", "newPassword"}`. Returns `204` (see Authentication) |
| GET | `/api/settings` | JWT | `{smsEnabled, cooperativeEmail, location, minOrderKg, committeeEmails, deductionPerKg, cooperativeName, cooperativePhone, lotSizeKg}` |
| PUT | `/api/settings` | JWT | Saves the settings (see Settings and change history) |
| GET | `/api/settings/history?page=0&size=20` | JWT | Change history, newest first (max 100 per page) |
| GET | `/api/ledger?page=0&size=20` | JWT | Ledger entries, newest first (max 100 per page) |
| GET | `/api/ledger/verify` | JWT | Recomputes every hash. Returns `{valid, checkedEntries, firstBrokenEntryId}` |
| POST | `/api/ledger/{id}/reverse` | JWT | Body `{"reason"}` (required). Reverses the entry through its business action (see Reversals) and returns the REVERSAL entry |
| GET | `/api/members?search=&active=&page=0&size=20` | JWT | Members sorted by name (max 100 per page). `search` and `active` are optional. Without `nationalId` |
| GET | `/api/members/summary` | JWT | Counts: `{total, active, inactive, joinedThisMonth}` |
| GET | `/api/members/{id}` | JWT | One member, including `nationalId` |
| POST | `/api/members` | JWT | Registers a member from a verification and returns it with `201`. Body `{verificationId, nationalId?, address?, joinDate?, preferredLanguage?}`; every verified detail (name, phone, birth date, gender, email, address) comes from the verification and must not be sent (`400 VERIFIED_FIELD_LOCKED`, see Identity verification); `503` when verification is not configured |
| GET | `/api/members/verify/status` | JWT | `{available, required}`: both are `true` when `MOSIP_ENABLED=true`, both `false` otherwise (registering a member needs verification) |
| POST | `/api/members/verify/start` | JWT | Starts a verification: `{provider, authorizationUrl, state, expiresAt}`. `503` if not available |
| POST | `/api/members/verify/callback` | JWT | Body `{code, state}` from eSignet's redirect. Returns `{verificationId, verified, method, fullName, phone, useBefore, birthdate, gender, email, address, verifiedFields}` |
| POST | `/api/members/verify/cancel` | JWT | Body `{state, error}` when eSignet's redirect has an error instead of a code. Returns `{reason, message}` |
| PUT | `/api/members/{id}` | JWT | Updates a member's details |
| PATCH | `/api/members/{id}/deactivate` | JWT | Sets the member's status to `INACTIVE` |
| PATCH | `/api/members/{id}/activate` | JWT | Sets the member's status to `ACTIVE` |
| GET | `/api/grades?includeInactive=false` | JWT | Grades `{id, code, name, nameRw, active, floorPricePerKg, description, sizeInfo, packaging}`, sorted by code. Only active ones unless `includeInactive=true`. `floorPricePerKg` is `null` when not set |
| PUT | `/api/grades/{id}` | JWT | Body `{"name", "nameRw", "active", "floorPricePerKg", "description", "sizeInfo", "packaging"}`. Edits a grade. Grades A, B and C are fixed: they cannot be created or deleted |
| GET | `/api/lots?status=&gradeId=&page=0&size=20` | JWT | Lots, newest first (max 100 per page), with capacity, space left or shortfall, and kg in the lot, lost, sold, remaining and pending confirmation |
| GET | `/api/lots/{id}` | JWT | One lot, with each member's kg in it and how much of it was sold, lost and is left |
| GET | `/api/lots/stock` | JWT | Stock: kg left in total and per grade, read from the batches and split into sellable, waiting and disputed kg; delivered, sold, lost and pending-confirmation kg, today's deliveries and lot counts |
| GET | `/api/lots/open?gradeId=` | JWT | The lot being filled for the grade `{id, code, gradeName, totalKg, capacityKg}`. `204` if none yet, `404` if the grade does not exist |
| POST | `/api/lots/{id}/losses` | JWT | Records a loss on a lot that still holds potatoes, returns `201`. Shared across the lot's members in proportion to their kg left |
| GET | `/api/lots/{id}/losses` | JWT | Losses of a lot, newest first |
| GET | `/api/buyers?search=` | JWT | Buyers sorted by name. `search` matches name or phone |
| POST | `/api/buyers` | JWT | Adds a buyer, returns `201` |
| GET | `/api/public/grades` | None | Active grades `{code, name, nameRw, description, sizeInfo, packaging, availability}`. No prices or kilograms (see Public side for buyers) |
| GET | `/api/public/info` | None | `{cooperativeName, location, cooperativeEmail, minOrderKg, cooperativePhone}` |
| POST | `/api/public/requests` | None | A buyer's purchase request, with an optional `contactMethod` (`CALL` or `WHATSAPP`) and `whatsappNumber`. Returns `201` `{code, status}`. Limited per IP address and phone number |
| GET | `/api/public/requests/{code}?phone=` | None | `{code, status, message, contactMethod, contactNumber}`. `404` if the code or the phone is wrong |
| GET | `/api/buyer-requests?status=&search=&page=0&size=15` | JWT | Buyers' requests, newest first (15 per page, max 100), with `contactMethod` and `whatsappNumber` |
| GET | `/api/buyer-requests/{id}` | JWT | One request with the buyer's contact details and how to contact them |
| PATCH | `/api/buyer-requests/{id}/status` | JWT | Body `{"status", "publicMessage"}` (message optional). Changes the status; kept in the change history |
| POST | `/api/buyer-requests/{id}/buyer` | JWT | Turns the request into a buyer record, or links it to the buyer with the same phone |
| POST | `/api/sales` | JWT | Records a sale of a grade, first in first out by lot, and credits each portion's member (see Sales). `201` when new, `200` when the same `clientUuid` was already recorded |
| POST | `/api/sales/orders` | JWT | One buyer order for several grades (each once), one sale per grade. Same rules and status codes |
| GET | `/api/grades/{id}/sale-preview?quantityKg=&pricePerKg=&skipLots=` | JWT | The portions a sale would use and what each member would be credited. Saves nothing |
| GET | `/api/grades/{id}/batches?page=0&size=20` | JWT | The grade's batches that still have kg, oldest delivery first, each with its lots |
| GET | `/api/members/{id}/batches?status=all&page=0&size=20` | JWT | A member's batches, newest first, with kg and money earned per batch. `status=unsold` keeps batches with kg left |
| GET | `/api/sales?search=&lotId=&buyerId=&gradeId=&page=0&size=20` | JWT | Sales, newest first (max 100 per page). All filters are optional |
| GET | `/api/sales/summary` | JWT | `{salesAmountThisMonth, soldKgThisMonth, buyersThisMonth, averagePricePerKg}` |
| GET | `/api/lots/{id}/share-preview` | JWT | Older lot sharing (see Sharing): `409` for any lot that has sales, and while deliveries are pending or disputed |
| POST | `/api/lots/{id}/share` | JWT | Older lot sharing: saves the shares and marks the lot `SHARED`. `409` for any lot that has sales |
| GET | `/api/payments/balances?search=&status=` | JWT | Earned (sale credits and shares), deductions, paid and balance per member, plus `totalOwedRwf` and `paidThisWeekRwf` |
| POST | `/api/payments` | JWT | Pays a member. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/payments?memberId=&method=&page=0&size=20` | JWT | Payments, newest first (max 100 per page). Reversed payments are listed with `reversed: true` |
| POST | `/api/payments/{id}/reverse` | JWT | Body `{"reason"}`. Cancels a payment; the member is owed the amount again |
| GET | `/api/members/{id}/statement` | JWT | A member's sale credits, shares, deductions, payments and amount owed |
| POST | `/api/deliveries` | JWT | Records a delivery. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/deliveries?memberId=&lotId=&from=&to=&includeReversed=false&page=0&size=20` | JWT | Deliveries, newest first (max 100 per page), each with its `lots`. All filters are optional; `lotId` finds every delivery with kg in that lot. Reversed deliveries only with `includeReversed=true` |
| GET | `/api/deliveries/{id}` | JWT | One delivery, also when it was reversed |
| POST | `/api/deliveries/{id}/reverse` | JWT | Body `{"reason"}`. Cancels a delivery recorded by mistake |
| POST | `/api/deliveries/{id}/correct` | JWT | Body `{"reason", "memberId", "gradeId", "quantityKg"}`. Replaces a wrong delivery, returns the corrected one with `201` |
| POST | `/api/deliveries/{id}/confirm` | JWT | Body `{"code"}`. Confirms a pending delivery with the 6-digit code from the member's SMS |
| POST | `/api/deliveries/{id}/resend-code` | JWT | Sends the member a new code; the old one stops working. At most 3 times per delivery |
| POST | `/api/deliveries/{id}/review` | JWT | Body `{"note"}`. Confirms a pending delivery after the manager checked it. Only while SMS are off, once all 3 new codes were sent, or after 48 hours pending |
| POST | `/api/deliveries/{id}/dispute` | JWT | Body `{"reason", "note"}`. Records that the member says the delivery is wrong |
| POST | `/api/deliveries/{id}/resolve-dispute` | JWT | Body `{"note"}`. Closes a dispute after checking the delivery is correct |
| GET | `/api/disputes?status=open&search=&page=0&size=15` | JWT | Open or settled disputes, newest first (15 per page, max 100), with totals `{openDisputes, openKg, settledDisputes}` |
| GET | `/api/reports/weekly?weekStart=2026-10-19` | JWT | Weekly report: deliveries, stock, sales, money and confirmations (see Weekly report) |
| GET | `/api/reports/weeks` | JWT | The weeks of the last 52 with any activity, newest first: `[{weekStart, weekEnd}]` |
| POST | `/api/sms/inbound?token=` | Secret | Webhook for members' SMS replies (see SMS) |
| GET | `/api/dashboard` | JWT | Numbers for the home screen: stock split into sellable, waiting and disputed kg, grades with sellable kg, the lots being filled with their fill level (`lotsBeingFilled`), disputed deliveries and confirmations pending for more than 48 hours |

More endpoints coming soon.

### Authentication

The manager logs in with `POST /api/auth/login` and receives a JWT that is valid for
12 hours. Protected endpoints need the header `Authorization: Bearer <token>`.

- A wrong email or a wrong password both return `401` with
  `{"message": "Invalid email or password"}`.
- After 5 failed attempts for the same email, logins for that email are refused with
  `429` for 5 minutes. The counter is kept in memory and resets when the app restarts.
- `POST /api/auth/change-password` needs the current password. The new one must have
  8 to 72 characters and be different from the current one, otherwise `400` with a
  clear message. Tokens already given out stay valid until they expire.

### Settings and change history

- `smsEnabled` (default `true`) turns the member SMS on or off.
- `cooperativeEmail`, `cooperativePhone` and `location` are optional. The phone is a Rwandan mobile number
  (`07XXXXXXXX`, `2507XXXXXXXX` or `+2507XXXXXXXX`), stored as `+2507XXXXXXXX`; empty clears it. `minOrderKg` (default 100) must be more than 0.
- `cooperativeName` (default "Koperative Intagamburuzwa") is shown on the public pages. Optional
  in the request; left out or empty keeps the current name.
- `lotSizeKg` (default 100, more than 0, at most 100,000, 2 decimals) is the size of new lots (see
  Lots and losses). Optional in the request; left out keeps the current size. It can change at any
  time, with `lotSizeReason` (10 to 500 characters, otherwise `400`; ignored when the size does not
  change). The new size applies only to lots opened afterwards: every lot keeps the capacity it was
  opened with, so the lot being filled keeps its size and the next lot gets the new one. Each
  change is written to the change history as `LOT_SIZE_CHANGED`, for example
  `Lot size: 100 kg -> 50 kg, for new lots only. Reason: …`.
- `committeeEmails` holds at most 10 valid emails. They are saved in lowercase without
  duplicates, in the `committee_emails` column of the `setting` table, separated by commas.
- Every change to the settings or a grade, every password change, every new code sent for
  a delivery and every manager review of a delivery is written to the change history (`GET /api/settings/history`) with the
  manager and the time, for example `smsEnabled: true -> false`. Passwords and codes are
  never written to it. Rows from the old member PIN (`PIN_SET`, `PIN_RESET`) stay there.

### Errors

Every error response has the form `{"message": "..."}`.

| Status | When |
|--------|------|
| 400 | Invalid input. The message lists each field error. Also used when the body refers to something that cannot be used, such as an inactive member |
| 401 | Missing or expired token, or wrong login |
| 404 | The requested record does not exist |
| 409 | A business rule was broken, for example reversing an entry twice or registering a phone number that is already used |
| 429 | Too many failed logins |
| 500 | Unexpected error. The details are only written to the server log |

### Ledger

Every delivery, loss, sale, sale credit, share and payment adds a ledger entry. The entry
types are `DELIVERY`, `LOSS`, `SALE` (the buyer's money), `SALE_CREDIT` (what a sale credits to one
member for one batch, see Sales), `SHARE` (older lot sharing), `PAYMENT` and `REVERSAL`. Entries can
never be changed or deleted (a database trigger blocks it). Each entry stores a SHA-256 hash
of its own data plus the previous entry's hash, so changing any past entry breaks the
chain and `GET /api/ledger/verify` reports the first broken entry. Mistakes are fixed
with a REVERSAL entry that cancels the original. A reversal cannot be reversed, and an
entry can only be reversed once.

### Delivery confirmation

Every delivery is confirmed by the member with a one-time code sent by SMS.

- When a delivery is saved (online, or when an offline one syncs) it is `PENDING`, and
  the member gets one SMS with the delivery and a 6-digit code (see SMS). If the delivery
  is right, the member gives the code to the manager.
- `POST /api/deliveries/{id}/confirm` with `{"code"}` makes it `CONFIRMED` (method `OTP`).
  - The code is random (`SecureRandom`), and only a BCrypt hash is stored. It is never
    returned, logged or saved in `sms_log`, where it shows as `******`.
  - It expires 10 minutes after it is sent (`409`).
  - A wrong code is `400` with the tries left. After 3 wrong tries the code stops working,
    even the right one (`409`); the manager must send a new one.
- **Demo code (temporary, remove after the demo):** when `SMS_DEMO_CODE` is set to 6 digits,
  `confirm` also accepts it as the right code for any `PENDING` delivery. Everything else stays
  the same: the delivery needs a code that is sent and not expired, the 3-wrong-tries limit
  applies, and the member still gets the receipt SMS. Such a delivery has the resolution note
  "Confirmed with the demo code, not the member's SMS code." and a `DEMO_CODE_CONFIRMATION`
  entry in the change history; the code itself is never logged or saved. The app logs one
  warning at startup while it is set, and does not start if it is not 6 digits. Not set (the
  default), nothing changes. After the demo, remove the variable and delete the code (look for
  "TEMPORARY, for the demo only").
- `POST /api/deliveries/{id}/resend-code` sends a new code and the old one stops working at
  once. At most 3 times per delivery (`409` after that); each delivery response shows
  `codeResendsLeft`. Every resend is written to the change history.
- A correction is a new delivery, so it also starts `PENDING` and gets its own code.
- **Manager review:** when no code can reach the member, the manager checks the delivery
  and confirms it with `POST /api/deliveries/{id}/review` and a note (required). It becomes
  `CONFIRMED` with method `MANAGER_REVIEW` ("Checked by the manager") and is written to the
  change history. This is only allowed for a `PENDING` delivery when:
  - SMS are turned off (then no code is ever sent, so deliveries stay `PENDING` until
    reviewed),
  - SMS are on but all 3 new codes were already sent, or
  - it has been `PENDING` for more than 48 hours since it reached the server (`syncedAt`),
    the same deliveries the dashboard shows as overdue.

  In every other case it returns `409`. The member can still dispute a reviewed delivery
  by replying `2 RCT-…`.
- **Disputes:** `POST /api/deliveries/{id}/dispute` records a member's complaint
  (`WRONG_KG`, `WRONG_GRADE`, `NOT_MY_DELIVERY` or `OTHER`, with an optional note).
  - If the record is right: `POST /api/deliveries/{id}/resolve-dispute` with a note puts it
    back to `CONFIRMED` (method `MANAGER_REVIEW`).
  - If it is wrong: correct or reverse it (see Reversals); that also closes the dispute.
  - A lot cannot be shared while it has pending or disputed deliveries (see Sharing).
  - **Disputes page:** `GET /api/disputes` lists them from the same delivery data, newest first,
    15 per page. `status=open` (default) is every delivery still `DISPUTED` and not reversed, the
    same as the dashboard count; `status=settled` is every delivery that was disputed and has
    since been resolved, reversed or corrected. `search` matches the receipt code, member name or
    member number. Each item has the delivery id, receipt code, member name and number, grade, lot
    code, kg, reason, note, `disputedAt` and `daysOpen` (whole days until it was settled, or until
    now). Settled items also have `outcome` (`RESOLVED`, `REVERSED` or `CORRECTED`), `settledAt`,
    the manager's `resolutionNote` and, for a correction, `correctedById`. The response also has
    `openDisputes`, `openKg` and `settledDisputes` for the whole page header, not just one page
    or search. Settling stays on the delivery endpoints above.
  - Only a delivery's latest dispute is kept: if a member disputes a delivery again after it was
    resolved, the new reason and time replace the earlier ones.
- Deliveries recorded before confirmations existed are `CONFIRMED` with method
  `RECORDED_BEFORE_CONFIRMATION`, so they are not counted as waiting. The member never
  confirmed them. Each delivery also has `confirmationLabel`, the text to show, for example
  "Recorded before confirmation existed" or "Confirmed by member with the SMS code".
- The permanent member PIN was removed. Deliveries already confirmed with it keep method
  `PIN` ("Confirmed by member with PIN").

### SMS

After a delivery is saved, the member gets one SMS in their language (`rw` or `en`):

"CoopAggregate: RCT-7K2Q, 300 kg Big, 21/10. If correct, give the manager code 482193.
If wrong, don't give the code, reply 2 RCT-7K2Q."

The reply instruction is only there when the provider passes replies on to the webhook
(`africastalking`). With `log` and `traccar` the last sentence is instead
"If the code is wrong, tell the manager."

The Kinyarwanda text still needs a check by a native speaker.

The SMS is sent in the background after the delivery is committed, so a failed SMS never
fails or slows a delivery. Every message is saved in `sms_log` (`QUEUED`, `SENT`, `FAILED`
or `CANCELLED`) with the code replaced by `******`. Failed ones are retried every 5 minutes,
up to 5 attempts. Because only the code's hash is kept, each attempt sends a **new** code;
the member never got the earlier one. A code message that is no longer needed (the delivery
was confirmed, disputed or reversed, or a newer code was asked for) is `CANCELLED` instead
of sent. No SMS are sent when SMS are turned off in the settings.

**Receipt.** Once a delivery becomes `CONFIRMED` (with the code, by an SMS reply, by the
manager's review of a pending delivery, or when a dispute is resolved as correct), the member gets
one receipt in their language, for example:

"Koperative Intagamburuzwa: delivery RCT-7K2Q confirmed. 300 kg Big, 21/10. Thank you."

It holds the cooperative's name, the receipt code, kg, grade and day; never money, a balance or
the member's name. Names are written in plain characters, and a long cooperative name is shortened
so the receipt fits in one SMS. It is sent in the background after the confirmation is committed,
so a failed receipt never undoes or holds up the confirmation; it is retried like any other SMS.
It is saved in `sms_log` with `message_type` `RECEIPT`, at most once per delivery: confirming again
later (for example after a dispute is resolved) sends no second receipt. An unsent receipt is
`CANCELLED` if the delivery is disputed or reversed first. Deliveries recorded before confirmation
existed get none unless they are confirmed later. No receipt is sent while SMS are turned off.

`SMS_PROVIDER=log` (default) sends nothing. The messages are in `sms_log`, but the codes
are masked there. To test codes locally, set `SMS_LOG_SHOW_CODES=true`: the log provider
then prints each SMS, real code included, in the console (`sms_log` stays masked). This is
**for development only**: with any other provider the app refuses to start while it is
`true`. Without it, use the Africa's Talking sandbox and its simulator to see the codes. `SMS_PROVIDER=africastalking` sends them with Africa's Talking
(`AT_USERNAME=sandbox` uses their sandbox and simulator).

**Traccar SMS Gateway** (`SMS_PROVIDER=traccar`) sends through an Android phone with a SIM
card that runs the Traccar SMS Gateway app. Set `SMS_PROVIDER=traccar` and `SMS_TRACCAR_TOKEN`
(the token shown in the app); `SMS_TRACCAR_URL` is only needed for a different gateway address.
The app does not start if the token is missing or the URL is not `https` (`http` is accepted only
for `localhost`); the message names the variable, never its value.

- Each number is turned into `+2507XXXXXXXX` first; anything that is not a Rwandan mobile number
  is not sent and counts as a failed send.
- Timeouts are 3 seconds to connect and 10 seconds to read. The provider does not retry and does
  not follow redirects. A non-`2xx` answer or an exception is a failed send, so the usual
  resend limits, the 48-hour review and "Checked by the manager" work as with any provider.
- The log gets one `WARN` line per failure with the HTTP status or the exception class and the
  number masked to its last 3 digits. The token, the `Authorization` header, the message and
  the code are never logged. The same short text (no number, no message) is kept as the error of
  the message in `sms_log`. `SMS_LOG_SHOW_CODES` still works only with `SMS_PROVIDER=log`.
- **The SIM and the phone must stay on, charged and online.** The phone sends the SMS, so
  when it is off or has no signal, messages fail or wait.
- **"Accepted" does not mean "delivered".** `SENT` in `sms_log` means the gateway accepted the
  message; the provider cannot tell whether it reached the member's phone.
- The text of the messages is unchanged. This provider only sends: nothing forwards replies to
  the SMS reply webhook below.
- A different provider, for example Africa's Talking with a sender ID, can replace it later by
  changing `SMS_PROVIDER` and its settings. Nothing else changes.

**Replies:** in the Africa's Talking dashboard, set the SMS callback URL to
`https://<api-host>/api/sms/inbound?token=<SMS_INBOUND_SECRET>`. The secret can also be sent
in the `X-Sms-Secret` header; without the right secret the webhook answers `403`.

- `1 RCT-7K2Q` still confirms a pending delivery (method `SMS`).
- `2 RCT-7K2Q <note>` disputes it (reason `OTHER`, with the note), also when it was already
  confirmed ("this was not me").
- A reply only counts when it comes from the phone of the member who owns that delivery;
  everything else is ignored.

### Reversals

A reversal changes the business data and the ledger together, in one transaction, so
the totals and the ledger always agree. The reversed delivery or payment stays in its
table for the history (`reversed: true`, `reversalReason`) but is no longer counted
anywhere: lot kilograms, stock, the dashboard, sharing, member statements and balances.

- **Delivery:** `POST /api/deliveries/{id}/reverse`. Only while none of its kg are sold,
  because its member was already credited for sold kg (otherwise `409`). Other deliveries of a
  lot that has sales can still be reversed, but not those of a `SHARED` lot. Also `409` when the
  delivery is already reversed, or when its lost kg would not fit in what the rest of its lot has
  left ("would have less than 0 kg left").
- **A delivery that is in lots** leaves them: its lost kg are handed to the other portions of
  each lot, in proportion to what each has left, so the lot's losses stay the same. A lot still
  being filled gets the space back. A `FULL` lot keeps the shortfall (for example capacity 100,
  80 kg in it) and is never filled again; it becomes `SOLD` if nothing is left in it. Its
  portions stay as a record but no longer count. A waiting delivery is in no lot, so nothing
  else changes.
- **Correction:** `POST /api/deliveries/{id}/correct` reverses the delivery and saves the
  corrected one in the same transaction: new receipt code, the same `deliveredAt`, its own
  `DELIVERY` ledger entry. Like any new delivery it waits for the member's confirmation and only
  then goes into a lot. The two are linked by `correctsId` (on the new delivery) and
  `correctedById` (on the original). Same rules as a reversal. A correction to another grade
  locks both grades, in id order.
- **Payment:** `POST /api/payments/{id}/reverse`. The member's balance goes back up.
- **Ledger:** `POST /api/ledger/{id}/reverse` does the same as the endpoints above for
  `DELIVERY` and `PAYMENT` entries. `SHARE`, `SALE`, `SALE_CREDIT` and `LOSS` entries cannot
  be reversed (`409`): a share has already divided the money, and sales, their credits and losses
  cannot be reversed at all yet, because they have no place to record a reversal.

### Members

`POST /api/members` registers a member from a completed identity verification (see Identity
verification). Every verified detail is taken from the verification, never from the body: sending a
value for one of them (`fullName`, `phone`, `birthDate`, `gender`, `email`, or `address` when the
address was verified) is refused with `400` and code `VERIFIED_FIELD_LOCKED`, whatever the value, even
empty. Leave them out. A birth date, gender or email can only come from the check, so sending one that
was not verified is refused too (`400`, no code). An address that was not verified may be typed:

```json
{
  "verificationId": 9,
  "nationalId": "1199880012345678",
  "address": "Busogo",
  "joinDate": "2026-10-08",
  "preferredLanguage": "rw"
}
```

`PUT /api/members/{id}` updates a member:

```json
{
  "fullName": "Uwimana Claudine",
  "phone": "0788123456",
  "nationalId": "1199880012345678",
  "address": "Busogo",
  "joinDate": "2026-10-08",
  "preferredLanguage": "rw"
}
```

| Field | Rules |
|-------|-------|
| `verificationId` | Create only, required: the `verificationId` from `POST /api/members/verify/callback` |
| `fullName` | Update only: required, at most 150 characters. On create it is the verified name and must not be sent |
| `phone` | Update only: required. A Rwandan mobile number (072, 073, 078 or 079) written as `07XXXXXXXX`, `2507XXXXXXXX` or `+2507XXXXXXXX`. On create it is the verified phone and must not be sent. Always stored and returned as `+2507XXXXXXXX` |
| `nationalId` | Optional: leave it out or empty. When given, 16 digits and unique. Only returned for one member, never in the list |
| `address` | Optional. On create at most 255 characters, and only when the check did not verify an address. On update at most 500 (a verified address can be that long); changing a verified address removes it from `verifiedFields` |
| `joinDate` | Optional, not in the future. Defaults to today on create; left unchanged on update if not sent |
| `preferredLanguage` | Optional, `rw` or `en`. Defaults to `rw` |

A member (from create, update, `GET /api/members/{id}`, activate and deactivate) looks like this;
the list items are the same without `nationalId`:

```json
{
  "id": 1, "memberCode": "M-0001", "fullName": "Uwimana Claudine", "phone": "+250788123456",
  "nationalId": "1199880012345678", "address": "KG 7 Ave, Kacyiru, Gasabo, Rwanda",
  "joinDate": "2026-11-04", "status": "ACTIVE", "preferredLanguage": "rw",
  "createdAt": "2026-11-04T08:00:00Z", "verified": true, "verificationMethod": "PASSWORD",
  "verifiedAt": "2026-11-04T07:58:00Z",
  "birthDate": "1990-05-17", "gender": "Female", "email": null,
  "verifiedFields": ["fullName", "phone", "birthdate", "gender", "address"]
}
```

`birthDate`, `gender` and `email` are `null` when the check did not give them. `verifiedFields` names
the details that came from the check, in the order `fullName`, `phone`, `birthdate`, `gender`, `email`,
`address`; it is `[]` for a member who was never verified. The name and phone of a verified member are
always verified (members verified before V38 show just those two).

- The member code is generated on create as `M-` and 4 digits (`M-0001`, `M-0002`, ...):
  the highest existing number plus one. It never changes.
- The national ID is left out of `GET /api/members`; it is only shown when one member is
  returned (`GET /api/members/{id}`, and the responses of create, update, activate and
  deactivate). `search` still matches it.
- Phone numbers and national IDs must be unique. A duplicate returns `409`, for example
  `{"message": "Phone number +250788123456 is already used by member M-0003."}`.
- `search` matches part of the name, member code, phone number or national ID, ignoring
  case. A full number such as `0788123456` also finds `+250788123456`.
- `GET /api/members/summary` returns `{total, active, inactive, joinedThisMonth}`.
  `joinedThisMonth` counts members whose join date is in the current month in
  Africa/Kigali time.
- `active=true` returns only active members, `active=false` only inactive ones.
- Members are never deleted, because deliveries, payments and the ledger refer to them.
  Use deactivate instead. Activate and deactivate can be called again on a member that
  already has that status.

### Identity verification

Every new member is verified with MOSIP eSignet first, and is registered with every detail eSignet releases
(name, phone, and when allowed the birth date, gender, email and address); there is no setting to skip it and
nothing to type or compare. Existing members, updating members and every other feature do not depend on it.

1. **Start.** "Add member" calls `POST /api/members/verify/start`, which returns `authorizationUrl`: eSignet's
   login page, with a one-use `state` (valid 10 minutes), a `nonce`, PKCE (S256), `scope=openid` only (`profile`
   would also release the picture) and the OIDC `claims` parameter, URL-encoded. With the defaults it is:

   ```json
   {"userinfo":{"name":{"essential":true},"phone_number":{"essential":true},
                "birthdate":null,"gender":null,"email":null,"address":null}}
   ```

   `name` and `phone_number` are essential, so eSignet's consent screen shows them as required; the optional
   ones come from `MOSIP_OPTIONAL_CLAIMS` and the member may refuse each of them. `picture` is never asked for
   or kept. With `MOSIP_NATIONAL_ID_CLAIM` set, that claim is added as optional (`"<name>":null`); it is empty
   by default because this eSignet does not release the national ID, which stays entered by the manager. With
   `MOSIP_PROMPT_CONSENT=true` (a demo setting) the URL also carries `prompt=consent`, so the Allow page shows
   every time. The frontend sends the member there; eSignet sends them back to `MOSIP_REDIRECT_URI` with
   `code` and `state`, or with `error` and `state`.
2. **Back with a code:** `POST /api/members/verify/callback` with `{code, state}`. The backend exchanges the code
   at the token endpoint with a client assertion (PS256, signed with the private key in `MOSIP_PRIVATE_KEY_PATH`,
   with the `kid` of `MOSIP_KEY_ID`), then checks the ID token and the signed userinfo answer against eSignet's
   JWKS (signature, issuer, audience, expiry, nonce, and the same `sub` in both; see "Token checks" below). Only
   the claims that were asked for are read; anything else eSignet sends (`picture`, `given_name`, `nickname`,
   `zoneinfo`, ...) is not stored, logged or passed on. Control characters are removed from every text value.
   What eSignet sends, as measured on the real one, and how it is read:

   | Claim | Sent as | Kept as |
   |-------|---------|---------|
   | `name` | One plain string (`given_name` and the like are ignored) | Trimmed, runs of spaces made one; at most 150 characters (`NAME_TOO_LONG`). A language-tagged one (`name#eng`) is used only when the plain one is missing (with several, the first language code alphabetically) |
   | `phone_number` | `"+2507…"` | Must be a Rwandan mobile number (`PHONE_NOT_RWANDAN`), stored as `+2507XXXXXXXX` |
   | `birthdate` | `yyyy/MM/dd` (`yyyy-MM-dd` also accepted) | A date. Not a real date, in the future or before 1900: left out |
   | `gender` | A plain string, for example `"Female"` | As given, trimmed. Longer than 20 characters: left out |
   | `email` | A plain string | Trimmed. No `@` or longer than 254 characters: left out |
   | `address` | A JSON object with `street_address`, `locality`, `region`, `country` (`postal_code` may be missing); a plain string is also accepted | One line: the parts present, in that order, joined with `, ` (`postal_code` and `formatted` are not used). Longer than 500 characters: cut at the last whole word |

   A refused or missing optional detail, or one left out because it does not fit, never fails the login: that
   field is just `null`. A missing name or phone is still an error (`CLAIMS_MISSING`), and a member who refuses
   them at eSignet comes back with `CLAIMS_REFUSED` as before. Each callback logs one INFO line per optional
   claim with its name only: `Identity verification userinfo claim birthdate: present` (or `absent`, or
   `ignored, unusable`), never a value. It returns:

   ```json
   { "verificationId": 9, "verified": true, "method": "PASSWORD",
     "fullName": "UWIMANA Claudine", "phone": "+250788123456", "useBefore": "2026-11-03T10:30:00Z",
     "birthdate": "1990-05-17", "gender": "Female", "email": null,
     "address": "KG 7 Ave, Kacyiru, Gasabo, Rwanda",
     "verifiedFields": ["fullName", "phone", "birthdate", "gender", "address"] }
   ```

   `birthdate` is `yyyy-MM-dd`; `birthdate`, `gender`, `email` and `address` are `null` when not released.
   `verifiedFields` names the fields that hold a verified value, in the order `fullName`, `phone`, `birthdate`,
   `gender`, `email`, `address`. The frontend shows them read-only in the new member form and leaves them out of
   the registration.
3. **Back with an error:** `POST /api/members/verify/cancel` with `{state, error}` (eSignet's `error` code;
   never its `error_description`). The state is used up and the outcome written to the change history. It
   returns `{reason, message}` with `reason` `CANCELLED` (`access_denied`), `CLAIMS_REFUSED`
   (`essential_consent_denied`: the member did not allow the name or phone) or `PROVIDER_ERROR` (anything else),
   and the same answer whether or not the state is known.
4. **Register:** `POST /api/members` with `verificationId` and the other fields before `useBefore` (30 minutes).
   Every verified detail is copied from the verification to the member, with its flag set (`verifiedFields`);
   a value for one of them in the body is refused with `VERIFIED_FIELD_LOCKED`. The national ID, join date,
   preferred language, and an address that was not verified come from the body as before.
   Only the manager who started the verification can use it, once. A registration refused for another reason
   (a phone or national ID already used) leaves the verification usable until then.

**Errors.** Identity errors have a `code` next to the `message`, for the frontend to choose its own text
(`{"message": "...", "code": "CLAIMS_MISSING"}`); other errors have no `code`.

| Code | Status | When |
|------|--------|------|
| `VERIFICATION_UNKNOWN` | 400 | The state or verification is unknown, or another manager's |
| `VERIFICATION_EXPIRED` | 400 | The state is more than 10 minutes old, or the verification more than 30 minutes |
| `VERIFICATION_USED` | 409 | The state, or the verification, was already used |
| `ALREADY_MEMBER` | 409 | This person is already a member |
| `CLAIMS_MISSING` | 400 | eSignet gave no name or no phone |
| `PHONE_NOT_RWANDAN` | 400 | The verified phone is not a Rwandan mobile number |
| `NAME_TOO_LONG` | 400 | The verified name is longer than 150 characters |
| `ACR_NOT_ALLOWED` | 400 | The id_token says the person logged in a way not in `MOSIP_ALLOWED_ACRS` (for example biometrics) |
| `VERIFIED_FIELD_LOCKED` | 400 | `POST /api/members` sent a value for a detail the check verified (`fullName`, `phone`, `birthDate`, `gender`, `email`, `address`). Message: "This detail came from the identity check and cannot be changed here."; it never repeats the verified value |
| `PROVIDER_FAILED` | 502 | eSignet failed or its answer could not be trusted |
| `NOT_CONFIGURED` | 503 | Identity verification is switched off |

`VERIFIED_FIELD_LOCKED` replaces the earlier `400` without a code for a `fullName` or `phone` in the body.
`POST /api/members` also refuses a birth date, gender or email that was not verified (`400`, no code), and a
phone or national ID that belongs to another member (`409`, no code), as before.

What is kept: eSignet's `sub` is pairwise (not a national ID number), so it is only used as `identity_token`:
one person, one membership (`ALREADY_MEMBER` for the second). The verified details (name, phone, birth date,
gender, email, address) are kept on the verification for at most 30 minutes: all cleared when the member is
saved, or by a job that runs every minute once they are older than that, so never more than about 31 minutes.
After that they live only in the member record. The change history gets `IDENTITY_VERIFIED`, `IDENTITY_DUPLICATE` and
`IDENTITY_VERIFICATION_FAILED` with the reason only (for example `missing details: phone`, or
`the member did not allow the name and phone (essential_consent_denied)`), never a name, phone or eSignet's
text. Older history rows may also show `IDENTITY_MISMATCH` from the comparison this replaced. Codes, states,
tokens, assertions, claims, the verified details and the key are never logged; the server log only gets safe
one-line descriptions of a failed call to eSignet (address without query, HTTP status, known OAuth error code).

**Login method (acr).** eSignet can offer several ways to log in (one-time code, biometrics, wallet), asked for
with `MOSIP_ACR_VALUES` (several values, space-separated, sent as `acr_values` exactly as set; starting a
verification logs one INFO line with that value, and nothing else from the URL). The local eSignet mock accepts
any biometric capture, so a biometric login proves nothing. When the ID token carries an `acr` claim, it must be
one of `MOSIP_ALLOWED_ACRS` (default `mosip:idp:acr:generated-code`, the one-time code), compared exactly;
anything else (including an empty or non-text `acr`) is refused with `ACR_NOT_ALLOWED`, before the duplicate
check, and the history reason is `the login method (acr) is not in MOSIP_ALLOWED_ACRS`. When the ID token has no
`acr`, nothing changes: it is not guessed from `acr_values`. Each callback logs one INFO line saying only whether
the `acr` claim was present (`id_token acr claim present: true|false`), never its value. Note that the default
`MOSIP_ACR_VALUES` (`mosip:idp:acr:password`) is not in the default `MOSIP_ALLOWED_ACRS`: if eSignet puts `acr` in
the ID token, set both so the method asked for is one that is allowed.

**Token checks.** Both tokens must carry exactly the expected `iss` (present, and compared character for
character: no prefix or partial match), an `aud` containing `MOSIP_CLIENT_ID`, and a `sub`; the userinfo `sub`
must be the ID token's. The two differ in these places:

| | ID token | Userinfo answer |
|---|---|---|
| Expected `iss` | `MOSIP_ISSUER`, else `MOSIP_BASE_URL` (trailing `/` removed) | `MOSIP_USERINFO_ISSUER`, else the ID token's |
| Algorithm | PS256 only | PS256 or RS256 |
| Key | a JWKS key that verifies it | the RSA key of the JWKS whose `kid` is in the token header (no `kid`, or no such key: refused) |
| `exp` | required, and not passed | optional; refused if present and passed |
| `iat` | not required (as before) | not required |

Why the userinfo answer differs: eSignet signs it with RS256 using the key its JWKS lists as PS256, and without
`exp` or `iat`. OpenID Connect Core 5.3.2 asks a signed userinfo answer for `iss` and `aud` only, and RS256 is
still RSA with the same published key, so both are accepted there; the key's `alg` in the JWKS is not compared.
`none`, HMAC (`HS*`), EC (`ES*`) and every other algorithm stay refused, for both tokens.

Why a separate userinfo issuer: the local eSignet signs the ID token with `iss` `http://localhost:8080` (its
discovery document's `issuer`) but the userinfo answer with `http://localhost:8088/v1/esignet`, so one expected
value cannot fit both. `MOSIP_USERINFO_ISSUER` names the second exactly, and changes nothing for the ID token. The
`MOSIP_ISSUER` and `MOSIP_USERINFO_ISSUER` values are only trimmed of spaces; a trailing `/` stays and must match.

**Checking the client assertion.** To match the key with the public JWK registered at eSignet, the app logs one
INFO line at startup when verification is on, for example:

```
Client assertion: alg=PS256, thumbprint=<RFC 7638 SHA-256 thumbprint>, kid=<MOSIP_KEY_ID>,
kidEqualsThumbprint=false, modulusStart=<first 8 characters of n>, aud=<audience>, lifetimeSeconds=60
```

The thumbprint and `modulusStart` come from the PUBLIC half of the key (compare them with the `n` and the
thumbprint of the JWK registered at eSignet); `kidEqualsThumbprint` only says whether `MOSIP_KEY_ID` happens to be
that thumbprint. Nothing private and never the key file's path is printed. When the token call is refused with
`401`, the usual failure line also shows the assertion that was sent, decoded: the header (`alg`, `typ`, `kid`)
and the claims (`iss`, `sub`, `aud`, `iat`, `exp` as epoch seconds and time, `jti`). Never its signature or the
whole token. The assertion is RSASSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt (PS256).

**Checking a refused id_token or userinfo answer.** When one of the checks above fails, the failure line
explains it, for example:

```
userinfo check failed: BadJwtException; failedChecks=[iss missing, aud missing], category=BadJwtException;
response HTTP 200, Content-Type application/jwt; body JWT; header {alg=PS256, kid=..., typ=(none)};
claimNames [birthdate, exp, iat, name, phone_number, sub]; iss=(none), aud=(none), exp=<epoch> (<time>),
iat=<epoch> (<time>); subMatchesIdToken=true; jwks [{kid=..., alg=PS256, kty=RSA}]
```

- `failedChecks` are the app's own checks, run again on the refused answer: `format: ...` (not a JWT, JSON instead
  of a signed JWT, encrypted), `algorithm not allowed` (not PS256; for userinfo not PS256 or RS256),
  `signature: no matching key` (no published key with that `kid`), `signature: does not verify`, `iss missing` /
  `iss different` (compared with the expected issuer of that token, shown as `iss=` for both), `aud missing` / `aud different` (must contain
  `MOSIP_CLIENT_ID`), `exp missing` (ID token only), `expired`, `not yet valid`, `sub missing`,
  `sub mismatch` (userinfo `sub` is not the id_token's). `category` is only the class of the exception that refused
  it (Spring reports several different problems as `BadJwtException`), never its message.
- The response status and Content-Type are for the userinfo call only; the body is described as JWT, JSON,
  encrypted JWT or other.
- Only the NAMES of the claims are listed. The only values shown are `iss`, `aud`, `exp` and `iat`; `sub` is
  only compared (`subMatchesIdToken`). Never a name, phone, birth date, the access token, the signature or the
  whole token.
- `jwks` lists the `kid`, `alg` and key type of every key eSignet publishes (public information).

With `MOSIP_ENABLED=false` (the default) the app starts and works as before, but `POST /api/members` is refused
with `503` "Member registration needs identity verification, which is not configured." and the status endpoint
says `{available: false, required: false}`. With `MOSIP_ENABLED=true` the app does not start if a setting is
missing or the key file cannot be used (the message names settings only). The old `require_identity_verification`
column of the `setting` table stays in the database, unused.

The automatic tests use a fake eSignet, built from what the real one was measured to send (the claim formats
above). Only the real one can confirm that it honours the `claims` parameter with `scope=openid`, the error code
when the member declines (`essential_consent_denied`), the phone format it returns, that it accepts the client assertion (`aud`, `exp`, PS256), the exact redirect URI, that the same
person always gets the same `sub`, and the `iss` of each of its tokens (set `MOSIP_ISSUER` and
`MOSIP_USERINFO_ISSUER` to match).

### Deliveries

Request body for `POST /api/deliveries`:

```json
{
  "clientUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "memberId": 1,
  "gradeId": 1,
  "quantityKg": 120.5,
  "deliveredAt": "2026-10-09T07:00:00Z"
}
```

| Field | Rules |
|-------|-------|
| `clientUuid` | Required. Created on the device, so a delivery sent twice (for example by offline sync) is saved only once |
| `memberId` | Required. The member must exist and be active, otherwise `400` |
| `gradeId` | Required. The grade must exist, otherwise `400` |
| `quantityKg` | Required. More than 0, at most 5000, at most 2 decimals |
| `deliveredAt` | Optional. When the delivery happened, for example the time on the device when it was recorded offline. At most 5 minutes in the future and at most 7 days in the past, otherwise `400`. Defaults to the time the server receives the delivery |

- The response contains the receipt code, member, grade, quantity, deduction, `deliveredAt`
  (when the delivery happened), `syncedAt` (when the server saved it) and `lots`: one entry per lot
  the delivery is in, `{lotId, lotCode, lotStatus, kg, soldKg, lostKg, remainingKg}`, oldest lot
  first. `lots` is empty while the delivery waits for confirmation. `lotId` and `lotCode` are the
  delivery's own lot (from before fixed-size lots) or its first lot.
- **Deduction:** `quantityKg` x `deduction_per_kg` from the `setting` table (5 RWF),
  rounded half up to whole RWF. For example 12.25 kg gives 61 RWF.
- **Lot:** a new delivery is in no lot. It goes into the lots of its grade when it is
  confirmed (see Lots and losses).
- **Receipt code:** `RCT-` and 4 characters without look-alikes (no 0, O, 1, I or L), for
  example `RCT-7K2Q`.
- **Same `clientUuid` again:** the existing delivery is returned with `200` and nothing new
  is saved, even if the member has been deactivated since.
- **Ledger:** each new delivery adds a `DELIVERY` ledger entry in the same transaction,
  with the kilograms, the deduction as the amount, and the receipt code in the reason.
  The member is linked through the delivery, not written into the reason, because ledger
  entries can never change.
- `from` and `to` filter by delivery day in Kigali time (`yyyy-MM-dd`, both inclusive).
- Deliveries cannot be edited or deleted. A mistake is fixed by reversing or correcting
  the delivery (see Reversals).

#### Offline sync

The app records deliveries offline and sends them later, possibly hours later. The server
follows these rules:

- **Delivery time:** `deliveredAt` from the device is kept as the delivery time.
  `syncedAt` shows when the server saved it. A time more than 5 minutes in the future (a
  device clock that is wrong) or more than 7 days in the past returns `400`.
- **Sending again is safe:** the same `clientUuid` always returns the delivery that was
  saved the first time, with `200`. It is saved once and adds one ledger entry, however
  often it is sent. This check comes first, so a re-sent delivery still gets `200` after the
  7-day window or after its member was deactivated.
- **Lot:** an offline delivery is in no lot either. It goes into the lot being filled when it is
  confirmed, whenever that is.
- **Errors that will not go away on retry** return a `4xx` status with a message, never
  `500`, so the app can show the message and stop retrying:
  - `400`: invalid body (missing fields, malformed `clientUuid`, quantity out of range,
    `deliveredAt` without a time zone or outside the time window), unknown member or
    grade, inactive member.
  - `409`: the request clashes with data already saved.

  `401` means the login has expired; the app should ask the manager to log in again and
  then retry. Network errors and `5xx` can be retried later with the same `clientUuid`.

### Lots and losses

A lot holds potatoes of **one grade** and has a **fixed size**: the lot size setting when it was
opened (100 kg by default, see Settings). The setting can change, so lots of one grade can have
different sizes; the fill level, space left, shortfall, sales and losses always use the lot's own
capacity. Nobody opens or closes a lot: the system fills them.

- **Only confirmed kg go into lots.** A delivery enters a lot when it becomes `CONFIRMED` (with the
  code, by an SMS reply, by the manager's review, or when a dispute raised before confirmation is
  resolved). Waiting deliveries, and deliveries disputed before they were confirmed, are in no lot.
- **Filling:** each grade has at most one lot being filled (`FILLING`). A confirmed delivery fills
  it up to its capacity; the lot becomes `FULL` and the rest of the delivery starts the next lot.
  One delivery can be split across two or more lots, and deliveries of different members share a
  lot. Lots are filled in the order deliveries are **confirmed**: a delivery confirmed late goes into
  the lot being filled then, and nothing ever moves between lots afterwards.
- **Statuses:** `FILLING`, `FULL`, and `SOLD` once a full lot has nothing left. Lots from before
  fixed-size lots keep their size and status (`OPEN`, `CLOSED`, `SOLD`, `SHARED`), have no capacity
  (`olderLot: true`) and get no new kg; their kg are sold first (see Sales).
- **Lot codes** are the grade code, the year the lot was opened (Kigali time) and the grade's lot
  number in that year: `A-2026-01`, `A-2026-02`, `B-2026-01`. They continue after the older lots.
- `GET /api/lots/open?gradeId=` shows the lot being filled with `totalKg` (kg in it) and
  `capacityKg`, or `204` when there is none (the next confirmed delivery of that grade starts one).
- **Quantities** are always computed from the lot's portions (the part of each delivery in the lot),
  never stored: `remainingKg = deliveredKg - lostKg - soldKg`, where `deliveredKg` is the kg in the
  lot. `capacityKg` is the lot's size; `spaceLeftKg` what a `FILLING` lot can still take;
  `shortfallKg` what reversals took out of a `FULL` or `SOLD` lot (it is never refilled).
  `saleAmountRwf` is the money of the kg sold from it. `memberCount` is the number of members with
  kg in it. `createdAt` and `closedAt` are the dates the lot was opened and became full.
- `GET /api/lots/{id}` lists `members`: each member's `kg` in the lot and how much of it was
  `soldKg`, `lostKg` and is `remainingKg`.
- `pendingConfirmationKg` is only for older lots, which can still hold kg of deliveries that are
  `PENDING`. It is shown next to the totals and does **not** change them.
- **Stock:** `GET /api/lots/stock` reads the kg left per grade from the batches, whatever their
  lot. `totalRemainingKg` is split into `totalSellableKg` (confirmed), `totalWaitingKg` (pending
  confirmation) and `totalDisputedKg`; `byGrade` lists every grade with kg left with `remainingKg`,
  `sellableKg`, `waitingKg` and `disputedKg`. Only sellable kg can be sold. `totalDeliveredKg`,
  `totalSoldKg` and `totalLostKg` cover all lots (so delivered - lost - sold = remaining),
  `addedTodayKg` and `deliveriesToday`
  (deliveries whose time falls on today in Kigali), `openLots` (lots not full yet: `FILLING`, and
  older `OPEN` lots) / `closedLots` (full lots not sold out: `FULL`, and older `CLOSED` lots), and
  `pendingConfirmationKg` (all `PENDING`, not reversed deliveries; already included in the
  totals). The stock reads each delivery's own grade, so waiting kg (in no lot) are counted too.
- **Losses** are for losses after intake; at intake the manager sorts the potatoes and records only
  the good kg. Body `{"quantityKg", "reason", "recordedAt", "notes"}`. `reason` is `ROT`, `DRYING`,
  `DAMAGE`, `THEFT` or `OTHER`; `recordedAt` (a date) and `notes` are optional. Allowed on lots that
  still hold potatoes: `FILLING`, `FULL`, and older `OPEN` and `CLOSED` lots (otherwise `409`).
  `quantityKg` must be more than 0 and at most the lot's **confirmed** kg left (otherwise `400`
  "Only X kg of confirmed potatoes remain in lot Y."). Each loss adds one `LOSS` ledger entry.
- **Who bears a loss:** the members whose potatoes are in the lot, never the cooperative. A loss is
  shared across the lot's portions in proportion to what each has **left**: kg already sold never
  carry a loss, and neither do kg still waiting for confirmation; disputed kg do, because they are
  still in the lot. The split is in hundredths of a kg: each portion gets its share rounded down,
  and the hundredths still missing go to the largest remainders, then to the older delivery. Each
  part is a `loss_allocation` of that portion. The members earn nothing on lost kg and pay no
  deduction on them. Example: 10 kg lost from a lot where three members have 18.75, 31.25 and 20 kg
  left gives 2.68, 4.46 and 2.86 kg.

### Buyers

Body for `POST /api/buyers`: `{"name", "phone", "type"}`. `name` and `phone` are required;
`type` is optional free text (at most 30 characters), for example `Trader`. The phone
number follows the same rules as for members and is stored as `+2507XXXXXXXX`. A phone
number that already belongs to a buyer returns `409`.

### Public side for buyers

Buyers do not log in. Only `/api/public/**` (plus login, health, the SMS webhook and Swagger) is
open; everything else needs the manager's token. CORS allows only the frontend (`FRONTEND_URL`).

- `GET /api/public/grades` shows the active grades with `code`, `name`, `nameRw`, `description`,
  `sizeInfo`, `packaging` and a stock hint `availability`. It never shows prices, ids or any
  stock number.
  - `availability` comes from the grade's **sellable** kg as on the stock page (confirmed batches
    with kg left; kg waiting for confirmation or disputed are not counted, so a buyer is never
    promised kg that cannot be sold): `NONE` below the cooperative's `minOrderKg`, `LIMITED` from
    `minOrderKg` up to just under 500 kg, `AVAILABLE` from 500 kg. A grade with no sellable kg is
    `NONE`. The 500 kg is one constant, `StockAvailability.AVAILABLE_FROM_KG` in
    `src/main/java/com/coopaggregate/publicapi/StockAvailability.java`.
  - At most 60 calls per minute per IP address (`429` with `Retry-After`). The response has
    `Cache-Control: max-age=60, public`, so browsers may keep it for a minute.
- `GET /api/public/info` shows `cooperativeName`, `location`, `cooperativeEmail`, `cooperativePhone` and
  `minOrderKg`. Nothing about members, stock, prices or the ledger.
- `POST /api/public/requests` sends a purchase request:

  ```json
  {"name": "Kalisa Traders", "phone": "0788123456", "email": "kalisa@example.com",
   "gradeCode": "A", "quantityKg": 250, "note": "Pick up on Friday",
   "contactMethod": "WHATSAPP", "whatsappNumber": "0788999999", "website": ""}
  ```

  `name`, `phone`, `gradeCode` and `quantityKg` are required. The phone is a Rwandan mobile
  number (`07XXXXXXXX`, `2507XXXXXXXX` or `+2507XXXXXXXX`, stored as `+2507XXXXXXXX`).
  `quantityKg` must be at least `minOrderKg` (otherwise `400` "The smallest order is 100 kg.") and the
  grade must be an active one. Sizes are limited (name 150, email 150, note 1000 characters).
  `contactMethod` and `whatsappNumber` are optional. `contactMethod` is `CALL` (the default) or
  `WHATSAPP`. `whatsappNumber` follows the same Rwandan phone rules as `phone` (an empty value counts
  as not given) and is only used for `WHATSAPP`: when it is missing, the main `phone` is used. For
  `CALL` it is ignored. Any other `contactMethod` returns `400`.
  It returns `201` `{code, status}` with a code like `BR-2026-7K2Q`: random, unique, and made
  without letters and digits that look alike (no 0, O, 1, I or L). The request starts as `PENDING`.
- `GET /api/public/requests/{code}?phone=0788123456` returns `{code, status, message, contactMethod,
  contactNumber}`: the status (`PENDING`, `ACCEPTED` or `REJECTED`), the manager's public message, and
  how the cooperative will contact the buyer: `contactMethod` and the number it will use
  (`contactNumber`: the WhatsApp number for `WHATSAPP`, otherwise the phone), both given by the buyer.
  Requests made before this existed are `CALL`. The phone must be
  the one used for the request. A wrong phone, a missing phone and an unknown code all return the
  same `404` `{"message": "Request not found."}`, so a code cannot be tested without its phone.
- **Protection:** at most 10 requests per hour per IP address and 3 per day per phone number
  (`429` with a `Retry-After` header); at most 30 lookups per 10 minutes per IP address. The counters
  are kept in memory and reset when the app restarts. `website` is a **honeypot**: hide it on the
  page, and a bot that fills it in gets a normal answer with a code that belongs to no request;
  nothing is saved. Names, phone numbers, emails and IP addresses are never written to the logs.
  The server reads the real client address from `X-Forwarded-For` behind Render's proxy
  (`server.forward-headers-strategy=native`).
- A public request does not create a buyer record, so strangers cannot fill the buyer list. The
  contact details stay on the request until the manager converts it.

**Manager (JWT):**
- `GET /api/buyer-requests` lists requests newest first, 15 per page. `status` filters by
  status; `search` matches the request code, the buyer's name or phone (`0788123456` finds
  `+250788123456`).
- `GET /api/buyer-requests/{id}` shows one request with the buyer's contact details and note. The list
  and the detail both show `contactMethod` (`CALL` or `WHATSAPP`) and `whatsappNumber` (the number to
  use for `WHATSAPP`, empty for `CALL`, when `buyerPhone` is the number to call).
- `PATCH /api/buyer-requests/{id}/status` with `{"status", "publicMessage"}` changes the status. The
  public message (at most 500 characters) is what the buyer reads; leaving it out keeps the earlier
  one. The same status without a new message returns `409`. Every change goes to the change
  history (`GET /api/settings/history`) as `BR-2026-7K2Q: PENDING -> ACCEPTED`, without the
  message text or any personal data.
- `POST /api/buyer-requests/{id}/buyer` turns the request into a buyer record, or links it to the
  buyer who already has that phone number (`409` if it already has a buyer). Then record the sale
  for that buyer with `POST /api/sales/orders`; sales are not linked to the request automatically.

### Batches

Every delivery is a batch for its member: `receivedKg`, `soldKg`, `lostKg` and `remainingKg`
(received - sold - lost, computed by the database), the sums of its portions. Each batch also has
`lots`, one entry per lot it is in (empty while it waits for confirmation). Reversed deliveries are
not batches.

- `GET /api/members/{id}/batches?status=all|unsold&page=0&size=20`: the member's batches, newest
  first, with the kg `totals` and the money `earnings` over all of them. Each batch has `grossRwf`,
  `deductionRwf`, `earnedRwf` (what sales credited for its sold kg) and `payment`: `NOTHING_SOLD`,
  `BATCH`, or, only for sales made before sales by grade, `LOT_SHARE` or `AWAITING_SETTLEMENT`.
- `GET /api/grades/{id}/batches`: the grade's batches that still have kg, oldest delivery first.

### Sales

Sales are made by **grade**, first in, first out **by lot**:

1. The lots from before fixed-size lots first, oldest delivery first (delivery time, then id), as
   before.
2. Then the fixed-size lots in the order they were filled. A sale may take part of a lot, and any
   quantity is allowed: 150 kg from 100 kg lots is the first lot whole and half of the next. A lot
   still being filled can be sold from and keeps filling.

Inside a fixed-size lot the kg are shared across its portions **in proportion to what each has
left** (the lot is one pool), in hundredths of a kg with the largest remainder first and ties to
the older delivery. Only confirmed kg are sold: kg disputed after they went into a lot stay in it
but are held back until the dispute is settled.

Request body for `POST /api/sales`:

```json
{
  "clientUuid": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "gradeId": 1,
  "buyerId": 2,
  "quantityKg": 150,
  "pricePerKg": 300,
  "soldAt": "2026-10-09",
  "skipLotIds": [12, 15],
  "skipReason": "The buyer wants the newer potatoes"
}
```

- The grade and the buyer must exist, `quantityKg` must be more than 0 (2 decimals) and
  `pricePerKg` more than 0 in whole RWF, and more than the deduction per kg (otherwise `400`).
  `soldAt` is an optional date and defaults to today (Kigali time).
- With fewer sellable kg than asked, the answer is `400` "Only 250.00 kg of Big can be sold now, but
  300.00 kg were asked for. 40.00 kg are waiting for their members to confirm them." and nothing is
  saved.
- **Preview:** `GET /api/grades/{id}/sale-preview?quantityKg=150&pricePerKg=300&skipLots=12,15`
  shows the portions the sale would use (`batches`, one line per portion, with `lotPortionId`: a
  delivery split across two lots can appear twice) and what each member would be credited, and
  saves nothing. `skipped` lists the skipped lots `{lotId, lotCode, sellableKg}`. The sale works it
  out again under locks when it is recorded, so it can differ if stock changed in between.
- **Skipping whole lots** is allowed with `skipLotIds` and a `skipReason` of 10 to 500 characters
  (otherwise `400`). Only a lot of the grade with sellable kg that the sale would otherwise reach can
  be skipped. Every skip is written to the audit log as `SALE_FIFO_SKIPPED` ("Sale SAL-7K2Q: skipped
  lots A-2026-03. Reason: ..."). Single batches can no longer be skipped: `skipDeliveryIds` in a sale
  and `skip` in the preview are refused with `400`.
- **Credits:** each portion used credits its member. `grossRwf` is the portion's part of the sale
  amount and `deductionRwf` its part of the deduction (5 RWF per **sold** kg, the rate from
  Settings, rounded once for the whole sale), both split by kg, so the lines add up to exactly the
  sale amount and the sale deduction. A deduction is never more than its gross. `netRwf = grossRwf -
  deductionRwf` is added to what the member is owed. Example: 130 kg at 300 RWF from a full lot of
  60 + 40 kg and 30 kg of the next lot, where two members have 30 and 50 kg: 60 kg (18,000 - 300),
  40 kg (12,000 - 200), 11.25 kg (3,375 - 56) and 18.75 kg (5,625 - 94); 39,000 RWF and 650 RWF in
  all.
- **Ledger:** one `SALE` entry (kilograms and the buyer's total), then one `SALE_CREDIT` entry per
  portion used (kilograms and the net amount, `relatedEntityType` `SALE_ALLOCATION`). A portion's
  part of a sale is credited once: the database refuses a second credit.
- The response has the sale (`id`, `saleCode`, `gradeId`, `gradeName`, `buyerName`, `quantityKg`,
  `pricePerKg`, `totalAmountRwf`, `belowFloor`, `soldAt`, `totalDeductionRwf`, `totalNetRwf`) and
  `batches`: for each portion used `receiptCode`, `lotId` and `lotCode` (the portion's lot),
  `memberNumber`, `memberName`, `kg`, `grossRwf`, `deductionRwf` and `netRwf`. The sale's own
  `lotId`, `lotCode` and `lotStatus` are only set for sales made before sales by grade; for those,
  the money fields are empty.
- `belowFloor` is `true` when the grade has a floor price and `pricePerKg` is lower. It is
  only a warning: the sale is saved either way.
- A `FULL` lot (or an older lot) that has nothing left becomes `SOLD`. A lot being filled stays
  `FILLING`.
- Sending the same `clientUuid` again returns the first result with `200`.
- Locks are always taken in this order: the grade, the lots that have sellable kg (by id), their
  portions, the deliveries, and last the ledger. Two sales of one grade wait for each other.
  Confirmations, losses, reversals and corrections take the grade first too, so none of them can
  deadlock with a sale.
- Sale codes look like `SAL-7K2Q`.
- `POST /api/sales/orders` takes `{"clientUuid", "buyerId", "soldAt", "items": [{"gradeId",
  "quantityKg", "pricePerKg", "skipLotIds", "skipReason"}]}`, each grade at most once, and
  records one sale per item with an order code `ORD-7K2Q`. If any item fails, nothing is saved.
- `GET /api/sales` filters: `search` matches part of the buyer name, a lot code (also those of a
  sale's batches) or sale code (ignoring case), and a number on its own also matches the sale id.
  `gradeId` keeps sales of that grade and `lotId` those that took kg from that lot.
- `GET /api/sales/summary` covers the current month in Kigali time:
  `salesAmountThisMonth` (RWF), `soldKgThisMonth`, `buyersThisMonth` (different buyers) and
  `averagePricePerKg` (amount / kg, 2 decimals, `0` when nothing was sold).

### Sharing

This is the older way of paying members: when a lot was `SOLD`, its sale money was shared
between everyone who delivered to it. Sales are now paid to the members by batch when they are made
(see Sales), so sharing is refused with `409` for any lot that has sales, and it only remains for
lots shared before. The endpoints are kept as they were.

`GET /api/lots/{id}/share-preview` shows the result without saving; `POST /api/lots/{id}/share`
saves it and sets the lot to `SHARED`.

- A lot that has sales (every `SOLD` lot) is refused with `409`: its members are paid by batch, and
  sharing as well would pay them twice. A lot that is already `SHARED` returns `409`, and a lot
  that is not `SOLD` returns `400`.
- Only confirmed deliveries are shared. While the lot has `PENDING` or `DISPUTED`
  deliveries (not reversed), both the preview and the share return `409` with the list:

  ```json
  {"message": "Lot A-2026-01 has deliveries that are not confirmed yet: RCT-7K2Q (PENDING). ...",
   "deliveries": [{"id": 42, "receiptCode": "RCT-7K2Q", "memberId": 5, "memberName": "Uwimana Claudine",
                   "memberNumber": "M-0005", "quantityKg": 120.50, "status": "PENDING"}]}
  ```

  Confirm, resolve, correct or reverse them first.
- `shareRwf = memberKg / deliveredKg x saleAmountRwf`, rounded to whole RWF. The rounding
  remainder goes to the member with the most kg, so the shares add up to exactly the sale
  amount. Shares use delivered kg, so lost kg are carried by everyone in proportion.
- `deductionRwf` is the deduction already recorded on the member's deliveries to that lot
  (kg x 5 RWF, the rate from Settings). `netRwf = shareRwf - deductionRwf`.
- Example: a lot of 10,000 kg sold for 2,952,000 RWF. Members with 5,000 / 3,000 / 2,000 kg
  get 1,476,000 / 885,600 / 590,400 RWF.
- Each member's share adds a `SHARE` ledger entry with the kilograms and the gross share.

### Payments

Payments are made to a member for everything they are owed, not to one lot. What a member
is owed (their balance) = their sale credits and shares - their deductions - what they were
already paid.

Request body for `POST /api/payments`:

```json
{
  "clientUuid": "3f2b7c1e-9a4d-4e8b-b1c2-5d6e7f8a9b0c",
  "memberId": 1,
  "amountRwf": 451000,
  "method": "MOMO",
  "reference": "MP2610120001",
  "paidAt": "2026-10-12"
}
```

- `method` is `CASH`, `MOMO` or `BANK`. `MOMO` and `BANK` payments need `reference`, the
  MoMo or bank transaction number (otherwise `400`).
- Each payment gets its own code, `PAY-` and 4 digits (`PAY-0001`), the highest existing
  number plus one. It is separate from `reference`.
- `amountRwf` must be more than 0 and at most what the member is owed (otherwise `400`).
- `paidAt` is an optional date and defaults to today (Kigali time).
- Sending the same `clientUuid` again returns the existing payment with `200`.
- Each payment adds a `PAYMENT` ledger entry with the amount and the payment code.
- `GET /api/payments/balances` lists every member with at least one sale credit or shared lot:
  `shareRwf` (gross credits and shares), `deductionRwf`, `paidRwf`, `balanceRwf` and `status`,
  largest balance first.
  `status` is `UNPAID` (nothing paid yet), `PART_PAID` or `PAID` (balance 0).
  `search` (name, member number or phone) and `status` filter the list only:
  `totalOwedRwf` (all balances) and `paidThisWeekRwf` (payments dated Monday to Sunday of the
  current week, Kigali time) always cover all members.

### Member statement

`GET /api/members/{id}/statement` returns `grossShareRwf`, `deductionsRwf`, `paidRwf` and
`owedRwf` (the first two add up lot shares and sale credits), the member's `payments` (newest
first), `saleCredits` (for each: `saleCode`, `soldAt`, `receiptCode`, `lotCode`, `kg`,
`pricePerKg`, `grossRwf`, `deductionRwf`, `netRwf`; `lotCode` is the lot the kg came from) and
`lotShares`: every lot from before fixed-size lots the member
delivered to, newest first. A shared lot shows the saved kg, percent and `shareRwf`. A lot
that is not shared yet shows its status (`OPEN`, `CLOSED` or `SOLD`), the current kg and
percent, and `shareRwf: null`, so the app can show "Waiting for sale" or "Waiting for
sharing".

### Weekly report

`GET /api/reports/weekly?weekStart=YYYY-MM-DD` shows one week, Monday to Sunday in Africa/Kigali
time. `weekStart` must be a Monday (otherwise `400`, for example "weekStart must be a Monday, for
example 2026-10-19.") and not a future week (`400`). Without it, the current week is shown.

| Section | Contents |
|---------|----------|
| `period` | `weekStart`, `weekEnd` |
| `deliveries` | `totalKg`, `deliveries` (count), `byGrade` (kg and count per grade) and `byMember` (member number, name, kg per grade and total kg, highest total first) |
| `stock` | `remainingKgNow` and `remainingByGradeNow` (the same as the stock page, at the time of the request), `lostKg` and `lossesByReason` for the week |
| `sales` | one line per sale order (or per sale recorded on its own): code, date, buyer, lot codes, kg and RWF; `totalKg`, `totalAmountRwf`, `averagePricePerKg` |
| `money` | `sharedRwf`, `shareDeductionsRwf`, `sharedNetRwf`: lots shared in the week plus the sale credits made in the week; `paidRwf` and `paidByMethod` (CASH, MOMO, BANK); `owedNowRwf` |
| `confirmations` | `confirmedThisWeek` and `confirmedByMethod`; `pendingFromThisWeek` (the week's deliveries still pending), `pendingNow`, `disputedNow`; `disputesSettledThisWeek` |

- **Same numbers as elsewhere:** kg per grade use the dashboard's query, stock comes from the stock
  page, sale totals and the average price from the sales summary, paid and owed from the balances,
  and open disputes from the dashboard. Reversed deliveries and payments are left out everywhere;
  sales, losses and shares cannot be reversed.
- **Which date counts:** deliveries by delivery time, sales by sale date, payments by payment date
  and losses by loss date, as on the other pages. Three things have no such date, so they use
  another moment: shares use the time the lot was shared, sale credits the time of the sale, `confirmedThisWeek` uses the time of the
  confirmation (it may confirm a delivery from an earlier week), and `disputesSettledThisWeek` uses
  the time the dispute was resolved or the delivery reversed.
- **Week boundary:** a delivery at 23:59 on Sunday belongs to that week, one at 00:00 on Monday to
  the next. Amounts are whole RWF; kg keep their 2 decimals.
- All totals are added up in the database; only the week's sales are loaded to list them.

`GET /api/reports/weeks` returns the weeks of the last 52 (this one included) that have any
delivery, sale, payment, loss or share, newest first, so the frontend can offer them.

### Interactive docs (Swagger)

- Local: http://localhost:8080/swagger-ui.html
- Live: https://coopaggregate-backend.onrender.com/swagger-ui.html

To call protected endpoints from Swagger:

1. Open **Auth** > `POST /api/auth/login`, click **Try it out**, enter the manager
   email and password, and click **Execute**.
2. Copy the `token` value from the response (without the quotes).
3. Click **Authorize** at the top of the page, paste the token into the
   `bearerAuth` field, and click **Authorize**, then **Close**.
4. Protected endpoints such as `GET /api/auth/me` and the **Ledger** and **Members** endpoints now
   send the token. The token is valid for 12 hours; after that, log in again.

## Deployment

The API is deployed on Render as a Web Service built from the `Dockerfile`.

- Region: Frankfurt (same region as the Neon database)
- Plan: Free (512 MB)
- Auto-deploy: on every push to the `main` branch. Work in progress goes on the
  `dev` branch.

Environment variables to set in Render:

| Variable | Value |
|----------|-------|
| `DATABASE_URL` | JDBC URL of the Neon database |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | At least 32 characters |
| `FRONTEND_URL` | `https://coopaggregate-frontend.onrender.com` |
| `MANAGER_NAME` | Name of the manager account |
| `MANAGER_EMAIL` | Login email of the manager account |
| `MANAGER_PASSWORD` | Login password of the manager account |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC` |
| `SMS_PROVIDER` | `africastalking` to send real SMS, otherwise leave it unset |
| `AT_USERNAME`, `AT_API_KEY`, `AT_SENDER_ID` | Africa's Talking account details |
| `SMS_TRACCAR_TOKEN`, `SMS_TRACCAR_URL` | Only with `SMS_PROVIDER=traccar`: the gateway token and, if different, its `https` URL |
| `SMS_INBOUND_SECRET` | Long random string, also used in the SMS callback URL |
| `MOSIP_*` | To register members, with a real eSignet over `https`: `MOSIP_ENABLED`, `MOSIP_BASE_URL`, `MOSIP_CLIENT_ID`, `MOSIP_REDIRECT_URI`, `MOSIP_PRIVATE_KEY_PATH` (a secret file mounted on the service) and `MOSIP_KEY_ID` |

Never set `SMS_LOG_SHOW_CODES` on Render: it is for local development only, and the app
refuses to start if it is `true` with any provider but `log`.

Do not set `PORT`; Render sets it. `JAVA_TOOL_OPTIONS` keeps the JVM within the
512 MB memory limit of the free plan.

## Testing

Windows:

```powershell
.\mvnw.cmd test
```

macOS / Linux:

```bash
./mvnw test
```

The tests do not need a database. They cover the ledger hash chain, the ledger service
(recording, reversal and verification, with a mocked repository), the login service
(including the lockout after 5 failures), the login endpoint, Rwandan phone number
validation, the member service (create, update, duplicates, not found, activate and
deactivate), the member endpoints (validation errors, `401` without a token), recording
deliveries (deduction, no lot until confirmed, idempotency, ledger entry), fixed-size lots (filling
in confirmation order, splitting a delivery across lots, changing the lot size, lots of two sizes in one grade, shortfall and
space left, stock per grade), losses (shared in proportion, exact rounding, sold kg carry none),
batches, buyers and sales by grade (older lots first, then lots in fill order shared in proportion,
awkward numbers that must add up exactly to the ledger entries, skipping whole lots with a reason,
lock order, `SOLD` at 0, floor price warning, idempotency), reversals of deliveries in lots, sharing (the 10,000 kg
example, rounding remainder, refused for lots with sales), payments (owed math,
overpayment, MoMo reference, idempotency), member statements and identity verification (the key file
and startup checks, PKCE, the signed assertion, the token and userinfo checks with a fake eSignet, the claims
request, the verified name and phone and their 30 minutes, one manager and one use per verification, cancelling,
the error codes, and that nothing personal is logged or kept longer). What only the real eSignet can show is listed in Identity verification.

`LotFillingConcurrencyTest` runs on a real, **empty, local** Postgres and is skipped otherwise:
many confirmations of one grade at once, and confirmations, sales, losses, reversals, corrections
and disputes side by side while the lot size changes, with no lot over its size, no deadlock, every
total matching, every read model agreeing with the tables, and the ledger still verifying; and lots of
two sizes in one grade through filling, a sale, a loss, a reversal and the weekly report. It refuses any host but localhost:

```bash
./mvnw test -Dtest=LotFillingConcurrencyTest -Dcoopaggregate.test.postgres-url=jdbc:postgresql://localhost:5432/<empty-db>
```

`docs/fixed-lots-swagger-test.md` walks through the lot rules in Swagger with the numbers to expect.

## Author

Emerance Umurerwa - BSc Software Engineering, African Leadership University

Supervisor: Bernard Lamptey
