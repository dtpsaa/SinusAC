package ru.dtpsaa.sinusac.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.Frame;

/**
 * HTTP-клиент к ML-серверу SinusAI.
 * <p>
 * SinusAC использует: validateLicense, analyze, notifyBan, heartbeat, ping.
 * Методы upload() и learn() оставлены как API для SinusOP — команды,
 * которые их дёргают, в этом плагине отсутствуют. Реальная защита от
 * несанкционированного обучения — на стороне сервера (права токена лицензии).
 */
public class ApiClient {

    private String baseUrl;
    private String licenseKey;
    private String serverId;

    public static class LicenseResult {
        public final boolean valid;
        public final String message;
        public final int remainingDays;

        public LicenseResult(boolean valid, String message, int remainingDays) {
            this.valid = valid;
            this.message = message;
            this.remainingDays = remainingDays;
        }

        public static LicenseResult fail(String r) {
            return new LicenseResult(false, r, 0);
        }
    }

    public static class AnalysisResult {
        public final double probability;
        public final double confidence;
        public final boolean flagged;
        public final String checkType;
        /** Модель ещё не обучена/не загружена на сервере. */
        public final boolean noModel;
        /** Сервер копит буфер фреймов — вердикта пока нет. */
        public final boolean buffering;

        public AnalysisResult(double probability, double confidence, boolean flagged,
                              String checkType, boolean noModel, boolean buffering) {
            this.probability = probability;
            this.confidence = confidence;
            this.flagged = flagged;
            this.checkType = checkType;
            this.noModel = noModel;
            this.buffering = buffering;
        }
    }

    public static class LearnResult {
        public final boolean success;
        public final String message;
        public final Map<String, String> results;

        public LearnResult(boolean success, String message, Map<String, String> results) {
            this.success = success;
            this.message = message;
            this.results = (results != null) ? results : new HashMap<>();
        }

        public static LearnResult fail(String r) {
            return new LearnResult(false, r, null);
        }
    }

    private final Gson gson = new Gson();
    private HttpClient http;
    private volatile String sessionToken;

    public ApiClient(String baseUrl, String licenseKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.licenseKey = licenseKey;
        this.sessionToken = licenseKey;
        this.serverId = "unknown";
        this.http = buildClient();
    }

    /** Перечитывает url/ключ из конфига и заново определяет server_id (публичный IP:порт). */
    public void updateConfig(PluginConfig config) {
        this.baseUrl = config.getServerUrl().replaceAll("/$", "");
        this.licenseKey = config.getLicenseKey();
        this.sessionToken = this.licenseKey;
        this.http = buildClient();
        int port = org.bukkit.Bukkit.getPort();
        String ip = resolvePublicIp();
        this.serverId = ip + ":" + port;
    }

