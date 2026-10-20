-- Audit §5: ProxyCalibrationScheduler inserted proxy_col/target_col/pearson_r/spearman_r, none of which
-- existed on proxy_calibration_runs (V?? defined cohort/bias columns), so every run failed.
ALTER TABLE proxy_calibration_runs ADD COLUMN IF NOT EXISTS proxy_col  VARCHAR(50);
ALTER TABLE proxy_calibration_runs ADD COLUMN IF NOT EXISTS target_col VARCHAR(50);
ALTER TABLE proxy_calibration_runs ADD COLUMN IF NOT EXISTS pearson_r  REAL;
ALTER TABLE proxy_calibration_runs ADD COLUMN IF NOT EXISTS spearman_r REAL;
