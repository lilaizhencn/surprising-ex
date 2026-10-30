package com.surprising.gateway.provider.content.support;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Browser-playable media only; SVG, HTML and arbitrary executable attachments are excluded. */
public final class MediaFileType {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif", "image/webp", "webp",
            "audio/mpeg", "mp3", "audio/mp4", "m4a", "audio/wav", "wav", "video/mp4", "mp4");
    private MediaFileType() { }
    public static long limit(String type) {
        if (type == null || !EXTENSIONS.containsKey(type)) throw new IllegalArgumentException("unsupported media type");
        return (type.startsWith("image/") ? 20L : type.startsWith("audio/") ? 32L : 64L) * 1024 * 1024;
    }
    public static String extension(String type, byte[] header) {
        limit(type);
        boolean valid = header.length >= 12 && switch (type) {
            case "image/png" -> starts(header, new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg" -> (header[0]&255)==255 && (header[1]&255)==216 && (header[2]&255)==255;
            case "image/gif" -> at(header,0,"GIF87a") || at(header,0,"GIF89a");
            case "image/webp" -> at(header,0,"RIFF") && at(header,8,"WEBP");
            case "audio/wav" -> at(header,0,"RIFF") && at(header,8,"WAVE");
            case "audio/mpeg" -> at(header,0,"ID3") || ((header[0]&255)==255 && (header[1]&224)==224);
            case "audio/mp4", "video/mp4" -> at(header,4,"ftyp") && (header[0]&255)==0;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("file content does not match its media type");
        return EXTENSIONS.get(type);
    }
    private static boolean at(byte[] bytes, int offset, String value) {
        byte[] match = value.getBytes(StandardCharsets.US_ASCII);
        return bytes.length >= offset + match.length && java.util.Arrays.equals(bytes,offset,offset+match.length,match,0,match.length);
    }
    private static boolean starts(byte[] bytes, byte[] match) {
        return java.util.Arrays.equals(bytes,0,match.length,match,0,match.length);
    }
}
