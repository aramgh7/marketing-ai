-- B-015-1: creative_quality table was missing RLS + FORCE + policy (V50 omission)
ALTER TABLE creative_quality ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE creative_quality ENABLE ROW LEVEL SECURITY;
ALTER TABLE creative_quality FORCE ROW LEVEL SECURITY;
CREATE POLICY creative_quality_tenant ON creative_quality
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
