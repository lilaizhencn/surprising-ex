package com.surprising.gateway.provider.telegram.bot.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 与 Web 端 {@code posterGenerator.ts} 保持 1:1 视觉与数据结构一致的 900x1360 专属邀请函海报渲染器。
 */
final class TelegramAffiliatePosterRenderer {

    static final int WIDTH = 900;
    static final int HEIGHT = 1360;
    static final int QR_SIZE = 248;

    private static final String SANS_FONT = pickFont(
            "PingFang SC", "Noto Sans CJK SC", "Noto Sans SC",
            "Source Han Sans SC", "Microsoft YaHei", "WenQuanYi Micro Hei",
            "Helvetica Neue", "Inter", Font.SANS_SERIF);
    private static final String MONO_FONT = pickFont(
            "JetBrains Mono", "SF Mono", "Menlo", "DejaVu Sans Mono", Font.MONOSPACED);

    private TelegramAffiliatePosterRenderer() {
    }

    static byte[] renderPng(String inviteCode, String inviteLink, double rebateRatePercent,
                            long userId, BufferedImage qrImage) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            configureQuality(g);
            drawBackground(g);
            drawHeader(g);
            drawHeadline(g);
            drawPillars(g, formatPercent(rebateRatePercent));
            drawInviteCodeCard(g, inviteCode, userId);
            drawBottomQrCard(g, inviteLink, qrImage);
            drawFooter(g);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static void configureQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static void drawBackground(Graphics2D g) {
        // Deep obsidian institutional background
        LinearGradientPaint bg = new LinearGradientPaint(
                0f, 0f, WIDTH, HEIGHT,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(0x07, 0x09, 0x12), new Color(0x0d, 0x13, 0x24), new Color(0x07, 0x09, 0x10)}
        );
        g.setPaint(bg);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Ambient radial glows (gold + cyan)
        RadialGradientPaint goldGlow = new RadialGradientPaint(
                new Point2D.Float(WIDTH * 0.8f, 180f), 440f,
                new float[]{0f, 1f},
                new Color[]{new Color(245, 196, 81, 56), new Color(245, 196, 81, 0)}
        );
        g.setPaint(goldGlow);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        RadialGradientPaint cyanGlow = new RadialGradientPaint(
                new Point2D.Float(140f, HEIGHT * 0.65f), 420f,
                new float[]{0f, 1f},
                new Color[]{new Color(45, 212, 191, 36), new Color(45, 212, 191, 0)}
        );
        g.setPaint(cyanGlow);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Subtle architectural grid
        g.setColor(new Color(255, 255, 255, 9));
        g.setStroke(new BasicStroke(1f));
        for (int x = 60; x < WIDTH; x += 60) {
            g.drawLine(x, 0, x, HEIGHT);
        }
        for (int y = 60; y < HEIGHT; y += 60) {
            g.drawLine(0, y, WIDTH, y);
        }

