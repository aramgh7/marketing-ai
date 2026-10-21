package com.marketingai.service;

import com.marketingai.dto.creative.CreativeAtomResponse;
import com.marketingai.dto.creative.CreativeResponse;
import com.marketingai.dto.creative.UpdateAtomRequest;
import com.marketingai.entity.Creative;
import com.marketingai.entity.CreativeAtom;
import com.marketingai.repository.CreativeAtomRepository;
import com.marketingai.repository.CreativeRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreativeService {

    private final CreativeRepository creativeRepository;
    private final CreativeAtomRepository atomRepository;
    private final StorageService storageService;
    private final MlServiceClient mlServiceClient;

    // ─── List ────────────────────────────────────────────────────────────────

    public List<CreativeResponse> list(String typeFilter) {
        UUID tenantId = tenantId();
        List<Creative> creatives = (typeFilter != null && !typeFilter.isBlank())
                ? creativeRepository.findByTenantIdAndTypeAndStatusNotOrderByCreatedAtDesc(tenantId, typeFilter.toUpperCase(), "ARCHIVED")
                : creativeRepository.findByTenantIdAndStatusNotOrderByCreatedAtDesc(tenantId, "ARCHIVED");
        return creatives.stream().map(c -> toResponse(c, false)).toList();
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    public CreativeResponse get(UUID id) {
        Creative creative = findOrThrow(id);
        List<CreativeAtomResponse> atoms = atomRepository
                .findByCreativeIdOrderByAtomTypeAscCreatedAtAsc(id)
                .stream().map(this::toAtomResponse).toList();
        return toResponse(creative, true, atoms);
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    @Transactional
    public CreativeResponse create(String type, String title, String externalId,
                                   String metaAdSetId, String fileUrl, MultipartFile file) {
        UUID tenantId = tenantId();

        Creative creative = new Creative();
        creative.setTenantId(tenantId);
        creative.setType(type.toUpperCase());
        creative.setTitle(title);
        creative.setExternalId(externalId);
        creative.setMetaAdSetId(metaAdSetId);
        creative.setStatus("UPLOADED");
        creative = creativeRepository.save(creative);

        // Store uploaded file if provided
        if (file != null && !file.isEmpty()) {
            try {
                String storedUrl = storageService.store(tenantId, creative.getId(),
                        file.getOriginalFilename(), file.getBytes());
                creative.setFileUrl(storedUrl);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store file");
            }
        } else if (fileUrl != null && !fileUrl.isBlank()) {
            creative.setFileUrl(fileUrl);
        }

        creative = creativeRepository.save(creative);
        return toResponse(creative, false);
    }

    // ─── Update metadata ─────────────────────────────────────────────────────

    @Transactional
    public CreativeResponse update(UUID id, String title, String externalId,
                                   String metaAdSetId, String transcript, Float durationSec) {
        Creative creative = findOrThrow(id);
        if (title != null) creative.setTitle(title);
        if (externalId != null) creative.setExternalId(externalId);
        if (metaAdSetId != null) creative.setMetaAdSetId(metaAdSetId);
        if (transcript != null) creative.setTranscript(transcript);
        if (durationSec != null) creative.setDurationSec(durationSec);
        return toResponse(creativeRepository.save(creative), false);
    }

    // ─── Toggle anchor ───────────────────────────────────────────────────────

    @Transactional
    public CreativeResponse toggleAnchor(UUID id) {
        Creative creative = findOrThrow(id);
        creative.setAnchor(!creative.isAnchor());
        return toResponse(creativeRepository.save(creative), false);
    }

    // ─── Archive ─────────────────────────────────────────────────────────────

    @Transactional
    public void archive(UUID id) {
        Creative creative = findOrThrow(id);
        creative.setStatus("ARCHIVED");
        creativeRepository.save(creative);
    }

    // ─── Trigger analysis ────────────────────────────────────────────────────

    @Transactional
    public String triggerAnalysis(UUID id) {
        Creative creative = findOrThrow(id);
        if (creative.getFileUrl() == null && creative.getThumbnailUrl() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Creative has no file to analyze");
        }
        creative.setStatus("ANALYZING");
        creativeRepository.save(creative);

        String jobId = mlServiceClient.analyzeCreative(
                creative.getTenantId(),
                creative.getId(),
                creative.getType(),
                creative.getFileUrl(),
                creative.getThumbnailUrl(),
                creative.getTranscript(),
                creative.getDurationSec()
        );

        if (jobId == null) {
            // ml-service unreachable — revert status so the user can retry
            creative.setStatus("UPLOADED");
            creativeRepository.save(creative);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analysis service is unavailable");
        }

        return jobId;
    }

    // ─── Manual atom management ──────────────────────────────────────────────

    @Transactional
    public CreativeAtomResponse addAtom(UUID creativeId, UpdateAtomRequest req) {
        findOrThrow(creativeId); // verify ownership
        UUID tenantId = tenantId();
        CreativeAtom atom = new CreativeAtom();
        atom.setCreativeId(creativeId);
        atom.setTenantId(tenantId);
        atom.setAtomType(req.getAtomType().toUpperCase());
        atom.setAtomValue(req.getAtomValue());
        atom.setConfidence(req.getConfidence());
        atom.setExtractedBy("manual");
        return toAtomResponse(atomRepository.save(atom));
    }

    @Transactional
    public CreativeAtomResponse updateAtom(UUID creativeId, UUID atomId, UpdateAtomRequest req) {
        findOrThrow(creativeId);
        CreativeAtom atom = atomRepository.findByIdAndTenantId(atomId, tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Atom not found"));
        atom.setAtomType(req.getAtomType().toUpperCase());
        atom.setAtomValue(req.getAtomValue());
        atom.setConfidence(req.getConfidence());
        atom.setExtractedBy("manual");
        return toAtomResponse(atomRepository.save(atom));
    }

    @Transactional
    public void deleteAtom(UUID creativeId, UUID atomId) {
        findOrThrow(creativeId);
        CreativeAtom atom = atomRepository.findByIdAndTenantId(atomId, tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Atom not found"));
        atomRepository.delete(atom);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Creative findOrThrow(UUID id) {
        return creativeRepository.findByIdAndTenantId(id, tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creative not found"));
    }

    private UUID tenantId() {
        return UUID.fromString(TenantContext.get());
    }

    private CreativeResponse toResponse(Creative c, boolean withAtoms) {
        return toResponse(c, withAtoms, withAtoms
                ? atomRepository.findByCreativeIdOrderByAtomTypeAscCreatedAtAsc(c.getId())
                        .stream().map(this::toAtomResponse).toList()
                : null);
    }

    private CreativeResponse toResponse(Creative c, boolean withAtoms, List<CreativeAtomResponse> atoms) {
        return CreativeResponse.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .externalId(c.getExternalId())
                .type(c.getType())
                .title(c.getTitle())
                .fileUrl(c.getFileUrl())
                .thumbnailUrl(c.getThumbnailUrl())
                .transcript(c.getTranscript())
                .durationSec(c.getDurationSec())
                .status(c.getStatus())
                .isAnchor(c.isAnchor())
                .metaAdSetId(c.getMetaAdSetId())
                .createdAt(c.getCreatedAt())
                .publishStatus(c.getPublishStatus())
                .creativeCode(c.getCreativeCode())
                .metaVideoId(c.getMetaVideoId())
                .metaCreativeId(c.getMetaCreativeId())
                .publishedAt(c.getPublishedAt())
                .atoms(atoms)
                .build();
    }

    private CreativeAtomResponse toAtomResponse(CreativeAtom a) {
        return CreativeAtomResponse.builder()
                .id(a.getId())
                .creativeId(a.getCreativeId())
                .atomType(a.getAtomType())
                .atomValue(a.getAtomValue())
                .confidence(a.getConfidence())
                .extractedBy(a.getExtractedBy())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
