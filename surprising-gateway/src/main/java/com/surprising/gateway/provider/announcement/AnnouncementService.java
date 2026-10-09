package com.surprising.gateway.provider.announcement;

import com.surprising.gateway.provider.announcement.AnnouncementModels.Announcement;
import com.surprising.gateway.provider.announcement.AnnouncementModels.AdminPage;
import com.surprising.gateway.provider.announcement.AnnouncementModels.ClientPage;
import com.surprising.gateway.provider.announcement.AnnouncementModels.SaveRequest;
import com.surprising.gateway.provider.announcement.AnnouncementModels.TranslationInput;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AdminApprovalService;
import com.surprising.gateway.provider.auth.AuthService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnnouncementService {

    private static final Set<String> CATEGORIES = Set.of("GENERAL", "PRODUCT", "MAINTENANCE", "SECURITY", "RISK");
    private static final Set<String> PLACEMENTS = Set.of("CENTER", "MODAL", "BANNER");
    private static final Set<String> PLATFORMS = Set.of("WEB", "IOS", "ANDROID");
    private static final Set<String> PRODUCT_LINES = Set.of("SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL",
            "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION");
    private static final List<String> REQUIRED_LOCALES = List.of("en-US", "zh-CN");

    private final AnnouncementRepository repository;
    private final AuthService authService;
    private final AdminApprovalService approvalService;

    public AnnouncementService(AnnouncementRepository repository, AuthService authService,
                               AdminApprovalService approvalService) {
        this.repository = repository;
        this.authService = authService;
        this.approvalService = approvalService;
    }

    public AdminPage adminList(String authorization, String status, String query, int offset, int limit) {
        adminPrincipal(authorization, "admin.announcements.read");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, Math.min(offset, 1_000_000));
        var result = repository.listAdmin(status, query, safeOffset, safeLimit);
        return new AdminPage(result.count(), result.announcements(), safeOffset, safeLimit,
                safeOffset + result.announcements().size() < result.count());
    }

    public Announcement adminGet(String authorization, long id) {
        adminPrincipal(authorization, "admin.announcements.read");
        return repository.findAdmin(id).orElseThrow(() -> notFound("announcement not found"));
    }

    public List<AnnouncementModels.AuditEntry> audit(String authorization, long id) {
        adminPrincipal(authorization, "admin.announcements.read");
        repository.findAdmin(id).orElseThrow(() -> notFound("announcement not found"));
        return repository.audit(id);
    }

    @Transactional
    public Announcement create(String authorization, SaveRequest request) {
        JwtPrincipal actor = adminPrincipal(authorization, "admin.announcements.write");
        SaveRequest normalized = validateAndNormalize(request, false);
        Instant now = Instant.now();
        long id = repository.create(normalized, actor.userId(), now);
        repository.audit(id, "CREATE", actor.userId(), actor.username(), null, now);
        return adminGetAfterWrite(id);
    }

    @Transactional
    public Announcement update(String authorization, long id, SaveRequest request) {
        JwtPrincipal actor = adminPrincipal(authorization, "admin.announcements.write");
        Announcement current = repository.findAdmin(id).orElseThrow(() -> notFound("announcement not found"));
        SaveRequest normalized = validateAndNormalize(request, true);
        if ("PUBLISHED".equals(current.status())) {
            List<String> locales = normalized.translations().stream().map(TranslationInput::locale).toList();
            if (!locales.containsAll(REQUIRED_LOCALES)) {
                throw badRequest("published announcements must retain complete zh-CN and en-US translations");
            }
        }
        Instant now = Instant.now();
        if (!repository.update(id, normalized, actor.userId(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "announcement was changed by another operator");
        }
        repository.audit(id, "UPDATE", actor.userId(), actor.username(), null, now);
        return adminGetAfterWrite(id);
    }

    @Transactional
    public Announcement publish(String authorization, long id, AnnouncementModels.PublishRequest request,
                                AdminApprovalService.AdminRequestMetadata metadata, byte[] rawBody) {
        JwtPrincipal actor = approvalService.requireWrite(authorization, "admin.announcements.publish",
                "gateway-admin", metadata, rawBody);
        Announcement current = repository.findAdmin(id).orElseThrow(() -> notFound("announcement not found"));
        if (request == null || request.version() == null) throw badRequest("version is required");
        if (current.version() != request.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "announcement was changed by another operator");
        }
        if ("WITHDRAWN".equals(current.status())) throw badRequest("withdrawn announcements must be saved as a draft before publishing");
        Map<String, TranslationInput> translations = new LinkedHashMap<>();
        for (var translation : current.translations()) translations.put(translation.locale(),
                new TranslationInput(translation.locale(), translation.title(), translation.summary(), translation.body()));
        for (String locale : REQUIRED_LOCALES) {
            TranslationInput translation = translations.get(locale);
            if (translation == null || translation.title().isBlank() || translation.body().isBlank()) {
                throw badRequest("publish requires complete zh-CN and en-US translations");
            }
        }
        if (current.placements().isEmpty() || current.productLines().isEmpty() || current.platforms().isEmpty()) {
            throw badRequest("announcement targeting is incomplete");
        }
        if (!current.placements().contains("CENTER")) {
            throw badRequest("published announcements must be available in the announcement center");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() > 1000) throw badRequest("reason is too long");
        Instant now = Instant.now();
        if (!repository.publish(id, request.version(), actor.userId(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "announcement cannot be published in its current state");
        }
        repository.audit(id, "PUBLISH", actor.userId(), actor.username(), reason, now);
        return adminGetAfterWrite(id);
    }

    @Transactional
    public Announcement withdraw(String authorization, long id, AnnouncementModels.WithdrawRequest request,
                                 AdminApprovalService.AdminRequestMetadata metadata, byte[] rawBody) {
        JwtPrincipal actor = approvalService.requireWrite(authorization, "admin.announcements.publish",
                "gateway-admin", metadata, rawBody);
        if (request == null || request.version() == null) throw badRequest("version is required");
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank()) throw badRequest("withdrawal reason is required");
        if (reason.length() > 1000) throw badRequest("reason is too long");
        repository.findAdmin(id).orElseThrow(() -> notFound("announcement not found"));
        Instant now = Instant.now();
        if (!repository.withdraw(id, request.version(), actor.userId(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "announcement was changed or already withdrawn");
        }
        repository.audit(id, "WITHDRAW", actor.userId(), actor.username(), reason, now);
        return adminGetAfterWrite(id);
    }

    public ClientPage clientList(String authorization, String locale, String productLine, String platform,
                                 String placement, int offset, int limit) {
        Long userId = optionalUserId(authorization);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, Math.min(offset, 1_000_000));
        List<Announcement> items = repository.listClient(locale, productLine, platform, placement, userId,
                safeOffset, safeLimit);
        int count = repository.countClient(productLine, platform, placement, userId, false);
        int unread = userId == null ? 0 : repository.countClient(productLine, platform, placement, userId, true);
        return new ClientPage(items, unread, safeOffset, safeLimit, safeOffset + items.size() < count);
    }

    public Announcement clientGet(String authorization, long id, String locale, String productLine,
                                  String platform, String placement) {
        Long userId = optionalUserId(authorization);
        return repository.findClient(id, locale, productLine, platform, placement, userId)
                .orElseThrow(() -> notFound("announcement not found"));
    }

    public int unreadCount(String authorization, String productLine, String platform, String placement) {
        JwtPrincipal user = userPrincipal(authorization);
        return repository.countClient(productLine, platform, placement, user.userId(), true);
    }

    public Announcement markRead(String authorization, long id, String locale, String productLine, String platform, String placement) {
        JwtPrincipal user = userPrincipal(authorization);
        if (!repository.markRead(id, user.userId(), productLine, platform, placement, Instant.now())) {
            throw notFound("announcement not found");
        }
        return repository.findClient(id, locale, productLine, platform, placement, user.userId())
                .orElseThrow(() -> notFound("announcement not found"));
    }

    public int markAllRead(String authorization, String productLine, String platform, String placement) {
        JwtPrincipal user = userPrincipal(authorization);
        return repository.markAllRead(user.userId(), productLine, platform, placement, Instant.now());
    }

    private Announcement adminGetAfterWrite(long id) {
        return repository.findAdmin(id).orElseThrow(() -> new IllegalStateException("announcement disappeared after write"));
    }

    private JwtPrincipal adminPrincipal(String authorization, String permission) {
        try {
            return authService.requireAdminPermission(authorization, permission);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private JwtPrincipal userPrincipal(String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private Long optionalUserId(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        return userPrincipal(authorization).userId();
    }

    private SaveRequest validateAndNormalize(SaveRequest request, boolean update) {
        if (request == null) throw badRequest("request body is required");
        if (update && request.version() == null) throw badRequest("version is required");
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
        Map<String, TranslationInput> byLocale = new LinkedHashMap<>();
        for (TranslationInput input : request.translations()) {
            if (input == null) throw badRequest("translation cannot be empty");
            String locale = AnnouncementRepository.normalizeLocale(input.locale());
            if (!locale.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw badRequest("invalid locale: " + locale);
            String title = input.title() == null ? "" : input.title().trim();
            String summary = input.summary() == null ? "" : input.summary().trim();
            String body = input.body() == null ? "" : input.body().trim();
            if (title.isBlank() || title.length() > 160) throw badRequest("translation title must contain 1 to 160 characters");
            if (summary.length() > 500) throw badRequest("translation summary must be at most 500 characters");
            if (body.isBlank() || body.length() > 12000) throw badRequest("translation body must contain 1 to 12000 characters");
            if (byLocale.putIfAbsent(locale, new TranslationInput(locale, title, summary, body)) != null) {
                throw badRequest("duplicate translation locale: " + locale);
            }
        }
        return new SaveRequest(category, priority, placements, productLines, platforms,
                request.startsAt(), request.expiresAt(), new ArrayList<>(byLocale.values()), request.version());
    }

    private List<String> normalizeTargets(List<String> values, Set<String> allowed, String name) {
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

    private String token(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
