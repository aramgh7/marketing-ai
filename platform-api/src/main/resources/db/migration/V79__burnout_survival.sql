-- Burnout survival model columns (v1.1 — vision §15.8 task 2)
ALTER TABLE creative_quality ADD COLUMN IF NOT EXISTS predicted_burnout_day    INT;
ALTER TABLE creative_quality ADD COLUMN IF NOT EXISTS burnout_survival_method  VARCHAR(10) DEFAULT 'HEURISTIC';
ALTER TABLE creative_quality ADD COLUMN IF NOT EXISTS burnout_confidence       VARCHAR(6);
ALTER TABLE creative_quality ADD COLUMN IF NOT EXISTS replacement_brief_id     UUID REFERENCES creator_briefs(id);
