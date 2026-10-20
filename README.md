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
| `SMS_PROVIDER` | No | `log` (default: SMS are only saved in `sms_log`) or `africastalking` |
| `AT_USERNAME` | With Africa's Talking | Africa's Talking username; `sandbox` for their sandbox |
| `AT_API_KEY` | With Africa's Talking | Africa's Talking API key |
| `AT_SENDER_ID` | No | Sender name shown on members' phones, if your account has one |
| `SMS_INBOUND_SECRET` | For SMS replies | Long random string that the SMS reply webhook must send (see SMS) |
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

## API endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/health` | None | Returns `{"status":"OK"}` |
| POST | `/api/auth/login` | None | Body `{"email", "password"}`. Returns `{token, expiresAt, managerName}` |
| GET | `/api/auth/me` | JWT | Returns `{name, email}` of the logged-in manager |
| POST | `/api/auth/change-password` | JWT | Body `{"currentPassword", "newPassword"}`. Returns `204` (see Authentication) |
| GET | `/api/settings` | JWT | `{smsEnabled, cooperativeEmail, location, minOrderKg, committeeEmails, deductionPerKg}` |
| PUT | `/api/settings` | JWT | Saves the settings (see Settings and change history) |
| GET | `/api/settings/history?page=0&size=20` | JWT | Change history, newest first (max 100 per page) |
| GET | `/api/ledger?page=0&size=20` | JWT | Ledger entries, newest first (max 100 per page) |
| GET | `/api/ledger/verify` | JWT | Recomputes every hash. Returns `{valid, checkedEntries, firstBrokenEntryId}` |
| POST | `/api/ledger/{id}/reverse` | JWT | Body `{"reason"}` (required). Reverses the entry through its business action (see Reversals) and returns the REVERSAL entry |
| GET | `/api/members?search=&active=&page=0&size=20` | JWT | Members sorted by name (max 100 per page). `search` and `active` are optional |
| GET | `/api/members/summary` | JWT | Counts: `{total, active, inactive, joinedThisMonth}` |
| GET | `/api/members/{id}` | JWT | One member |
| POST | `/api/members` | JWT | Registers a member and returns it with `201` |
| PUT | `/api/members/{id}` | JWT | Updates a member's details |
| PATCH | `/api/members/{id}/deactivate` | JWT | Sets the member's status to `INACTIVE` |
| PATCH | `/api/members/{id}/activate` | JWT | Sets the member's status to `ACTIVE` |
| POST | `/api/members/{id}/pin` | JWT | Body `{"pin"}`. Sets or resets the member's 4-digit PIN; a reset unlocks it |
| GET | `/api/grades?includeInactive=false` | JWT | Grades `{id, code, name, nameRw, active, floorPricePerKg, description, sizeInfo, packaging}`, sorted by code. Only active ones unless `includeInactive=true`. `floorPricePerKg` is `null` when not set |
| PUT | `/api/grades/{id}` | JWT | Body `{"name", "nameRw", "active", "floorPricePerKg", "description", "sizeInfo", "packaging"}`. Edits a grade. Grades A, B and C are fixed: they cannot be created or deleted |
| GET | `/api/lots?status=&gradeId=&page=0&size=20` | JWT | Lots, newest first (max 100 per page), with delivered, lost, sold and remaining kg |
| GET | `/api/lots/{id}` | JWT | One lot, with the kilograms each member delivered |
| GET | `/api/lots/stock` | JWT | Stock balance: remaining, delivered, sold and lost kg, today's deliveries, lot counts and remaining kg per grade |
| GET | `/api/lots/open?gradeId=` | JWT | Open lot of the grade `{id, code, gradeName, totalKg}`. `204` if none is open yet, `404` if the grade does not exist |
| PATCH | `/api/lots/{id}/close` | JWT | Closes an open lot. The next delivery of that grade starts a new lot |
| POST | `/api/lots/{id}/losses` | JWT | Records a loss on an open or closed lot, returns `201` |
| GET | `/api/lots/{id}/losses` | JWT | Losses of a lot, newest first |
| GET | `/api/buyers?search=` | JWT | Buyers sorted by name. `search` matches name or phone |
| POST | `/api/buyers` | JWT | Adds a buyer, returns `201` |
| POST | `/api/sales` | JWT | Records a sale from a closed lot. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/sales?search=&lotId=&buyerId=&gradeId=&page=0&size=20` | JWT | Sales, newest first (max 100 per page). All filters are optional |
| GET | `/api/sales/summary` | JWT | `{salesAmountThisMonth, soldKgThisMonth, buyersThisMonth, averagePricePerKg}` |
| GET | `/api/lots/{id}/share-preview` | JWT | How a sold lot's money would be shared, per member. Saves nothing |
| POST | `/api/lots/{id}/share` | JWT | Saves the shares and marks the lot `SHARED`. Only once per lot |
| GET | `/api/payments/balances?search=&status=` | JWT | Share, deductions, paid and balance per member with a shared lot, plus `totalOwedRwf` and `paidThisWeekRwf` |
| POST | `/api/payments` | JWT | Pays a member. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/payments?memberId=&method=&page=0&size=20` | JWT | Payments, newest first (max 100 per page). Reversed payments are listed with `reversed: true` |
| POST | `/api/payments/{id}/reverse` | JWT | Body `{"reason"}`. Cancels a payment; the member is owed the amount again |
| GET | `/api/members/{id}/statement` | JWT | A member's shares, deductions, payments, amount owed and lots waiting for sharing |
| POST | `/api/deliveries` | JWT | Records a delivery. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/deliveries?memberId=&lotId=&from=&to=&includeReversed=false&page=0&size=20` | JWT | Deliveries, newest first (max 100 per page). All filters are optional. Reversed deliveries only with `includeReversed=true` |
| GET | `/api/deliveries/{id}` | JWT | One delivery, also when it was reversed |
| POST | `/api/deliveries/{id}/reverse` | JWT | Body `{"reason"}`. Cancels a delivery recorded by mistake |
| POST | `/api/deliveries/{id}/correct` | JWT | Body `{"reason", "memberId", "gradeId", "quantityKg"}`. Replaces a wrong delivery, returns the corrected one with `201` |
| POST | `/api/deliveries/{id}/confirm` | JWT | Body `{"pin"}`. Confirms a pending delivery with the member's PIN |
| POST | `/api/deliveries/{id}/dispute` | JWT | Body `{"reason", "note"}`. Records that the member says the delivery is wrong |
| POST | `/api/deliveries/{id}/resolve-dispute` | JWT | Body `{"note"}`. Closes a dispute after checking the delivery is correct |
| POST | `/api/sms/inbound?token=` | Secret | Webhook for members' SMS replies (see SMS) |
| GET | `/api/dashboard` | JWT | Numbers for the home screen, including disputed deliveries and confirmations pending for more than 48 hours |

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
- `cooperativeEmail` and `location` are optional. `minOrderKg` (default 100) must be more than 0.
- `committeeEmails` holds at most 10 valid emails. They are saved in lowercase without
  duplicates, in the `committee_emails` column of the `setting` table, separated by commas.
- Every change to the settings or a grade, every password change and every member PIN
  set or reset is written to the change history (`GET /api/settings/history`) with the
  manager and the time, for example `smsEnabled: true -> false`. Passwords and PINs are
  never written to it.

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

Every delivery, loss, sale, share and payment adds a ledger entry. Entries can never
be changed or deleted (a database trigger blocks it). Each entry stores a SHA-256 hash
of its own data plus the previous entry's hash, so changing any past entry breaks the
chain and `GET /api/ledger/verify` reports the first broken entry. Mistakes are fixed
with a REVERSAL entry that cancels the original. A reversal cannot be reversed, and an
entry can only be reversed once.

### Member PIN and delivery confirmation

Each member can have a 4-digit PIN to confirm their deliveries.

- **PIN:** `POST /api/members/{id}/pin` sets or resets it. Exactly 4 digits; 4 equal digits
  or digits in a row (`1234`, `4321`, ...) are refused. Only a BCrypt hash is stored and the
  PIN is never returned or logged. Member responses show `hasPin` and `pinLocked`. Every set
  and reset is written to `audit_log`.
- **Confirming:** `POST /api/deliveries` accepts an optional `confirmationPin`, typed by the
  member on the manager's device. This also works offline: the PIN travels with the
  delivery when it syncs.
  - Right PIN: the delivery is `CONFIRMED` (method `PIN`).
  - No PIN, or the member has none: `PENDING`.
  - Wrong PIN: the delivery is **still saved** (`PENDING`, `pinMismatch: true`) and the
    attempt is counted. 3 wrong PINs in a row lock the PIN until the manager resets it.
- `POST /api/deliveries/{id}/confirm` confirms a `PENDING` delivery later with the PIN
  (wrong PIN `400` with the attempts left, locked PIN `409`).
- **Disputes:** `POST /api/deliveries/{id}/dispute` records a member's complaint
  (`WRONG_KG`, `WRONG_GRADE`, `NOT_MY_DELIVERY` or `OTHER`, with an optional note).
  - If the record is right: `POST /api/deliveries/{id}/resolve-dispute` with a note puts it
    back to `CONFIRMED` (method `MANAGER_REVIEW`).
  - If it is wrong: correct or reverse it (see Reversals); that also closes the dispute.
  - A lot cannot be shared while it has open disputes: `POST /api/lots/{id}/share` returns
    `409` listing them.
- Deliveries recorded before confirmations existed are `CONFIRMED` with method
  `RECORDED_BEFORE_CONFIRMATION`, so they are not counted as waiting. The member never
  confirmed them. Each delivery also has `confirmationLabel`, the text to show, for example
  "Recorded before confirmation existed" or "Confirmed by member with PIN".

### SMS

After a delivery is saved, the member gets one SMS in their language (`rw` or `en`):

- Confirmed: "CoopAggregate: you confirmed RCT-7K2Q, 300 kg Big, 19/10. If this was not
  you, reply 2 RCT-7K2Q."
- Pending: "CoopAggregate: RCT-7K2Q, 300 kg Big, 19/10. Reply 1 RCT-7K2Q if correct,
  2 RCT-7K2Q if wrong."

The SMS is sent in the background after the delivery is committed, so a failed SMS never
fails or slows a delivery. Every message is saved in `sms_log` (`QUEUED`, `SENT` or
`FAILED`) and failed ones are retried every 5 minutes, up to 5 attempts. No SMS are sent
when SMS are turned off in the settings.

`SMS_PROVIDER=log` (default) sends nothing; read the messages in `sms_log`.
`SMS_PROVIDER=africastalking` sends them with Africa's Talking (`AT_USERNAME=sandbox` uses
their sandbox and simulator).

**Replies:** in the Africa's Talking dashboard, set the SMS callback URL to
`https://<api-host>/api/sms/inbound?token=<SMS_INBOUND_SECRET>`. The secret can also be sent
in the `X-Sms-Secret` header; without the right secret the webhook answers `403`.

