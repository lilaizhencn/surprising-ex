package com.surprising.gateway.provider.telegram.bot.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TelegramQrCodeService {

    private static final int POSTER_QR_DARK_ARGB = 0xFF0A0D1A;
    private static final int POSTER_QR_LIGHT_ARGB = 0xFFFFFFFF;

    public byte[] generateQrCodePng(String text, int width, int height) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text for QR code cannot be empty");
        }
        try {
            BitMatrix matrix = encodeMatrix(text, width, height, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate QR code", e);
        }
    }

    /** 生成与 Web 返佣页同款逻辑的 900x1360 机构级专属邀请函海报 PNG。 */
    public byte[] generateInvitationPosterPng(String inviteCode, String inviteLink,
                                              double rebateRatePercent, long userId) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new IllegalArgumentException("inviteCode cannot be empty");
        }
        if (inviteLink == null || inviteLink.isBlank()) {
            throw new IllegalArgumentException("inviteLink cannot be empty");
        }
        try {
            BitMatrix matrix = encodeMatrix(inviteLink, TelegramAffiliatePosterRenderer.QR_SIZE,
                    TelegramAffiliatePosterRenderer.QR_SIZE, 2);
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(
                    matrix, new MatrixToImageConfig(POSTER_QR_DARK_ARGB, POSTER_QR_LIGHT_ARGB));
            return TelegramAffiliatePosterRenderer.renderPng(
                    inviteCode.trim(), inviteLink.trim(), rebateRatePercent, userId, qrImage);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate invitation poster", e);
        }
    }

    private BitMatrix encodeMatrix(String text, int width, int height, int margin) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, margin);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        return writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints);
    }
}
