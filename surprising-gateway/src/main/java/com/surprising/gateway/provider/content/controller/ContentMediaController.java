package com.surprising.gateway.provider.content.controller;

import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ContentMediaController {
    private final ContentMediaService service;
    public ContentMediaController(ContentMediaService service) { this.service=service; }
    @PostMapping(path="/api/v1/admin/content/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContentMedia.Upload upload(@RequestHeader("Authorization") String authorization,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        try (var input=file.getInputStream()) { return service.upload(authorization,file.getContentType(),file.getSize(),input); }
    }
    @GetMapping("/api/v1/content/media/{mediaId}")
    public ResponseEntity<Void> media(@PathVariable UUID mediaId) {
        return ResponseEntity.status(HttpStatus.FOUND).location(service.publicUrl(mediaId))
                .cacheControl(CacheControl.noCache()).build();
    }
}
