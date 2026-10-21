package com.surprising.gateway.provider.announcement.controller;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 公告请求参数的格式、范围与规范化，仅供两个Controller调用。 */
final class AnnouncementParameters {
    private AnnouncementParameters() { }
    private static final Set<String> CATEGORIES = Set.of("GENERAL", "PRODUCT", "MAINTENANCE", "SECURITY", "RISK");
    private static final Set<String> PLACEMENTS = Set.of("CENTER", "MODAL", "BANNER");
    private static final Set<String> PLATFORMS = Set.of("WEB", "IOS", "ANDROID");
    private static final Set<String> PRODUCT_LINES = Set.of("SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION");

    static Content content(SaveRequest request, boolean update) {
        if (request == null) throw badRequest("request body is required");
        if (update) version(request.version());
        String category = token(request.category());
        if (!CATEGORIES.contains(category)) throw badRequest("unsupported announcement category");
        int priority = request.priority() == null ? 0 : request.priority();
        if (priority < 0 || priority > 100) throw badRequest("priority must be between 0 and 100");
        if (request.startsAt() == null) throw badRequest("startsAt is required");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(request.startsAt())) {
            throw badRequest("expiresAt must be after startsAt");
        }
        List<String> placements = normalizeTargets(request.placements(), PLACEMENTS, "placement");
        List<String> productLines = normalizeTargets(request.productLines(), PRODUCT_LINES, "productLine");
        List<String> platforms = normalizeTargets(request.platforms(), PLATFORMS, "platform");
        if (placements.isEmpty() || productLines.isEmpty() || platforms.isEmpty()) {
            throw badRequest("select at least one placement, product line and platform");
        }
        if (request.translations() == null || request.translations().isEmpty() || request.translations().size() > 12) {
            throw badRequest("one to twelve locale translations are required");
        }
        Map<String, Translation> byLocale = new LinkedHashMap<>();
        for (TranslationInput input : request.translations()) {
            if (input == null) throw badRequest("translation cannot be empty");
            String locale = normalizeLocale(input.locale());
            if (!locale.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw badRequest("invalid locale: " + locale);
            String title = input.title() == null ? "" : input.title().trim();
            String summary = input.summary() == null ? "" : input.summary().trim();
            String body = input.body() == null ? "" : input.body().trim();
            if (title.isBlank() || title.length() > 160) throw badRequest("translation title must contain 1 to 160 characters");
            if (summary.length() > 500) throw badRequest("translation summary must be at most 500 characters");
            if (body.isBlank() || body.length() > 12000) throw badRequest("translation body must contain 1 to 12000 characters");
            if (byLocale.putIfAbsent(locale, new Translation(locale, title, summary, body)) != null) {
                throw badRequest("duplicate translation locale: " + locale);
            }
        }
        return new Content(category, priority, placements, productLines, platforms,
                request.startsAt(), request.expiresAt(), List.copyOf(byLocale.values()));
    }

    private static List<String> normalizeTargets(List<String> values, Set<String> allowed, String name) {
        if (values == null || values.isEmpty()) return List.of("ALL");
        var normalized = new LinkedHashSet<String>();
        for (String value : values) {
            String token = token(value);
            if (!"ALL".equals(token) && !allowed.contains(token)) throw badRequest("unsupported " + name + ": " + token);
            normalized.add(token);
        }
        if (normalized.contains("ALL") && normalized.size() > 1) throw badRequest("ALL cannot be combined with specific " + name + " values");
        return List.copyOf(normalized);
    }

    private static String token(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static String normalizeLocale(String value) {
        String locale = value == null ? "en-US" : value.trim().replace('_', '-');
        return switch (locale.toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn", "zh-hans" -> "zh-CN";
            case "en", "en-us" -> "en-US";
            default -> locale;
        };
    }


    static void page(int offset,int limit) {
        if(offset<0 || offset>1000000 || limit<1 || limit>100)throw badRequest("invalid announcement page");
    }
    static void version(Long value) { if(value==null || value<1)throw badRequest("positive version is required"); }
    static void id(long value) { if(value<1)throw badRequest("positive announcementId is required"); }
    static String reason(String value,boolean required) {
        String reason=value==null?"":value.trim();
        if((required && reason.isBlank()) || reason.length()>1000)throw badRequest("reason must contain at most 1000 characters");
        return reason;
    }
    static String optionalText(String value) { return value==null||value.isBlank()?null:value.trim(); }
    static String status(String value) {
        value=optionalText(value);
        if(value!=null && !Set.of("DRAFT","PUBLISHED","WITHDRAWN").contains(value))throw badRequest("invalid announcement status");
        return value;
    }
    static ClientFilter filter(String locale,String line,String platform,String placement) {
        String requested=normalizeLocale(locale);
        if(!requested.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))throw badRequest("invalid locale");
        line=scope(line,"ALL",PRODUCT_LINES);platform=scope(platform,"WEB",PLATFORMS);placement=scope(placement,"CENTER",PLACEMENTS);
        int split=requested.indexOf('-');
        return new ClientFilter(requested,split<0?requested:requested.substring(0,split),line,platform,placement);
    }
    private static String scope(String value,String defaultValue,Set<String> allowed) {
        value=value==null||value.isBlank()?defaultValue:token(value);
        if(!value.equals("ALL") && !allowed.contains(value))throw badRequest("invalid announcement target");
        return value;
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
