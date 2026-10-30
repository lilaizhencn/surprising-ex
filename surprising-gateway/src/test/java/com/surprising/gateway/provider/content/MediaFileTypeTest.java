package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.content.support.MediaFileType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MediaFileTypeTest {
    @Test void rejectsForgedTypesAndBoundsEachMediaKind() {
        assertThat(MediaFileType.limit("image/png")).isEqualTo(20L*1024*1024);
        assertThat(MediaFileType.limit("audio/mp4")).isEqualTo(32L*1024*1024);
        assertThat(MediaFileType.limit("video/mp4")).isEqualTo(64L*1024*1024);
        assertThatThrownBy(()->MediaFileType.limit("image/svg+xml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MediaFileType.extension("image/png","<html>evil</html>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThat(MediaFileType.extension("image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0,0})).isEqualTo("png");
    }
}
