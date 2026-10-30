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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in real Social R2/CDN round trip; deletes only new objects created by this test. */
@EnabledIfEnvironmentVariable(named="CONTENT_R2_TEST_ENABLED",matches="true")
class ContentR2IntegrationTest {
    @Test void realImageAudioAndVideoAreUploadedReadableAndVideoSupportsByteRanges() throws Exception {
        var env=System.getenv();
        var properties=new ContentMediaProperties(true,env.get("GATEWAY_CONTENT_R2_ENDPOINT"),env.get("GATEWAY_CONTENT_R2_ACCESS_KEY_ID"),
                env.get("GATEWAY_CONTENT_R2_SECRET_ACCESS_KEY"),"app-public","https://cdn.surprising.top");
        var storage=new ContentR2Storage(properties);
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            for(var file:Map.of("image.png","image/png","audio.wav","audio/wav","video.mp4","video/mp4").entrySet()) {
                Path path=Path.of(env.get("CONTENT_R2_TEST_MEDIA_DIR"),file.getKey());
                byte[] expected=Files.readAllBytes(path);
                String key=ContentR2Storage.PREFIX+UUID.randomUUID()+file.getKey().substring(file.getKey().lastIndexOf('.'));
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
