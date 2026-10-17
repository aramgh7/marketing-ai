package com.marketingai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Telegram telegram = new Telegram();
    private final Storage storage = new Storage();
    private final Report report = new Report();
    private final Meta meta = new Meta();
    private String mlServiceUrl;
    private String internalApiKey;
    private String ghlWebhookSecret = "";
    private String slackWebhookUrl = "";
    /** TELEGRAM | SLACK | BOTH */
    private String alertChannel = "BOTH";

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expiryMs = 86400000L;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000");
    }

    @Getter
    @Setter
    public static class Storage {
        /** "local" (dev) or "r2" (Phase 4 production) */
        private String type = "local";
        private String localPath = System.getProperty("java.io.tmpdir") + "/marketing-ai/files";
        private final R2 r2 = new R2();

        @Getter
        @Setter
        public static class R2 {
            private String accountId = "";
            private String accessKeyId = "";
            private String secretAccessKey = "";
            private String bucket = "marketing-ai-assets";
            /** Public base URL, e.g. "https://assets.example.com". Falls back to r2.dev subdomain if blank. */
            private String publicUrl = "";
        }
    }

    @Getter
    @Setter
    public static class Report {
        private String emailTo = "";
        private String emailFrom = "noreply@marketing-ai.local";
        private boolean enabled = true;

        public boolean isEmailConfigured() {
            return emailTo != null && !emailTo.isBlank();
        }
    }

    @Getter
    @Setter
    public static class Meta {
        private String appId;
        private String appSecret;
    }

    @Getter
    @Setter
    public static class Telegram {
        private String botToken = "";
        private String chatId = "";

        public boolean isConfigured() {
            return botToken != null && !botToken.isBlank()
                    && chatId != null && !chatId.isBlank();
        }
    }
}
