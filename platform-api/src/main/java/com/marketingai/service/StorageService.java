package com.marketingai.service;

import java.util.UUID;

public interface StorageService {

    /**
     * Stores a file and returns its public URL.
     *
     * @param tenantId         tenant owning the file
     * @param creativeId       creative the file belongs to
     * @param originalFilename original filename from the upload
     * @param bytes            file content
     * @return public URL to access the file
     */
    String store(UUID tenantId, UUID creativeId, String originalFilename, byte[] bytes);
}
