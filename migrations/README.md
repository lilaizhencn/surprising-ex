# PostgreSQL migrations

`init.sql` is the immutable production baseline for the first Surprising Exchange release. It already contains every schema change that existed before launch, including the six-product-line instrument catalog and Aeron Core history projections.

## Rules after launch

1. Never edit an applied migration or rewrite `init.sql` to upgrade an existing production database.
2. Add one forward-only file named `VYYYYMMDDHHMM__short_description.sql` for each schema change.
3. Make each migration transactional unless PostgreSQL explicitly forbids the operation.
4. Start scripts with `BEGIN;` and end with `COMMIT;`; deployment must use `psql -v ON_ERROR_STOP=1`.
5. Do not place destructive data cleanup, environment-specific seed data, passwords, roles, database creation, or ownership changes in migrations.
6. Online trading state remains authoritative in Aeron Core. PostgreSQL migrations may evolve configuration, history, audit and projection schemas only.
7. Validate every new migration against a database created from the current `init.sql`, then validate application startup and projector compatibility.
8. Every new table and column must have a meaningful PostgreSQL `COMMENT`; every domain with a finite value set must have a `CHECK`, and relationships must use explicit `PK`/`FK`/`UNIQUE` constraints.

The dated SQL files that predated the first release were folded into `init.sql` on 2026-08-18. They are intentionally not retained as upgrade steps because no production database has ever applied them.