- `1 RCT-7K2Q` confirms a pending delivery (method `SMS`).
- `2 RCT-7K2Q <note>` disputes it (reason `OTHER`, with the note), also when it was already
  confirmed ("this was not me").
- A reply only counts when it comes from the phone of the member who owns that delivery;
  everything else is ignored.

### Reversals

A reversal changes the business data and the ledger together, in one transaction, so
the totals and the ledger always agree. The reversed delivery or payment stays in its
table for the history (`reversed: true`, `reversalReason`) but is no longer counted
anywhere: lot kilograms, stock, the dashboard, sharing, member statements and balances.

- **Delivery:** `POST /api/deliveries/{id}/reverse`. Only while the delivery's lot has no
  sales, because sold potatoes and shared money already depend on the lot's kilograms
  (otherwise `409`). Also `409` when the delivery is already reversed, or when the lot's
  recorded losses would leave it with less than 0 kg.
- **Correction:** `POST /api/deliveries/{id}/correct` reverses the delivery and saves the
  corrected one in the same transaction: new receipt code, the open lot of its grade, the
  same `deliveredAt`, its own `DELIVERY` ledger entry. The two are linked by `correctsId`
  (on the new delivery) and `correctedById` (on the original). Same rules as a reversal.
- **Payment:** `POST /api/payments/{id}/reverse`. The member's balance goes back up.
- **Ledger:** `POST /api/ledger/{id}/reverse` does the same as the endpoints above for
  `DELIVERY` and `PAYMENT` entries. `SHARE`, `SALE` and `LOSS` entries cannot be reversed
  (`409`): a share has already divided the money, a sale cannot be undone once its lot is
  shared, and a loss cannot be undone once its lot has sales. Sales and losses cannot be
  reversed at all yet, because they have no place to record a reversal.

