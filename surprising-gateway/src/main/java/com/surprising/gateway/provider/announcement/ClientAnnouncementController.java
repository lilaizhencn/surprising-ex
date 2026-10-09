package com.surprising.gateway.provider.announcement;

import com.surprising.gateway.provider.announcement.AnnouncementModels.Announcement;
import com.surprising.gateway.provider.announcement.AnnouncementModels.ClientPage;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/announcements")
public class ClientAnnouncementController {

    private final AnnouncementService service;

    public ClientAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public ClientPage list(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestParam(defaultValue = "en-US") String locale,
                           @RequestParam(defaultValue = "ALL") String productLine,
                           @RequestParam(defaultValue = "WEB") String platform,
                           @RequestParam(defaultValue = "CENTER") String placement,
                           @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(defaultValue = "50") int limit) {
        return service.clientList(authorization, locale, productLine, platform, placement, offset, limit);
    }

    @GetMapping("/unread-count")
    public int unreadCount(@RequestHeader("Authorization") String authorization,
                           @RequestParam(defaultValue = "ALL") String productLine,
                           @RequestParam(defaultValue = "WEB") String platform,
                           @RequestParam(defaultValue = "CENTER") String placement) {
        return service.unreadCount(authorization, productLine, platform, placement);
    }

    @GetMapping("/{announcementId}")
    public Announcement get(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable long announcementId,
                            @RequestParam(defaultValue = "en-US") String locale,
                            @RequestParam(defaultValue = "ALL") String productLine,
                            @RequestParam(defaultValue = "WEB") String platform,
                            @RequestParam(defaultValue = "CENTER") String placement) {
        return service.clientGet(authorization, announcementId, locale, productLine, platform, placement);
    }

    @PostMapping("/{announcementId}/read")
    public Announcement markRead(@RequestHeader("Authorization") String authorization,
                                 @PathVariable long announcementId,
                                 @RequestParam(defaultValue = "en-US") String locale,
                                 @RequestParam(defaultValue = "ALL") String productLine,
                                 @RequestParam(defaultValue = "WEB") String platform,
                                 @RequestParam(defaultValue = "CENTER") String placement) {
        return service.markRead(authorization, announcementId, locale, productLine, platform, placement);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead(@RequestHeader("Authorization") String authorization,
                                            @RequestParam(defaultValue = "ALL") String productLine,
                                            @RequestParam(defaultValue = "WEB") String platform,
                                            @RequestParam(defaultValue = "CENTER") String placement) {
        return Map.of("updated", service.markAllRead(authorization, productLine, platform, placement));
    }
}
