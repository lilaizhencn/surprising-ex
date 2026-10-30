package com.surprising.gateway.provider.content.support;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Untrusted article HTML has one canonical representation; plain text is derived from it. */
public final class RichArticle {
    public static final int MAX_HTML_LENGTH = 64_000;
    public static final String MEDIA_PATH = "/api/v1/content/media/";
    private static final Pattern MEDIA = Pattern.compile(Pattern.quote(MEDIA_PATH)
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Cleaner CLEANER = new Cleaner(new Safelist()
            .addTags("p", "br", "h2", "h3", "strong", "b", "em", "i", "u", "s", "blockquote",
                    "ul", "ol", "li", "hr", "code", "pre", "a", "img", "audio", "video")
            .addAttributes("a", "href", "title").addProtocols("a", "href", "https", "mailto")
            .addAttributes("img", "src", "alt", "title")
            .addAttributes("audio", "src").addAttributes("video", "src")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow"));

    private RichArticle() { }
    public record Article(String html, String text, List<UUID> mediaIds) { }

    public static Article clean(String value) {
        return clean(value,12_000);
    }

    public static Article cleanLegal(String value) { return clean(value,60_000); }

    private static Article clean(String value,int maxTextLength) {
        if (value == null || value.isBlank() || value.length() > MAX_HTML_LENGTH)
            throw new IllegalArgumentException("article HTML must contain 1 to 64000 characters");
        Document document = CLEANER.clean(Jsoup.parseBodyFragment(value));
        document.outputSettings().prettyPrint(false);
        var media = document.select("img,audio,video");
        if (media.size() > 24) throw new IllegalArgumentException("an article supports at most 24 media items");
        for (var element : media) {
            if (!MEDIA.matcher(element.attr("src")).matches())
                throw new IllegalArgumentException("article media must be uploaded through the media library");
            if (element.tagName().equals("img")) {
                element.attr("loading", "lazy").attr("decoding", "async");
            } else {
                element.attr("controls", "").attr("preload", "metadata");
                if (element.tagName().equals("video")) element.attr("playsinline", "");
            }
        }
        String text = document.body().text().trim();
        if (text.isBlank() && media.isEmpty()) throw new IllegalArgumentException("article content is empty");
        if (text.isBlank()) text = media.stream().map(e -> "[" + e.tagName() + "]").collect(java.util.stream.Collectors.joining(" "));
        if (text.length() > maxTextLength) throw new IllegalArgumentException("article text must be at most "+maxTextLength+" characters");
        String html = document.body().html();
        if (html.length() > MAX_HTML_LENGTH) throw new IllegalArgumentException("article HTML is too large");
        return new Article(html, text, media.stream().map(e -> UUID.fromString(e.attr("src").substring(MEDIA_PATH.length()))).distinct().toList());
    }
}
