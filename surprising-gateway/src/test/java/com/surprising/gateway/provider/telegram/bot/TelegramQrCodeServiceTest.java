package com.surprising.gateway.provider.telegram.bot;

import com.surprising.gateway.provider.telegram.bot.service.TelegramQrCodeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramQrCodeServiceTest {

    private final TelegramQrCodeService qrCodeService = new TelegramQrCodeService();

    @Test
    void generatesValidPngBytesForAddress() {
        byte[] bytes = qrCodeService.generateQrCodePng("TYDzsxdczEfD17Q6yoRPAxmGgkPxT123456", 200, 200);
        assertThat(bytes).isNotEmpty();
        // PNG 文件魔数校验: 0x89 0x50 0x4E 0x47
        assertThat(bytes[0]).isEqualTo((byte) 0x89);
        assertThat(bytes[1]).isEqualTo((byte) 0x50);
        assertThat(bytes[2]).isEqualTo((byte) 0x4E);
        assertThat(bytes[3]).isEqualTo((byte) 0x47);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> qrCodeService.generateQrCodePng("", 200, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
