# Schema Migrations

This project uses Migratus for all schema evolution.

## Policy

1. Every schema change must be a new file in `resources/migrations/`.
2. Do not edit previously applied migration files on shared branches.
3. Do not add ad-hoc DDL in application code paths.
   **Exception:** BM25 search indexes (via `es.search/ensure-search!`) are created
   at startup rather than in migrations because the Tantivy-backed index has
   different lifecycle concerns than schema DDL (e.g. must be rebuilt after
   TRUNCATE). Migrations handle the schema (columns); `es.search` handles the
   search index.
4. Prefer forward-only fixes: add a new migration instead of rewriting old ones.

## File Naming

Use numeric prefixes (matching the existing convention in `resources/migrations/`):

`NNN-description.up.sql`
`NNN-description.down.sql`

Example:

`001-schema.up.sql`
`001-schema.down.sql`
`002-add-audit-columns.up.sql`
`002-add-audit-columns.down.sql`

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
