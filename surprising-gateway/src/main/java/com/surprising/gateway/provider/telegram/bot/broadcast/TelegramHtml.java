package com.surprising.gateway.provider.telegram.bot.broadcast;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;

/** Converts editor HTML into the tag subset Telegram accepts in HTML parse mode. */
public final class TelegramHtml {

    public static final int MAX_LENGTH = 4096;
    private static final Set<String> DROP_WITH_CONTENT = Set.of("script", "style", "iframe", "object", "embed", "noscript");

    private TelegramHtml() {}

    public static String toTelegram(String html) {
        if (html == null || html.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        ArrayDeque<String> open = new ArrayDeque<>();
        int i = 0;
        int length = html.length();
        while (i < length) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                out.append(escape(decode(html.substring(i))));
                break;
            }
            if (lt + 1 < length) {
                char next = html.charAt(lt + 1);
                if (!(Character.isLetter(next) || next == '/' || next == '!' || next == '?')) {
                    out.append(escape(decode(html.substring(i, lt)))).append("&lt;");
                    i = lt + 1;
                    continue;
                }
            }
            out.append(escape(decode(html.substring(i, lt))));
            int gt = html.indexOf('>', lt);
            if (gt < 0) {
                out.append(escape(decode(html.substring(lt))));
                break;
            }
            String raw = html.substring(lt + 1, gt).trim();
            i = gt + 1;
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("?")) continue;
            boolean closing = raw.startsWith("/");
            String body = closing ? raw.substring(1).trim() : raw;
            if (body.endsWith("/")) body = body.substring(0, body.length() - 1).trim();
            int space = indexOfWhitespace(body);
            String name = (space < 0 ? body : body.substring(0, space)).toLowerCase(Locale.ROOT);
            String attrs = space < 0 ? "" : body.substring(space);
            if (name.isEmpty()) continue;
            if (DROP_WITH_CONTENT.contains(name)) {
                if (!closing) i = skipUntilClose(html, i, name);
                continue;
            }
            String telegram = telegramName(name);
            if ("br".equals(name) || "hr".equals(name)) {
                out.append('\n');
                continue;
            }
            if ("li".equals(name) && !closing) {
                out.append("• ");
                continue;
            }
            if (telegram == null) {
                if (!closing && block(name)) {
                    // keep the text, break after the block
                } else if (closing && block(name)) {
                    out.append('\n');
                }
                continue;
            }
            if (closing) {
                closeThrough(out, open, telegram);
                continue;
            }
            if ("a".equals(telegram)) {
                String href = safeHref(attrs);
                if (href == null) continue;
                if (open.contains("a")) closeThrough(out, open, "a");
                open.push("a");
                out.append("<a href=\"").append(escape(href)).append("\">");
                continue;
            }
            open.push(telegram);
            out.append('<').append(telegram).append('>');
        }
        while (!open.isEmpty()) {
            out.append("</").append(open.pop()).append('>');
        }
        return collapse(out.toString());
    }

    public static String preview(String telegramHtml) {
        String text = telegramHtml == null ? "" : telegramHtml.replaceAll("<[^>]+>", "");
        text = decode(text).replaceAll("\\s+", " ").trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private static void closeThrough(StringBuilder out, ArrayDeque<String> open, String name) {
        if (!open.contains(name)) return;
        while (!open.isEmpty()) {
            String current = open.pop();
            out.append("</").append(current).append('>');
            if (current.equals(name)) return;
        }
    }

    private static int skipUntilClose(String html, int from, String name) {
        String marker = "</" + name;
        int found = html.toLowerCase(Locale.ROOT).indexOf(marker, from);
        if (found < 0) return html.length();
        int gt = html.indexOf('>', found);
        return gt < 0 ? html.length() : gt + 1;
    }

    private static String telegramName(String name) {
        return switch (name) {
            case "b", "strong" -> "b";
            case "i", "em" -> "i";
            case "u", "ins" -> "u";
            case "s", "strike", "del" -> "s";
            case "code" -> "code";
            case "pre" -> "pre";
            case "blockquote" -> "blockquote";
            case "a" -> "a";
            default -> null;
        };
    }

    private static boolean block(String name) {
        return name.equals("p") || name.equals("div") || name.equals("li") || name.equals("tr")
                || name.equals("h1") || name.equals("h2") || name.equals("h3")
                || name.equals("h4") || name.equals("h5") || name.equals("h6")
                || name.equals("section") || name.equals("article");
    }

    private static String safeHref(String attrs) {
        String href = attribute(attrs, "href");
        if (href == null) return null;
        String value = decode(href).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:") || lower.startsWith("tg://")) {
            if (value.indexOf('"') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) return null;
            return value;
        }
        return null;
    }

    private static String attribute(String attrs, String name) {
        String lower = attrs.toLowerCase(Locale.ROOT);
        String key = name + "=";
        int at = lower.indexOf(key);
        if (at < 0) return null;
        int valueAt = at + key.length();
        if (valueAt >= attrs.length()) return null;
        char quote = attrs.charAt(valueAt);
        if (quote == '"' || quote == '\'') {
            int end = attrs.indexOf(quote, valueAt + 1);
            return end < 0 ? null : attrs.substring(valueAt + 1, end);
        }
        int end = indexOfWhitespace(attrs.substring(valueAt));
        return end < 0 ? attrs.substring(valueAt) : attrs.substring(valueAt, valueAt + end);
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private static String decode(String raw) {
        return raw.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String collapse(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n{3,}", "\n\n").trim();
        return normalized;
    }
}
