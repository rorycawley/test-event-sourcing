# Schema Migrations

This project uses Migratus for all schema evolution.

## Policy

1. Every schema change must be a new file in `resources/migrations/`.
2. Do not edit previously applied migration files on shared branches.
3. Do not add ad-hoc DDL in application code paths.
4. Prefer forward-only fixes: add a new migration instead of rewriting old ones.

## File Naming

Use timestamped names:

`YYYYMMDDHHMMSS-description.up.sql`  
`YYYYMMDDHHMMSS-description.down.sql`

Example:

`20260316233000-create-core-tables.up.sql`  
`20260316233000-create-core-tables.down.sql`

## Running Migrations

Environment:

- `JDBC_URL` (required)
- `DB_USER` (optional)
- `DB_PASSWORD` (optional)

Commands:

```bash
JDBC_URL=jdbc:postgresql://localhost:5432/test_event_sourcing bb migration-status
JDBC_URL=jdbc:postgresql://localhost:5432/test_event_sourcing bb migrate
JDBC_URL=jdbc:postgresql://localhost:5432/test_event_sourcing bb rollback
```

## Notes

- `migrations/migrate!` applies pending Migratus migrations.
- Tests run against fresh containers and call the same migration path.
