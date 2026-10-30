package com.surprising.gateway.provider.content.service;

import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.repository.ContentMediaReferenceRepository;
import com.surprising.gateway.provider.content.support.MediaFileType;
import com.surprising.gateway.provider.content.support.RichArticle;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContentMediaService {
    private static final Logger LOG = LoggerFactory.getLogger(ContentMediaService.class);
    private final AuthService auth;
    private final ContentMediaRepository repository;
    private final ContentR2Storage storage;
    private final ContentMediaReferenceRepository references;
    public ContentMediaService(AuthService auth, ContentMediaRepository repository, ContentR2Storage storage, ContentMediaReferenceRepository references) {
        this.auth=auth; this.repository=repository; this.storage=storage; this.references=references;
    }

    /** Caller writes the article and these references in the same transaction. */
    @org.springframework.transaction.annotation.Transactional
    public void retainArticleMedia(String ownerType, long ownerId, java.util.List<String> articles) {
        var ids = articles.stream().filter(java.util.Objects::nonNull)
                .flatMap(html -> Jsoup.parseBodyFragment(html).select("img,audio,video").stream())
                .map(element -> UUID.fromString(element.attr("src").substring(RichArticle.MEDIA_PATH.length())))
                .distinct().sorted().toList();
        if (repository.lockForReference(ids).size() != ids.size()) throw badRequest("article contains expired media; upload the file again");
        references.replace(ownerType,ownerId,ids);
    }

    public ContentMedia.Upload upload(String authorization, String type, long size, InputStream input) throws IOException {
        final com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal actor;
        try { actor=auth.requireAdminPermission(authorization,"admin.content.media.write"); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in required."); }
        catch (IllegalStateException forbidden) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You do not have permission to upload article media."); }
        storage.requireAvailable();
        type = type==null ? "" : type.trim().toLowerCase(Locale.ROOT);
        final long limit;
        try { limit=MediaFileType.limit(type); }
        catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
        if (size<12 || size>limit) throw badRequest("media exceeds the size limit");
        Path temp = Files.createTempFile("surprising-article-", ".upload");
        String key = null;
        try {
            long copied = 0;
            byte[] buffer = new byte[64*1024];
            try (var output=Files.newOutputStream(temp)) {
                int count;
                while ((count=input.read(buffer))!=-1) {
                    copied+=count;
                    if (copied>size || copied>limit) throw badRequest("media size does not match the upload");
                    output.write(buffer,0,count);
                }
            }
            if (copied!=size) throw badRequest("media size does not match the upload");
            String extension;
            try (var check=Files.newInputStream(temp)) {
                try { extension=MediaFileType.extension(type,check.readNBytes(32)); }
                catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
            }
            UUID id=UUID.randomUUID();
            key=ContentR2Storage.PREFIX+id+"."+extension;
            storage.put(key,type,temp);
            repository.insert(new ContentMedia(id,key,type,size,actor.userId(),Instant.now()));
            return new ContentMedia.Upload(id,RichArticle.MEDIA_PATH+id,type,size);
        } catch (RuntimeException failure) {
            if (key!=null) {
                try { storage.delete(key); }
                catch (RuntimeException cleanup) { LOG.error("Failed to clean up article media {}",key,cleanup); }
            }
            if (failure instanceof ResponseStatusException response) throw response;
            LOG.error("Article media upload failed",failure);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Media could not be uploaded. Please try again.");
        } finally { Files.deleteIfExists(temp); }
    }

    public URI publicUrl(UUID id) {
        var media=repository.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Media is unavailable."));
        return storage.publicUrl(media.objectKey());
    }

    public void requireArticleMedia(String html) {
        if (html==null) return;
        for (var element:Jsoup.parseBodyFragment(html).select("img,audio,video")) {
            String source=element.attr("src");
            UUID id;
            try {
                if (!source.startsWith(RichArticle.MEDIA_PATH)) throw new IllegalArgumentException();
                id=UUID.fromString(source.substring(RichArticle.MEDIA_PATH.length()));
            } catch (IllegalArgumentException invalid) { throw badRequest("invalid article media"); }
            var media=repository.find(id).orElseThrow(()->badRequest("article contains missing media"));
            String prefix=switch(element.tagName()) { case "img" -> "image/"; case "audio" -> "audio/"; default -> "video/"; };
            if (!media.contentType().startsWith(prefix)) throw badRequest("article media type does not match its element");
        }
    }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
