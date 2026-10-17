-- B-008-1: grant missing from V36 — mai_app must have DML rights on creative_burnout_events
GRANT SELECT, INSERT, UPDATE, DELETE ON creative_burnout_events TO mai_app;
