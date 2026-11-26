package com.surprising.gateway.provider.telegram.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TelegramQrCodeServiceTest {

    @Test
    void generates900x1360InvitationPosterPng() throws Exception {
        TelegramQrCodeService service = new TelegramQrCodeService();

        byte[] png = service.generateInvitationPosterPng(
                "ABCD2345",
                "https://app.surprising.exchange/auth/register?invite=ABCD2345",
                30.0,
                10086L
        );

        assertThat(png).isNotEmpty();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(900);
        assertThat(image.getHeight()).isEqualTo(1360);
    }
}
