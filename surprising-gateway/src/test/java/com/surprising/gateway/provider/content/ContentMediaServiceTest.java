package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import com.surprising.gateway.provider.content.support.RichArticle;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ContentMediaServiceTest {
    private final AuthService auth=mock(AuthService.class);
    private final ContentMediaRepository repository=mock(ContentMediaRepository.class);
    private final ContentR2Storage storage=mock(ContentR2Storage.class);
    private final ContentMediaService service=new ContentMediaService(auth,repository,storage,mock(com.surprising.gateway.provider.content.repository.ContentMediaReferenceRepository.class));
    private final byte[] png={(byte)137,80,78,71,13,10,26,10,0,0,0,0};
    private void admin() { when(auth.requireAdminPermission("admin","admin.content.media.write")).thenReturn(new JwtPrincipal(9,"admin","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(60))); }
    @Test void uploadsOnlyServerGeneratedPublicKeysAndPersistsConfirmedMetadata() throws Exception {
        admin();
        doAnswer(call->{assertThat(Files.readAllBytes(call.getArgument(2))).isEqualTo(png);return null;}).when(storage).put(anyString(),eq("image/png"),any());
        var result=service.upload("admin","image/png",png.length,new ByteArrayInputStream(png));
        assertThat(result.url()).isEqualTo(RichArticle.MEDIA_PATH+result.mediaId());
        var metadata=ArgumentCaptor.forClass(ContentMedia.class);verify(repository).insert(metadata.capture());
        assertThat(metadata.getValue().objectKey()).isEqualTo(ContentR2Storage.PREFIX+result.mediaId()+".png");
        assertThat(metadata.getValue().uploadedBy()).isEqualTo(9);
        verify(storage,never()).delete(anyString());
    }
    @Test void malformedAndOversizedMediaNeverReachR2OrMetadata() throws Exception {
        admin();
        assertThatThrownBy(()->service.upload("admin","image/svg+xml",12,new ByteArrayInputStream(png))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.upload("admin","image/png",21L*1024*1024,new ByteArrayInputStream(png))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.upload("admin","image/png",12,new ByteArrayInputStream(new byte[12]))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.upload("admin","image/png",13,new ByteArrayInputStream(png))).isInstanceOf(ResponseStatusException.class);
        verify(storage,never()).put(anyString(),anyString(),any());verifyNoInteractions(repository);
    }
    @Test void failedMetadataWriteRemovesOnlyTheObjectCreatedByThisUpload() {
        admin();doThrow(new IllegalStateException("database unavailable")).when(repository).insert(any());
        assertThatThrownBy(()->service.upload("admin","image/png",12,new ByteArrayInputStream(png))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Media could not be uploaded");
        var key=ArgumentCaptor.forClass(String.class);verify(storage).put(key.capture(),eq("image/png"),any());verify(storage).delete(key.getValue());
        assertThat(key.getValue()).startsWith("public/exchange/content/").endsWith(".png");
    }
    @Test void missingAndMismatchedMediaCannotBeSavedAsArticleContent() {
        UUID id=UUID.randomUUID();String html="<img src='"+RichArticle.MEDIA_PATH+id+"'>";
        when(repository.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.requireArticleMedia(html)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("missing media");
        when(repository.find(id)).thenReturn(Optional.of(new ContentMedia(id,"key","video/mp4",12,9,Instant.now())));
        assertThatThrownBy(()->service.requireArticleMedia(html)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("type does not match");
    }
    @Test void deniedOperatorsCannotProbeOrUploadToStorage() {
        when(auth.requireAdminPermission(anyString(),anyString())).thenThrow(new IllegalStateException("denied"));
        assertThatThrownBy(()->service.upload("reader","image/png",12,new ByteArrayInputStream(png))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(storage,repository);
    }
}
