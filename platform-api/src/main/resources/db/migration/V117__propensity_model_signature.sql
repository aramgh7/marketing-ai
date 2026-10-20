-- Audit §3.4: /propensity/score unpickled caller-supplied joblib bytes (remote code execution).
-- The ML service now returns an HMAC signature from /fit and refuses unsigned bytes on /score.
ALTER TABLE propensity_models ADD COLUMN IF NOT EXISTS model_sig VARCHAR(64);
