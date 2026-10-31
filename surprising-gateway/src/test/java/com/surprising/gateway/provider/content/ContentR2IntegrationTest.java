package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.config.ContentMediaProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/** Opt-in real dedicated exchange R2/CDN round trip; deletes only new objects created by this test. */
@EnabledIfSystemProperty(named="content.r2.test.enabled",matches="true")
class ContentR2IntegrationTest {
    @Test void realImageAudioAndVideoAreUploadedReadableAndVideoSupportsByteRanges() throws Exception {
        var config=new FileSystemResource(System.getProperty("content.r2.test.config"));
        var sources=new YamlPropertySourceLoader().load("content-r2",config);
        var properties=new Binder(ConfigurationPropertySources.from(sources))
                .bind("surprising.content.r2",Bindable.of(ContentMediaProperties.class))
                .orElseThrow(()->new IllegalStateException("R2 YAML configuration is missing"));
        assertThat(properties.enabled()).isTrue();
        var storage=new ContentR2Storage(properties);
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            for(var file:Map.of("image.png","image/png","audio.wav","audio/wav","video.mp4","video/mp4").entrySet()) {
                Path path=Path.of(System.getProperty("content.r2.test.media-dir"),file.getKey());
                byte[] expected=Files.readAllBytes(path);
                String key=ContentR2Storage.PREFIX+UUID.randomUUID()+file.getKey().substring(file.getKey().lastIndexOf('.'));
                System.out.println("R2 test object: "+storage.publicUrl(key));
                try {
                    storage.put(key,file.getValue(),path);
                    var response=http.send(HttpRequest.newBuilder(storage.publicUrl(key)).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(response.statusCode()).as(file.getKey()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith(file.getValue());
                    assertThat(response.body()).isEqualTo(expected);
                    if(file.getValue().equals("video/mp4")) {
                        var range=http.send(HttpRequest.newBuilder(storage.publicUrl(key)).timeout(Duration.ofSeconds(30)).header("Range","bytes=0-31").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                        assertThat(range.statusCode()).isEqualTo(206);
                        assertThat(range.body()).isEqualTo(java.util.Arrays.copyOf(expected,32));
                    }
                } finally { storage.delete(key); }
            }
        } finally { storage.close(); }
    }
}
