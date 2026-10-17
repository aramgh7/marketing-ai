package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LocalStorageService implements StorageService {

    private final AppProperties props;

    @Override
    public String store(UUID tenantId, UUID creativeId, String originalFilename, byte[] bytes) {
        String sanitized = sanitize(originalFilename);
        Path dir = Path.of(props.getStorage().getLocalPath(), tenantId.toString(), creativeId.toString());
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitized);
            Files.write(target, bytes);
            log.debug("Stored file: {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file " + sanitized, e);
        }
        return "/files/" + tenantId + "/" + creativeId + "/" + sanitized;
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) return "upload";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