### Members

Request body for `POST` and `PUT`:

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
| `fullName` | Required, at most 150 characters |
| `phone` | Required. A Rwandan mobile number (072, 073, 078 or 079) written as `07XXXXXXXX`, `2507XXXXXXXX` or `+2507XXXXXXXX`. Always stored and returned as `+2507XXXXXXXX` |
| `nationalId` | Optional, 16 digits |
| `address` | Optional, at most 255 characters |
| `joinDate` | Optional, not in the future. Defaults to today on create; left unchanged on update if not sent |
| `preferredLanguage` | Optional, `rw` or `en`. Defaults to `rw` |

- The member code is generated on create as `M-` and 4 digits (`M-0001`, `M-0002`, ...):
  the highest existing number plus one. It never changes.
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

- The response contains the receipt code, member, grade, lot, quantity, deduction,
  `deliveredAt` (when the delivery happened) and `syncedAt` (when the server saved it).
- **Deduction:** `quantityKg` x `deduction_per_kg` from the `setting` table (5 RWF),
  rounded half up to whole RWF. For example 12.25 kg gives 61 RWF.
- **Lot:** the delivery goes to the open lot of its grade. If the grade has no open lot, a
  new one is opened. Lot codes are the grade code, the year the lot was opened (Kigali
  time) and the grade's lot number in that year: `A-2026-01`, `A-2026-02`, `B-2026-01`.
  A grade can only have one open lot at a time (enforced by the database).
  `GET /api/lots/open?gradeId=` shows it with the kilograms delivered so far, or returns
  `204` when no lot is open yet (the next delivery of that grade opens one).
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
  7-day window, after its member was deactivated, or after its lot was closed.