    private String resolvePublicIp() {
        String[] services = {
                "https://api.ipify.org",
                "https://checkip.amazonaws.com",
                "https://icanhazip.com"
        };
        for (String url : services) {
            try {
                HttpResponse<String> resp = this.http.send(
                        req(url).GET().timeout(Duration.ofSeconds(5L)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body().trim();
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }

    public LicenseResult validateLicense() {
        String url = this.baseUrl + "/subscription/validate/" + this.licenseKey + "?server_id=" + this.serverId;
        try {
            HttpResponse<String> resp = this.http.send(
                    req(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject obj = this.gson.fromJson(resp.body(), JsonObject.class);
                if (obj.has("token") && !obj.get("token").isJsonNull())
                    this.sessionToken = obj.get("token").getAsString();
                boolean allowed = (!obj.has("allowed") || obj.get("allowed").getAsBoolean());
                if (!allowed)
                    return LicenseResult.fail(obj.has("reason") ? obj.get("reason").getAsString() : "Доступ запрещён");
                int rem = (obj.has("remaining_days") && !obj.get("remaining_days").isJsonNull())
                        ? obj.get("remaining_days").getAsInt() : -1;
                String plan = obj.has("plan") ? obj.get("plan").getAsString() : "unknown";
                return new LicenseResult(true, "Тариф: " + plan, rem);
            }
            return LicenseResult.fail("HTTP " + resp.statusCode() + " " + reason(resp.body()));
        } catch (Exception e) {
            return LicenseResult.fail("Ошибка подключения: " + e.getMessage());
        }
    }

    /** Отправляет фреймы на анализ. null — сетевая ошибка. */
    public AnalysisResult analyze(List<Frame> frames, String checkType, String platform, String playerName) {
        Map<String, Object> body = new HashMap<>();
        body.put("platform", platform);
        body.put("check_type", normalizeCheck(checkType));
        body.put("frames", frames);
        body.put("server_id", this.serverId);
        body.put("player_id", playerName);
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/analyze")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject obj = this.gson.fromJson(resp.body(), JsonObject.class);
                boolean noModel   = obj.has("no_model")  && obj.get("no_model").getAsBoolean();
                boolean buffering = obj.has("buffering") && obj.get("buffering").getAsBoolean();
                return new AnalysisResult(
                        obj.get("probability").getAsDouble(),
                        obj.get("confidence").getAsDouble(),
                        obj.get("flagged").getAsBoolean(),
                        checkType, noModel, buffering);
            }
            return new AnalysisResult(0.0D, 0.0D, false, checkType, true, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** Уведомление о наказании (Telegram на стороне сервера). Ошибки глотаются. */
    public void notifyBan(String playerName, String platform, String reason, int vl, double probability, List<Frame> frames) {
        Map<String, Object> body = new HashMap<>();
        body.put("player_id", playerName);
        body.put("platform", platform);
        body.put("ban_reason", reason);
        body.put("vl", vl);
        body.put("probability", probability);
        body.put("frames", frames);
        body.put("server_id", this.serverId);
        try {
            this.http.send(
                    authReq(this.baseUrl + "/notify_ban")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    /** [API для SinusOP] Загрузка размеченных фреймов в датасет. */
    public boolean upload(List<Frame> frames, boolean isCheater, String checkType, String platform) {
        Map<String, Object> body = Map.of(
                "platform", platform,
                "check_type", normalizeCheck(checkType),
                "is_cheater", isCheater,
                "frames", frames);
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/upload")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode() == 200);
        } catch (Exception e) {
            return false;
        }
    }

    /** [API для SinusOP] Запуск обучения моделей на сервере. */
    public LearnResult learn() {
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/learn")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject obj = this.gson.fromJson(resp.body(), JsonObject.class);
                Map<String, String> results = new HashMap<>();
                if (obj.has("results"))
                    obj.getAsJsonObject("results").entrySet()
                            .forEach(e -> results.put(e.getKey(), e.getValue().getAsString()));
                return new LearnResult(true, "ok", results);
            }
            return LearnResult.fail("HTTP " + resp.statusCode() + " " + reason(resp.body()));
        } catch (Exception e) {
            return LearnResult.fail("Ошибка: " + e.getMessage());
        }
    }

    public boolean heartbeat(String name, String mcVersion, int online, int flagged, int banned) {
        Map<String, Object> body = new HashMap<>();
        body.put("server_id", this.serverId);
        body.put("name", (name == null) ? "" : name);
        body.put("mc_version", (mcVersion == null) ? "unknown" : mcVersion);
        body.put("online", online);
        body.put("flagged_total", flagged);
        body.put("banned_total", banned);
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/heartbeat")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode() == 200);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean ping() {
        try {
            HttpResponse<String> resp = this.http.send(
                    req(this.baseUrl + "/health").GET().timeout(Duration.ofSeconds(4L)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode() == 200);
        } catch (Exception e) {
            return false;
        }
    }

    private HttpClient buildClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(6L))
                .build();
    }

    private HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10L));
    }

    private HttpRequest.Builder authReq(String url) {
        String tok = (this.sessionToken != null && !this.sessionToken.isEmpty())
                ? this.sessionToken : this.licenseKey;
        return HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Bearer " + tok)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12L));
    }

    private String reason(String body) {
        try {
            JsonElement d = this.gson.fromJson(body, JsonObject.class).get("detail");
            return (d != null) ? d.getAsString() : "unknown";
        } catch (Exception e) {
            return (body != null && body.length() < 200) ? body : "unknown";
        }
    }

    private String normalizeCheck(String checkType) {
        return (checkType.equals("killaura") || checkType.equals("aimassist")) ? "combat" : checkType;
    }
}
