package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roadmap 9-19: every Meta Marketing API write must pass through {@link MetaWriteGate}.
 * Scans the main sources: each call to a MetaApiService write method must be preceded (within the same
 * method body, 40 lines) by a gate call. Adding a write path without the gate fails this test.
 */
class MetaWriteGuardTest {

    private static final Pattern WRITE_CALL = Pattern.compile(
            "metaApi(?:Service)?\\.(createCampaign|createAdSet|setCampaignStatus|setAdSetStatus|uploadVideo|uploadImage|createAdCreative|createAd|setAdStatus|upsertCustomAudience)\\(");
    private static final Pattern GATE_CALL = Pattern.compile("metaWriteGate\\.(assertAllowed|decide)\\(");

    @Test
    void every_meta_write_is_gated() throws IOException {
        Path root = Path.of("src/main/java/com/marketingai");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("MetaApiService.java")) continue;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = WRITE_CALL.matcher(lines.get(i));
                    if (!m.find()) continue;
                    boolean gated = false;
                    for (int j = i; j >= Math.max(0, i - 40); j--) {
                        if (GATE_CALL.matcher(lines.get(j)).find()) { gated = true; break; }
                    }
                    if (!gated) violations.add(f.getFileName() + ":" + (i + 1) + " " + m.group(1));
                }
            }
        }
        assertThat(violations)
                .as("Meta write calls without a preceding metaWriteGate.assertAllowed(...)")
                .isEmpty();
    }
}