- **Lot at sync time:** the lot is chosen when the delivery reaches the server: the open
  lot of its grade at that moment. If the lot that was open when the delivery was recorded
  has been closed in the meantime, the delivery goes to a new lot, because a closed lot
  may already be on sale and its kilograms must not change.
- **Errors that will not go away on retry** return a `4xx` status with a message, never
  `500`, so the app can show the message and stop retrying:
  - `400`: invalid body (missing fields, malformed `clientUuid`, quantity out of range,
    `deliveredAt` without a time zone or outside the time window), unknown member or
    grade, inactive member.
  - `409`: the request clashes with data already saved.

  `401` means the login has expired; the app should ask the manager to log in again and
  then retry. Network errors and `5xx` can be retried later with the same `clientUuid`.

### Lots and losses

A lot moves through `OPEN` -> `CLOSED` -> `SOLD` (`SHARED` comes with payment sharing).

- **Quantities** are always computed from the database, never stored:
  `remainingKg = deliveredKg - lostKg - soldKg`. `saleAmountRwf` is the sum of the lot's
  sales and `memberCount` the number of members who delivered to it. `createdAt` and
  `closedAt` are the dates the lot was opened and closed.
- **Close:** only an `OPEN` lot can be closed (otherwise `409`). Closing waits for any
  delivery of the same grade that is being recorded, so no delivery ends up in a lot
  after it closed.
- **Stock:** `GET /api/lots/stock` returns `totalRemainingKg` (open and closed lots) and
  `byGrade` (remaining kg per grade), `totalDeliveredKg`, `totalSoldKg` and `totalLostKg`
  (all lots, so delivered - lost - sold = remaining), `addedTodayKg` and `deliveriesToday`
  (deliveries whose time falls on today in Kigali), and `openLots` / `closedLots`.
- **Losses:** body `{"quantityKg", "reason", "recordedAt", "notes"}`. `reason` is `ROT`,
  `DRYING`, `DAMAGE`, `THEFT` or `OTHER`; `recordedAt` (a date) and `notes` are optional. Allowed on
  open and closed lots (otherwise `409`). `quantityKg` must be more than 0 and at most the
  lot's remaining kg (otherwise `400`). Each loss adds a `LOSS` ledger entry.

