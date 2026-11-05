package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.telegram.bot.service.TelegramWebAppInitDataVerifier;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramWebAppInitDataVerifierTest {

    private final TelegramWebAppInitDataVerifier verifier = new TelegramWebAppInitDataVerifier();
    private static final String BOT_TOKEN = "123456789:ABCdefGHIjklMNOpqrsTUVwxyz";

    @Test
    void verifiesValidInitDataSuccessfully() throws Exception {
        long authDate = Instant.now().getEpochSecond();
        String userJson = "{\"id\":99887766,\"first_name\":\"Telegram\",\"last_name\":\"Tester\",\"username\":\"t_tester\",\"language_code\":\"zh-hans\"}";

        String dataCheckString = "auth_date=" + authDate + "\nquery_id=AAH1234\nuser=" + userJson;

        // secret_key = HMAC_SHA256(key="WebAppData", data=botToken)
        Mac hmacSha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmacSha256.init(secretKeySpec);
        byte[] secretKey = hmacSha256.doFinal(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));

        // data_hash = HMAC_SHA256(key=secretKey, data=dataCheckString)
        Mac dataHmac = Mac.getInstance("HmacSHA256");
        dataHmac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        byte[] hashBytes = dataHmac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

        StringBuilder hashHex = new StringBuilder();
        for (byte b : hashBytes) {
            hashHex.append(String.format("%02x", b));
        }

        String initData = "query_id=AAH1234&user=" + URLEncoder.encode(userJson, StandardCharsets.UTF_8)
                + "&auth_date=" + authDate
                + "&hash=" + hashHex;

        var result = verifier.verify(initData, BOT_TOKEN);
        assertThat(result).isPresent();
        var user = result.get();
        assertThat(user.telegramId()).isEqualTo(99887766L);
        assertThat(user.firstName()).isEqualTo("Telegram");
        assertThat(user.lastName()).isEqualTo("Tester");
        assertThat(user.username()).isEqualTo("t_tester");
    }

    @Test
    void rejectsTamperedInitData() {
        long authDate = Instant.now().getEpochSecond();
        String userJson = "{\"id\":99887766,\"first_name\":\"Telegram\"}";
        String initData = "query_id=AAH1234&user=" + URLEncoder.encode(userJson, StandardCharsets.UTF_8)
                + "&auth_date=" + authDate
                + "&hash=0000000000000000000000000000000000000000000000000000000000000000";

        var result = verifier.verify(initData, BOT_TOKEN);
        assertThat(result).isEmpty();
    }
}
