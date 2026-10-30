package com.surprising.gateway.provider.content;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.content.support.RichArticle;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RichArticleTest {
    private final String id="12345678-1234-1234-1234-123456789abc";
    @Test void stripsActiveContentAndDerivesTextInsteadOfTrustingClientProjection() {
        var article=RichArticle.clean("<h2 onclick='alert(1)'>Heading</h2><p style='color:red'>Text <strong>bold</strong></p>"
                +"<script>alert(1)</script><iframe src='https://bad.test'></iframe>"
                +"<a href='javascript:alert(1)'>bad</a><a href='https://example.com'>safe</a>");
        assertThat(article.html()).contains("<h2>Heading</h2>","<strong>bold</strong>","href=\"https://example.com\"","noopener");
        assertThat(article.html()).doesNotContain("onclick","style=","script","iframe","javascript","alert(");
        assertThat(article.text()).isEqualTo("Heading Text bold badsafe");
    }
    @Test void acceptsOnlyManagedMediaAndDisablesAutoplayAndRemoteTracking() {
        var article=RichArticle.clean("<img src='"+RichArticle.MEDIA_PATH+id+"' onerror='bad()'><video src='"
                +RichArticle.MEDIA_PATH+id+"' autoplay loop poster='https://bad.test/track'></video>");
        assertThat(article.mediaIds()).containsExactly(UUID.fromString(id));
        assertThat(article.html()).contains("loading=\"lazy\"","controls","preload=\"metadata\"","playsinline");
        assertThat(article.html()).doesNotContain("onerror","autoplay","loop","poster");
        for (String source:new String[]{"https://cdn.surprising.top/private/secret", "data:image/png;base64,a", "//bad.test/a", RichArticle.MEDIA_PATH+id+"?redirect=bad"})
            assertThatThrownBy(()->RichArticle.clean("<img src='"+source+"'>")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundsMarkupTextAndMediaCount() {
        for (String html:new String[]{" ","<script>bad()</script>","a".repeat(12001),"<p>"+"a".repeat(64000)+"</p>",
                ("<img src='"+RichArticle.MEDIA_PATH+id+"'>").repeat(25)})
            assertThatThrownBy(()->RichArticle.clean(html)).isInstanceOf(IllegalArgumentException.class);
    }
}