### Buyers

Body for `POST /api/buyers`: `{"name", "phone", "type"}`. `name` and `phone` are required;
`type` is optional free text (at most 30 characters), for example `Trader`. The phone
number follows the same rules as for members and is stored as `+2507XXXXXXXX`. A phone
number that already belongs to a buyer returns `409`.

### Sales

Request body for `POST /api/sales`:

```json
{
  "clientUuid": "0f8fad5b-d9cb-469f-a165-70867728950e",
  "lotId": 3,
  "buyerId": 2,
  "quantityKg": 500,
  "pricePerKg": 320,
  "soldAt": "2026-10-09"
}
```

- The lot must be `CLOSED` and the buyer must exist (otherwise `400`).
- `quantityKg` must be more than 0 and at most the lot's remaining kg (otherwise `400`).
- `pricePerKg` is in whole RWF and must be more than 0. `soldAt` is an optional date and
  defaults to today (Kigali time).
- `totalAmountRwf = quantityKg x pricePerKg`, rounded half up to whole RWF.
- `belowFloor` is `true` when the grade has a floor price and `pricePerKg` is lower. It is
  only a warning: the sale is saved either way. A grade without a floor price is never
  below it.
- When a sale uses up the remaining kg, the lot becomes `SOLD` (`lotStatus` in the
  response).
- Sending the same `clientUuid` again returns the existing sale with `200`.
- Sale codes look like `SAL-7K2Q`. Each sale adds a `SALE` ledger entry with the kilograms
  and the total amount.
- `GET /api/sales` filters: `search` matches part of the buyer name, lot code or sale code
  (ignoring case), and a number on its own also matches the sale id. `gradeId` keeps sales
  from lots of that grade.
- `GET /api/sales/summary` covers the current month in Kigali time:
  `salesAmountThisMonth` (RWF), `soldKgThisMonth`, `buyersThisMonth` (different buyers) and
  `averagePricePerKg` (amount / kg, 2 decimals, `0` when nothing was sold).

### Sharing

When a lot is `SOLD`, its sale money is shared between the members who delivered to it.
`GET /api/lots/{id}/share-preview` shows the result without saving; `POST /api/lots/{id}/share`
saves it and sets the lot to `SHARED`.

- Only a `SOLD` lot can be shared (otherwise `400`). A lot that is already `SHARED` returns
  `409`.
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
is owed (their balance) = their shares - their deductions - what they were already paid,
counting only shared lots.

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
- `GET /api/payments/balances` lists every member with at least one shared lot:
  `shareRwf`, `deductionRwf`, `paidRwf`, `balanceRwf` and `status`, largest balance first.
  `status` is `UNPAID` (nothing paid yet), `PART_PAID` or `PAID` (balance 0).
  `search` (name, member number or phone) and `status` filter the list only:
  `totalOwedRwf` (all balances) and `paidThisWeekRwf` (payments dated Monday to Sunday of the
  current week, Kigali time) always cover all members.

### Member statement

`GET /api/members/{id}/statement` returns `grossShareRwf`, `deductionsRwf`, `paidRwf` and
`owedRwf`, the member's `payments` (newest first) and `lotShares`: every lot the member
delivered to, newest first. A shared lot shows the saved kg, percent and `shareRwf`. A lot
that is not shared yet shows its status (`OPEN`, `CLOSED` or `SOLD`), the current kg and
percent, and `shareRwf: null`, so the app can show "Waiting for sale" or "Waiting for
sharing".

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
| `SMS_INBOUND_SECRET` | Long random string, also used in the SMS callback URL |

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
deliveries (deduction, lot assignment, idempotency, ledger entry), lots (remaining kg,
closing, stock), losses, buyers and sales (closed lot only, remaining kg, `SOLD` at 0,
floor price warning, idempotency, ledger entries), sharing (the 10,000 kg example, rounding
remainder, sold lots only, no double sharing, ledger entries), payments (owed math,
overpayment, MoMo reference, idempotency) and member statements.

## Author

Emerance Umurerwa - BSc Software Engineering, African Leadership University

Supervisor: Bernard Lamptey
