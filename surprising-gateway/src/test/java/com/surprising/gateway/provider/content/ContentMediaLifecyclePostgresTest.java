package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.config.ContentMediaProperties;
import com.surprising.gateway.provider.content.model.ContentMedia;
import com.surprising.gateway.provider.content.repository.*;
import com.surprising.gateway.provider.content.service.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".*surprising_content_qa_.*")
class ContentMediaLifecyclePostgresTest {
    private String schema;
    private JdbcTemplate base;
    private ContentMediaRepository media;
    private ContentMediaReferenceRepository references;
    private ContentR2Storage storage;
    private ContentMediaCleanupService cleanup;
    private ContentMediaService articles;
    private DataSourceTransactionManager manager;
    @BeforeEach void setup() {
        String url=System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user=System.getenv("INSTRUMENT_TEST_DB_USER"),password=System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        base=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="content_lifecycle_"+UUID.randomUUID().toString().replace("-","");
        base.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE gateway_content_media(media_id UUID PRIMARY KEY,object_key TEXT NOT NULL,content_type TEXT NOT NULL,size_bytes BIGINT NOT NULL,uploaded_by BIGINT NOT NULL,created_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE gateway_content_media_references(owner_type TEXT NOT NULL,owner_id BIGINT NOT NULL,media_id UUID NOT NULL REFERENCES gateway_content_media(media_id),PRIMARY KEY(owner_type,owner_id,media_id))");
        media=new ContentMediaRepository(jdbc);references=new ContentMediaReferenceRepository(jdbc);storage=mock(ContentR2Storage.class);
        manager=new DataSourceTransactionManager(source);
        cleanup=cleanup(true);
        articles=new ContentMediaService(mock(AuthService.class),media,storage,references);
    }
    @AfterEach void close() { if(base!=null && schema!=null)base.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private ContentMediaCleanupService cleanup(boolean enabled) {
        return new ContentMediaCleanupService(media,storage,new ContentMediaProperties(enabled,null,null,null,"surprising-ex-public","https://ex-cdn.tokdou.com"),manager,references);
    }
    private ContentMedia upload(boolean old) {
        UUID id=UUID.randomUUID();
        var value=new ContentMedia(id,ContentR2Storage.PREFIX+id+".png","image/png",12,9,old?Instant.now().minus(Duration.ofDays(2)):Instant.now());
        media.insert(value);return value;
    }
    @Test void onlyAgedUnattachedUploadsAreDeleted() {
        var orphan=upload(true);var recent=upload(false);var retained=upload(true);
        new TransactionTemplate(manager).executeWithoutResult(status->articles.retainArticleMedia("LEGAL",7,List.of("<img src='/api/v1/content/media/"+retained.mediaId()+"'>")));
        cleanup.clean();
        verify(storage).delete(orphan.objectKey());verifyNoMoreInteractions(storage);
        assertThat(media.find(orphan.mediaId())).isEmpty();assertThat(media.find(recent.mediaId())).isPresent();assertThat(media.find(retained.mediaId())).isPresent();
    }
    @Test void storageFailureRetainsMetadataForIdempotentRetry() {
        var orphan=upload(true);doThrow(new IllegalStateException("controlled storage failure")).when(storage).delete(orphan.objectKey());
        cleanup.clean();assertThat(media.find(orphan.mediaId())).isPresent();
        reset(storage);cleanup.clean();verify(storage).delete(orphan.objectKey());assertThat(media.find(orphan.mediaId())).isEmpty();
    }
    @Test void concurrentAttachmentCannotCommitAfterCleanupClaimsTheUpload() throws Exception {
        var orphan=upload(true);var deleting=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{deleting.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test deadline");return null;}).when(storage).delete(orphan.objectKey());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var collected=executor.submit(cleanup::clean);
            assertThat(deleting.await(3,TimeUnit.SECONDS)).isTrue();
            var retained=executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->articles.retainArticleMedia("ANNOUNCEMENT",8,List.of("<img src='/api/v1/content/media/"+orphan.mediaId()+"'>"))));
            try { assertThatThrownBy(()->retained.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            collected.get(3,TimeUnit.SECONDS);
            assertThatThrownBy(()->retained.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(references.exists(orphan.mediaId())).isFalse();assertThat(media.find(orphan.mediaId())).isEmpty();
        } finally { release.countDown(); }
    }
    @Test void anAttachmentHoldingTheMediaLockIsSkippedByCleanup() throws Exception {
        var retained=upload(true);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var saved=executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                media.lockForReference(List.of(retained.mediaId()));locked.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test deadline"); }
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                references.replace("LEGAL",9,List.of(retained.mediaId()));
            }));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
            try {cleanup.clean();verifyNoInteractions(storage);}finally {release.countDown();}
            saved.get(3,TimeUnit.SECONDS);assertThat(references.exists(retained.mediaId())).isTrue();
            cleanup.clean();verifyNoInteractions(storage);
        } finally {release.countDown();}
    }
    @Test void everyRunHasABoundedBatchAndDisabledStorageDoesNoWork() {
        for(int index=0;index<25;index++)upload(true);
        cleanup(false).clean();verifyNoInteractions(storage);
        cleanup.clean();verify(storage,times(20)).delete(anyString());
        cleanup.clean();verify(storage,times(25)).delete(anyString());
    }
}
