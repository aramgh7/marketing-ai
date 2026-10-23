-- Audit 2026-10-22 M-5 (.ai/code-audit-2026-10-22.md §4.3): publishing a creative is recorded as an *intent* row
-- before the Meta calls and finished (or marked failed) after them, so no HTTP call runs inside a transaction
-- and a second click / retry sees the row and gets 409 instead of a second video + creative + ad.
--  meta_ad_map.status  PUBLISHING      — intent; meta_ad_id = 'pending:<creative_id>' until Meta returns the ad id
--                                        (the column is NOT NULL and unique per tenant, which also blocks a
--                                        concurrent second intent for the same creative); drift.stage carries the
--                                        checkpoint (IN_PROGRESS | VIDEO_PROCESSING) — not a reconciliation drift
--                      PUBLISH_FAILED  — the sequence broke after Meta created something; meta_ad_id =
--                                        'failed:<creative_id>:<epoch_ms>' (or the real ad id when it could not be
--                                        deleted), drift.orphans lists video / creative / ad ids for clean-up
ALTER TABLE meta_ad_map DROP CONSTRAINT IF EXISTS meta_ad_map_status_check;
ALTER TABLE meta_ad_map ADD CONSTRAINT meta_ad_map_status_check
    CHECK (status IN ('PAUSED', 'PENDING_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED', 'ARCHIVED', 'UNMANAGED', 'DRIFT', 'UNKNOWN',
                      'PUBLISHING', 'PUBLISH_FAILED'));
