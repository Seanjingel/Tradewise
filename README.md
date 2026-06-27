# TradeWise

TradeWise is a Spring Boot + JavaFX trading monitor with daily risk limits and broker kill-switch integration.

## Start Here

- Read `DOCS.md` for the simplified documentation map.
- Use only these files day-to-day:
  - `README.md` (this file)
  - `DOCS.md`
  - `src/main/resources/application.properties`
- Optional deep-dive docs live under `docs/`.

## Quick Start (Windows PowerShell)

1. Start the backend:

```powershell
.\mvnw.cmd spring-boot:run
```

2. In a second terminal, start JavaFX:

```powershell
.\mvnw.cmd javafx:run
```

3. Login in the app with your Dhan token (client ID optional).

## One-Click Launch

```powershell
.\launch.bat
```

## Dhan Setup

Set credentials in environment variables (optional if entering from UI):

```powershell
$env:DHAN_ACCESS_TOKEN="your_access_token"
$env:DHAN_CLIENT_ID="your_client_id"
```

Enable Dhan mode in `src/main/resources/application.properties`:

```properties
tradewise.dhan.enabled=true
```

## Core Endpoints

- `GET /api/trades`
- `GET /api/trades/daily-stats`
- `GET /api/trades/kill-switch`
- `POST /api/trades/kill-switch/activate`
- `POST /api/trades/kill-switch/deactivate`

## Troubleshooting

If SSL handshake fails locally:

```properties
tradewise.dhan.tls-protocol=TLSv1.2
tradewise.dhan.insecure-ssl=true
```

Use insecure SSL only for local debugging.


