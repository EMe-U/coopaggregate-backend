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
| GET | `/api/ledger?page=0&size=20` | JWT | Ledger entries, newest first (max 100 per page) |
| GET | `/api/ledger/verify` | JWT | Recomputes every hash. Returns `{valid, checkedEntries, firstBrokenEntryId}` |
| POST | `/api/ledger/{id}/reverse` | JWT | Body `{"reason"}` (required). Adds a REVERSAL entry and returns it |
| GET | `/api/members?search=&active=&page=0&size=20` | JWT | Members sorted by name (max 100 per page). `search` and `active` are optional |
| GET | `/api/members/summary` | JWT | Counts: `{total, active, inactive, joinedThisMonth}` |
| GET | `/api/members/{id}` | JWT | One member |
| POST | `/api/members` | JWT | Registers a member and returns it with `201` |
| PUT | `/api/members/{id}` | JWT | Updates a member's details |
| PATCH | `/api/members/{id}/deactivate` | JWT | Sets the member's status to `INACTIVE` |
| PATCH | `/api/members/{id}/activate` | JWT | Sets the member's status to `ACTIVE` |
| GET | `/api/grades` | JWT | Active grades `{id, code, name, floorPricePerKg}`, sorted by code. `floorPricePerKg` is `null` when not set |
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
| POST | `/api/deliveries` | JWT | Records a delivery. `201` when new, `200` when the same `clientUuid` was already recorded |
| GET | `/api/deliveries?memberId=&lotId=&from=&to=&page=0&size=20` | JWT | Deliveries, newest first (max 100 per page). All filters are optional |
| GET | `/api/deliveries/{id}` | JWT | One delivery |

More endpoints coming soon.

### Authentication

The manager logs in with `POST /api/auth/login` and receives a JWT that is valid for
12 hours. Protected endpoints need the header `Authorization: Bearer <token>`.

- A wrong email or a wrong password both return `401` with
  `{"message": "Invalid email or password"}`.
- After 5 failed attempts for the same email, logins for that email are refused with
  `429` for 5 minutes. The counter is kept in memory and resets when the app restarts.

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
| `deliveredAt` | Optional. Defaults to the time the server receives the delivery |

- The response contains the receipt code, member, grade, lot, quantity, deduction and
  delivery time.
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
- Deliveries cannot be edited or deleted. A mistake is corrected with a ledger reversal.

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
floor price warning, idempotency, ledger entries).

## Author

Emerance Umurerwa - BSc Software Engineering, African Leadership University

Supervisor: Bernard Lamptey
