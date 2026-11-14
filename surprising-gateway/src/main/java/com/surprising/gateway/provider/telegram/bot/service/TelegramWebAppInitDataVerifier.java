package com.surprising.gateway.provider.telegram.bot.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class TelegramWebAppInitDataVerifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebAppInitDataVerifier.class);
    private static final Duration MAX_AUTH_AGE = Duration.ofHours(24);
    private final ObjectMapper objectMapper;

    public TelegramWebAppInitDataVerifier() {
        this(new ObjectMapper());
    }

    @Autowired
    public TelegramWebAppInitDataVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public record VerifiedUser(
            long telegramId,
            String firstName,
            String lastName,
            String username,
            String languageCode
    ) {
        public String getDisplayName() {
            if (firstName != null && lastName != null) {
                return firstName + " " + lastName;
            } else if (firstName != null) {
                return firstName;
            } else if (username != null) {
                return username;
            }
            return "Telegram User " + telegramId;
        }
    }

    public Optional<VerifiedUser> verify(String initData, String botToken) {
        if (initData == null || initData.isBlank() || botToken == null || botToken.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, String> params = new HashMap<>();
            String receivedHash = null;

            for (String pair : initData.split("&")) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx < 0) continue;
                String key = URLDecoder.decode(pair.substring(0, eqIdx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eqIdx + 1), StandardCharsets.UTF_8);
                if ("hash".equals(key)) {
                    receivedHash = val;
                } else {
                    params.put(key, val);
                }
            }

            if (receivedHash == null || receivedHash.isBlank()) {
                log.warn("Missing hash in Telegram WebApp initData");
                return Optional.empty();
            }

            // 1. 检查 auth_date 防重放
            String authDateStr = params.get("auth_date");
            if (authDateStr == null) {
                log.warn("Missing auth_date in Telegram WebApp initData");
                return Optional.empty();
            }
            try {
                long authDateSec = Long.parseLong(authDateStr);
                Instant authTime = Instant.ofEpochSecond(authDateSec);
                if (Instant.now().isAfter(authTime.plus(MAX_AUTH_AGE))) {
                    log.warn("Telegram WebApp initData expired, auth_date={}", authTime);
                    return Optional.empty();
                }
            } catch (NumberFormatException e) {
                return Optional.empty();
            }

            // 2. 按键名升序排序拼接 data_check_string
            List<String> sortedKeys = new ArrayList<>(params.keySet());
            Collections.sort(sortedKeys);

            StringBuilder dataCheckString = new StringBuilder();
            for (int i = 0; i < sortedKeys.size(); i++) {
                String k = sortedKeys.get(i);
                if (i > 0) dataCheckString.append("\n");
                dataCheckString.append(k).append("=").append(params.get(k));
            }

            // 3. 计算 HMAC-SHA256
            // secret_key = HMAC_SHA256(key="WebAppData", data=botToken)
            Mac hmacSha256 = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmacSha256.init(secretKeySpec);
            byte[] secretKey = hmacSha256.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

            // data_hash = HMAC_SHA256(key=secretKey, data=dataCheckString)
            Mac dataHmac = Mac.getInstance("HmacSHA256");
            dataHmac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] hashBytes = dataHmac.doFinal(dataCheckString.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder calculatedHex = new StringBuilder();
            for (byte b : hashBytes) {
                calculatedHex.append(String.format("%02x", b));
            }

            if (!MessageDigest.isEqual(calculatedHex.toString().getBytes(StandardCharsets.UTF_8),
                    receivedHash.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
                log.warn("Telegram WebApp initData hash mismatch!");
                return Optional.empty();
            }

            // 4. 解析 user JSON
            String userJson = params.get("user");
            if (userJson == null || userJson.isBlank()) {
                log.warn("Missing user object in Telegram WebApp initData");
                return Optional.empty();
            }

            Map<?, ?> userMap = objectMapper.readValue(userJson, Map.class);
            long telegramId = ((Number) userMap.get("id")).longValue();
            String firstName = (String) userMap.get("first_name");
            String lastName = (String) userMap.get("last_name");
            String username = (String) userMap.get("username");
            String languageCode = (String) userMap.get("language_code");

            return Optional.of(new VerifiedUser(telegramId, firstName, lastName, username, languageCode));

        } catch (Exception ex) {
            log.error("Failed to verify Telegram WebApp initData: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
