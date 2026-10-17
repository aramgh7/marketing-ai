package com.marketingai.service;

import com.marketingai.config.AppProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.UUID;

/**
 * Cloudflare R2 storage (S3-compatible).
 * Active when app.storage.type=r2.
 * Architecture §2: R2 preferred — no egress fees.
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "r2")
@RequiredArgsConstructor
@Slf4j
public class R2StorageService implements StorageService {

    private final AppProperties props;
    private S3Client s3;

    @PostConstruct
    void init() {
        AppProperties.Storage.R2 r2 = props.getStorage().getR2();
        s3 = S3Client.builder()
                .endpointOverride(URI.create("https://" + r2.getAccountId() + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKeyId(), r2.getSecretAccessKey())))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        log.info("R2StorageService initialised (bucket={})", r2.getBucket());
    }

    @Override
    public String store(UUID tenantId, UUID creativeId, String originalFilename, byte[] bytes) {
        AppProperties.Storage.R2 r2 = props.getStorage().getR2();
        String sanitized = sanitize(originalFilename);
        String key = tenantId + "/" + creativeId + "/" + sanitized;

        s3.putObject(
                r -> r.bucket(r2.getBucket()).key(key).contentLength((long) bytes.length),
                RequestBody.fromBytes(bytes));

        String base = r2.getPublicUrl().isBlank()
                ? "https://" + r2.getBucket() + "." + r2.getAccountId() + ".r2.dev"
                : r2.getPublicUrl();
        String url = base + "/" + key;
        log.debug("Stored R2 object: {}", url);
        return url;
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) return "upload";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
