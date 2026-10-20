-- Roadmap 9-15: tenant music library for the render music bed (vision §5.2).
-- Tracks are uploaded by the tenant with a licence they can prove; the platform never generates
-- music. The ML render mixes one chosen track under the speech (ffmpeg amix + sidechain ducking).

CREATE TABLE music_tracks (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title         VARCHAR(255) NOT NULL,
    url           TEXT         NOT NULL,                              -- R2 / public URL of the audio file
    licence       VARCHAR(20)  NOT NULL
        CHECK (licence IN ('royalty_free', 'licensed', 'owned')),
    licence_ref   TEXT,                                               -- invoice / licence id / source
    bpm           REAL         CHECK (bpm IS NULL OR bpm > 0),
    mood          VARCHAR(50),                                        -- calm | upbeat | dramatic | corporate | …
    duration_sec  REAL         CHECK (duration_sec IS NULL OR duration_sec > 0),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_music_tracks_tenant ON music_tracks(tenant_id, is_active, mood);
ALTER TABLE music_tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE music_tracks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON music_tracks
    USING ((tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
           OR current_setting('app.rls_bypass', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON music_tracks TO mai_app;