        // Outer champagne-gold hairline frame
        g.setColor(new Color(245, 196, 81, 89));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(32, 32, WIDTH - 64, HEIGHT - 64, 56, 56));
    }

    private static void drawHeader(Graphics2D g) {
        g.setColor(new Color(0xf5, 0xc4, 0x51));
        g.setFont(new Font(SANS_FONT, Font.BOLD, 28));
        g.drawString("AETERN", 76, 108);

        RoundRectangle2D pill = new RoundRectangle2D.Double(WIDTH - 296, 76, 220, 42, 42, 42);
        g.setColor(new Color(245, 196, 81, 36));
        g.fill(pill);
        g.setColor(new Color(245, 196, 81, 115));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(pill);

        g.setColor(new Color(0xf9, 0xdf, 0xa0));
        g.setFont(new Font(SANS_FONT, Font.BOLD, 15));
        drawCenteredString(g, "EXCLUSIVE INVITATION", WIDTH - 186, 102);
    }

    private static void drawHeadline(Graphics2D g) {
        g.setFont(new Font(SANS_FONT, Font.BOLD, 54));
        g.setColor(new Color(0xf8, 0xfa, 0xfc));
        g.drawString("受邀开启机构级", 76, 220);

        g.setColor(new Color(0xf5, 0xc4, 0x51));
        g.drawString("数字资产交易体验", 76, 290);

        g.setColor(new Color(0x94, 0xa3, 0xb8));
        g.setFont(new Font(SANS_FONT, Font.PLAIN, 22));
        g.drawString("低延迟撮合 · 全产品线覆盖 · 透明资产托管", 76, 344);
    }

    private static void drawPillars(Graphics2D g, String rebateRateLabel) {
        String[][] pillars = {
                {"微秒级撮合", "Aeron 确定性核心引擎"},
                {"全产品矩阵", "现货 / 永续 / 交割 / 期权"},
                {"专属返佣", "最高 " + rebateRateLabel + "% 手续费回馈"}
        };
        for (int i = 0; i < pillars.length; i++) {
            int x = 76 + i * 254;
            int y = 398;
            RoundRectangle2D card = new RoundRectangle2D.Double(x, y, 236, 134, 32, 32);
            g.setColor(new Color(255, 255, 255, 10));
            g.fill(card);
            g.setColor(new Color(255, 255, 255, 26));
            g.setStroke(new BasicStroke(1f));
            g.draw(card);

            g.setColor(new Color(0xf5, 0xc4, 0x51));
            g.setFont(new Font(SANS_FONT, Font.BOLD, 20));
            g.drawString(pillars[i][0], x + 22, y + 52);

            g.setColor(new Color(0xcb, 0xd5, 0xe1));
            g.setFont(new Font(SANS_FONT, Font.PLAIN, 16));
            g.drawString(pillars[i][1], x + 22, y + 92);
        }
    }

    private static void drawInviteCodeCard(Graphics2D g, String inviteCode, long userId) {
        RoundRectangle2D card = new RoundRectangle2D.Double(76, 584, WIDTH - 152, 236, 44, 44);
        g.setPaint(new GradientPaint(76f, 584f, new Color(245, 196, 81, 36),
                WIDTH - 76f, 820f, new Color(45, 212, 191, 20)));
        g.fill(card);
        g.setColor(new Color(245, 196, 81, 115));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(card);

        g.setColor(new Color(0xcb, 0xd5, 0xe1));
        g.setFont(new Font(SANS_FONT, Font.PLAIN, 20));
        drawCenteredString(g, "专属短邀请码", WIDTH / 2, 646);

        g.setColor(Color.WHITE);
        g.setFont(new Font(MONO_FONT, Font.BOLD, 66));
        drawCenteredString(g, inviteCode, WIDTH / 2, 732);

        g.setColor(new Color(0xf9, 0xdf, 0xa0));
        g.setFont(new Font(SANS_FONT, Font.PLAIN, 18));
        drawCenteredString(g, "邀请人 UID " + userId + " · 注册即享专属返佣权益", WIDTH / 2, 784);
    }

    private static void drawBottomQrCard(Graphics2D g, String inviteLink, BufferedImage qrImage) {
        RoundRectangle2D card = new RoundRectangle2D.Double(76, 872, WIDTH - 152, 404, 48, 48);
        g.setColor(new Color(15, 23, 42, 224));
        g.fill(card);
        g.setColor(new Color(255, 255, 255, 31));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(card);

        int qrBoxX = 116;
        int qrBoxY = 950;
        RoundRectangle2D qrBg = new RoundRectangle2D.Double(qrBoxX - 14, qrBoxY - 14, QR_SIZE + 28, QR_SIZE + 28, 36, 36);
        g.setColor(Color.WHITE);
        g.fill(qrBg);
        g.drawImage(qrImage, qrBoxX, qrBoxY, QR_SIZE, QR_SIZE, null);

        int textX = qrBoxX + QR_SIZE + 48;
        g.setColor(new Color(0xf8, 0xfa, 0xfc));
        g.setFont(new Font(SANS_FONT, Font.BOLD, 30));
        g.drawString("扫码加入 AETERN", textX, 1008);

        g.setColor(new Color(0x94, 0xa3, 0xb8));
        g.setFont(new Font(SANS_FONT, Font.PLAIN, 20));
        g.drawString("使用手机相机或浏览器扫码", textX, 1054);
        g.drawString("直达专属邀请注册页面", textX, 1088);

        RoundRectangle2D linkPill = new RoundRectangle2D.Double(textX, 1126, 360, 56, 24, 24);
        g.setColor(new Color(45, 212, 191, 36));
        g.fill(linkPill);
        g.setColor(new Color(0x5e, 0xea, 0xd4));
        g.setFont(new Font(MONO_FONT, Font.PLAIN, 16));
        String clipped = inviteLink.length() > 36 ? inviteLink.substring(0, 33) + "..." : inviteLink;
        g.drawString(clipped, textX + 18, 1160);
    }

    private static void drawFooter(Graphics2D g) {
        g.setColor(new Color(0x64, 0x74, 0x8b));
        g.setFont(new Font(SANS_FONT, Font.PLAIN, 15));
        g.drawString("AETERN · High-Performance Exchange & Derivatives", 76, 1306);
    }

    private static void drawCenteredString(Graphics2D g, String text, int centerX, int baselineY) {
        FontMetrics metrics = g.getFontMetrics();
        int x = centerX - metrics.stringWidth(text) / 2;
        g.drawString(text, x, baselineY);
    }

    static String formatPercent(double value) {
        if (!Double.isFinite(value) || value <= 0d) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String pickFont(String... candidates) {
        try {
            Set<String> available = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String candidate : candidates) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Headless environments without system font list fall back to logical font names.
        }
        return candidates[candidates.length - 1];
    }
}
