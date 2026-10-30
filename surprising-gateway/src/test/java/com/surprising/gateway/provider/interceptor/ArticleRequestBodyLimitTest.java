package com.surprising.gateway.provider.interceptor;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ArticleRequestBodyLimitTest {
    @Test void onlyArticleDraftSavesReceiveTheLargerUtf8BodyLimit() throws Exception {
        for(String path:new String[]{"/api/v1/admin/announcements","/api/v1/admin/announcements/42","/api/v1/admin/legal/revisions","/api/v1/admin/legal/revisions/42"}) {
            assertThat(accepted(path,"POST",2*1024*1024)).isTrue();
            assertThat(accepted(path,"PUT",2*1024*1024)).isTrue();
            assertThat(accepted(path,"POST",3*1024*1024+1)).isFalse();
        }
        for(String path:new String[]{"/api/v1/gateway/trading/place","/api/v1/admin/legal/revisions/42/publish","/api/v1/admin/announcements/42/publish","/api/v1/admin/announcements-extra","/api/v1/auth/login"}) {
            assertThat(accepted(path,"POST",1024*1024+1)).as(path).isFalse();
        }
    }
    private boolean accepted(String path,String method,int size) throws Exception {
        var request=new MockHttpServletRequest(method,path);request.setContentType("application/json");request.setContent(new byte[size]);
        var response=new MockHttpServletResponse();var invoked=new AtomicBoolean();
        new ProductRequestBodyFilter().doFilter(request,response,(wrapped,reply)->{assertThat(wrapped.getInputStream().readAllBytes()).hasSize(size);invoked.set(true);});
        if(!invoked.get())assertThat(response.getStatus()).isEqualTo(413);
        return invoked.get();
    }
}
