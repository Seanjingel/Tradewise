# TradeWise Docs (Simplified)

This file is the single documentation map.
If you are unsure what to read, read only the **Core Docs** section.

## Core Docs (Use These)

1. `README.md` - setup and run
2. `DOCS.md` - this map
3. `src/main/resources/application.properties` - all limits/config

## Secondary Docs (Optional)

- `docs/API_EXAMPLES.md` - API samples and payload examples
- `docs/SESSION_FEATURES_GUIDE.md` - daily stats lock and journal behavior

## Legacy / Overlapping Docs (Archived)

These files are kept for history under `docs/archive/` and can be ignored in daily work:

- `docs/archive/FINAL_ANSWER_HOW_IT_CONTROLS_TRADING.md`
- `docs/archive/IMPROVEMENTS.md`
- `docs/archive/MONITORING_APP_BROKER_KILLSWITCH.md`
- `docs/archive/PROJECT_STRUCTURE.md`
- `docs/archive/SIMPLE_ANSWER_YES_KILLSWITCH.md`
- `docs/archive/TECHNICAL_IMPLEMENTATION_DETAILS.md`
- `docs/archive/UI_LOCK_INTEGRATION_GUIDE.md`

## Recommended Reading Path

- New here: `README.md` -> `DOCS.md`
- Working on APIs: `README.md` -> `docs/API_EXAMPLES.md`
- Working on risk lock behavior: `README.md` -> `docs/SESSION_FEATURES_GUIDE.md`

## Why this simplification

The repository has many historical markdown files from multiple implementation phases.
To reduce confusion, treat this as a two-level system:

- **Level 1 (daily use):** `README.md` and `DOCS.md`
- **Level 2 (deep dive):** open a single optional doc only when needed

