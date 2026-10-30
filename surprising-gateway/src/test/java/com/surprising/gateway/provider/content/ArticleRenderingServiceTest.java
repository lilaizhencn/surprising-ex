package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.config.ContentMediaProperties;
import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.service.ArticleRenderingService;
import com.surprising.gateway.provider.content.support.RichArticle;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ArticleRenderingServiceTest {
    @Test void publicDeliveryUsesHttpsCdnWithOneLookupAndDoesNotNeedUploadCredentials() {
        var repository=mock(ContentMediaRepository.class);
        var storage=new ContentR2Storage(new ContentMediaProperties(false,null,null,null,"app-public","https://cdn.surprising.top"));
        UUID id=UUID.randomUUID();
        when(repository.findFor(List.of(id))).thenReturn(List.of(new ContentMedia(id,ContentR2Storage.PREFIX+id+".png","image/png",100,9,Instant.now())));
        String source="<p>Article</p><img src='"+RichArticle.MEDIA_PATH+id+"'>";
        var rendered=new ArticleRenderingService(repository,storage).render(Arrays.asList(source,source,null,"<p>Text</p>"));
        assertThat(rendered.get(source)).contains("src=\"https://cdn.surprising.top/public/exchange/content/"+id+".png\"").doesNotContain("src=\"/api/");
        assertThat(rendered.get("<p>Text</p>")).isEqualTo("<p>Text</p>");
        verify(repository,times(1)).findFor(List.of(id));
        assertThatThrownBy(storage::requireAvailable).isInstanceOf(ResponseStatusException.class);
        storage.close();
    }
    @Test void missingManagedAssetsCannotTurnIntoArbitraryOrPrivateMediaUrls() {
        var repository=mock(ContentMediaRepository.class);
        when(repository.findFor(anyList())).thenReturn(List.of());
        var renderer=new ArticleRenderingService(repository,mock(ContentR2Storage.class));
        assertThatThrownBy(()->renderer.render(List.of("<img src='"+RichArticle.MEDIA_PATH+UUID.randomUUID()+"'>"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->renderer.render(List.of("<img src='https://cdn.surprising.top/private/c2c/file'>"))).isInstanceOf(IllegalArgumentException.class);
    }
}
