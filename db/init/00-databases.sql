-- Additional databases for the decision and core-mock tiers.
-- `channel` is created automatically via POSTGRES_DB.
-- Runs only on first container initialisation; use `docker compose down -v` to reset.
CREATE DATABASE decision;
CREATE DATABASE core;
