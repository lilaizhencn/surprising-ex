package com.surprising.gateway.provider.content.service;

import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.support.RichArticle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Stored articles reference managed IDs. Public responses resolve them to immutable HTTPS CDN URLs in one batch. */
@Service
public class ArticleRenderingService {
    private final ContentMediaRepository media;
    private final ContentR2Storage storage;
    public ArticleRenderingService(ContentMediaRepository media,ContentR2Storage storage) { this.media=media;this.storage=storage; }
    public Map<String,String> render(List<String> articles) {
        var documents=new LinkedHashMap<String,Document>();
        var ids=new LinkedHashSet<UUID>();
        for(String html:articles) {
            if(html==null || documents.containsKey(html))continue;
            var safe=RichArticle.cleanLegal(html);
            ids.addAll(safe.mediaIds());documents.put(html,Jsoup.parseBodyFragment(safe.html()));
        }
        Map<UUID,ContentMedia> assets=media.findFor(List.copyOf(ids)).stream().collect(Collectors.toMap(ContentMedia::mediaId,m->m));
        var rendered=new LinkedHashMap<String,String>();
        documents.forEach((source,document)->{
            document.outputSettings().prettyPrint(false);
            for(var element:document.select("img,audio,video")) {
                UUID id=UUID.fromString(element.attr("src").substring(RichArticle.MEDIA_PATH.length()));
                var asset=assets.get(id);
                if(asset==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Article media is temporarily unavailable.");
                element.attr("src",storage.publicUrl(asset.objectKey()).toString());
            }
            rendered.put(source,document.body().html());
        });
        return rendered;
    }
}
