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
- Spring Security (JWT login: coming soon)
- JUnit 5
- Docker
- Render

## Project structure

The code is organised by feature. Each feature package holds its own controller,
service, repository, entity and dto classes. Shared configuration lives in `config`.

```
coopaggregate-backend/
├── src/main/java/com/coopaggregate/
│   ├── CoopAggregateApplication.java
│   ├── config/            Security and CORS configuration
│   └── health/            Health check endpoint
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
DATABASE_URL=jdbc:postgresql://host/db?sslmode=require;DB_USERNAME=...;DB_PASSWORD=...;JWT_SECRET=...
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

More endpoints coming soon.

Interactive docs: `/swagger-ui.html`

- Local: http://localhost:8080/swagger-ui.html
- Live: https://coopaggregate-backend.onrender.com/swagger-ui.html

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

Tests coming soon, together with the features.

## Author

Emerance Umurerwa - BSc Software Engineering, African Leadership University

Supervisor: Bernard Lamptey
