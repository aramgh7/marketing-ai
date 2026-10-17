package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creative_atoms")
@Getter
@Setter
@NoArgsConstructor
public class CreativeAtom {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID creativeId;

    @Column(nullable = false)
    private UUID tenantId;

    /**
     * HOOK, STRUCTURE, EDIT_PACE, FACE, SUBTITLES,
     * TONE, PROOF_TYPE, MUSIC, DURATION, CTA, PRODUCTION_STYLE
     */
    @Column(nullable = false)
    private String atomType;

    @Column(nullable = false)
    private String atomValue;

    /** LLM extraction confidence [0,1]; null for manual entries */
    private Float confidence;

    /** "claude-vision", "whisper+llm", "manual" */
    private String extractedBy;

    // embedding vector(768) intentionally unmapped — pgvector Phase 1+

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
