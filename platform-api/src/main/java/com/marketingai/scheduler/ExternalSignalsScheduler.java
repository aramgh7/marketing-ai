package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * External Signals Scheduler (3-12).
 * Hourly: aggregate CPM index from campaign_cpm_history → external_signals.
 * Daily 06:00 UTC: fetch weather from Open-Meteo → external_signals.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalSignalsScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @Scheduled(fixedDelay = 3_600_000)
    public void aggregateCpmSignals() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT mc.tenant_id, AVG(h.cpm) AS avg_cpm, COUNT(*) AS n
                    FROM campaign_cpm_history h
                    JOIN meta_campaigns mc ON mc.id = h.campaign_id
                    WHERE h.recorded_at > now() - interval '1 hour'
                    GROUP BY mc.tenant_id
                    """);

            for (Map<String, Object> row : rows) {
                UUID tenantId = (UUID) row.get("tenant_id");
                Double avgCpm = ((Number) row.get("avg_cpm")).doubleValue();

                jdbc.update("""
                        INSERT INTO external_signals
                        (tenant_id, signal_type, observed_at, value_num, source, created_at)
                        VALUES (?, 'CPM_INDEX', now(), ?, 'campaign_cpm_history', now())
                        """, tenantId, avgCpm.floatValue());
            }
            if (!rows.isEmpty()) {
                log.debug("ExternalSignals: saved CPM signals for {} tenants", rows.size());
            }
        } catch (Exception e) {
            log.warn("ExternalSignalsScheduler CPM aggregation failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void fetchWeatherSignals() {
        // Default geo: lat,lon pairs from app config (agricultural hydrogel client)
        String geosConfig = "51.5,-0.1";  // default London; override via AppProperties if needed
        String[] geos = geosConfig.split(";");

        for (String geo : geos) {
            try {
                String[] parts = geo.trim().split(",");
                if (parts.length != 2) continue;
                String lat = parts[0].trim();
                String lon = parts[1].trim();

                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat
                        + "&longitude=" + lon
                        + "&daily=precipitation_sum,temperature_2m_max,et0_fao_evapotranspiration"
                        + "&forecast_days=1&timezone=UTC";

                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response == null) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> daily = (Map<String, Object>) response.get("daily");
                if (daily == null) continue;

                @SuppressWarnings("unchecked")
                List<Double> precipitation = (List<Double>) daily.get("precipitation_sum");
                @SuppressWarnings("unchecked")
                List<Double> tempMax = (List<Double>) daily.get("temperature_2m_max");
                @SuppressWarnings("unchecked")
                List<Double> et0 = (List<Double>) daily.get("et0_fao_evapotranspiration");

                double precip = precipitation != null && !precipitation.isEmpty()
                        ? precipitation.get(0) : 0.0;
                double temp = tempMax != null && !tempMax.isEmpty() ? tempMax.get(0) : 0.0;
                double gdd = et0 != null && !et0.isEmpty() ? et0.get(0) : 0.0;

                List<String> tags = buildWeatherTags(precip, temp, gdd);
                String tagsArray = "{" + String.join(",", tags) + "}";

                // Platform-wide signal (tenant_id = null)
                jdbc.update("""
                        INSERT INTO external_signals
                        (signal_type, geo, observed_at, value_num, value_tags, source, created_at)
                        VALUES ('WEATHER', ?, now(), ?, ?, 'open-meteo', now())
                        """, lat + "," + lon, (float) precip, tagsArray);

                log.debug("ExternalSignals: weather signal saved for geo={}", geo);
            } catch (Exception e) {
                log.warn("ExternalSignals: failed to fetch weather for geo={}: {}", geo, e.getMessage());
            }
        }
    }

    public void saveCalendarEvent(UUID tenantId, String name, String tag,
                                   LocalDate startsOn, LocalDate endsOn,
                                   String geo, String expectedEffect) {
        jdbc.update("""
                INSERT INTO event_calendar (tenant_id, name, tag, starts_on, ends_on, geo, expected_effect, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, tenantId, name, tag, startsOn, endsOn, geo, expectedEffect);
    }

    private List<String> buildWeatherTags(double precipMm, double tempCelsius, double et0) {
        List<String> tags = new java.util.ArrayList<>();
        if (precipMm < 1.0) tags.add("drought_moderate");
        if (precipMm < 0.1) tags.add("drought_severe");
        if (tempCelsius > 35.0) tags.add("heat_stress");
        if (et0 > 0) tags.add("growing_degree_days");
        if (precipMm > 20.0) tags.add("heavy_rain");
        if (tags.isEmpty()) tags.add("normal");
        return tags;
    }
}
