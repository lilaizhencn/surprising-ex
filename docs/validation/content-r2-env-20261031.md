# R2 敏感凭据迁回仓库外 ENV（2026-10-31）

按用户对公开仓库的要求，真实 R2 凭据保存在 `~/.config/surprising-ex/content-r2.env`，权限 `0600`，运行时注入。已移除本地含密钥的 `surprising-gateway/config/application.yaml` 及其显式导入；可提交 YAML 只保留环境变量引用和非敏感默认值。桶和公共域名仍直接配置在 YAML。`AGENTS.md` 保留普通配置默认 YAML 的约定，并明确 R2 敏感凭据是当前的环境变量例外。

此前本地 YAML 被 Git 忽略。拉取 origin 后，对本地全部 93,544 个 Git 对象（含可达和不可达对象）扫描这组 R2 访问凭据，匹配为零；新提交内容也单独检查。该检查只针对本次 R2 凭据，不等同于全仓库通用密钥审计。

验证使用 HotSpot JDK 27 / Maven 3.9.16。测试进程从仓库外文件注入环境变量，再用实际 Spring ConfigData 和 Binder 加载应用配置：

```bash
mvn -o -B -ntp -pl surprising-gateway \
  -Dtest=ArticleRenderingServiceTest,ContentMediaServiceTest,MediaFileTypeTest,RichArticleTest,ContentR2IntegrationTest \
  -Dcontent.r2.test.enabled=true \
  -Dcontent.r2.test.media-dir=/临时测试媒体目录 test

mvn -o -B -ntp -pl surprising-gateway -DskipTests package
```

13 项通过，零失败、错误或跳过，包含真实 PNG/WAV/MP4 上传、CDN 逐字节读取、视频 Range 206 和删除。打包通过，检查可执行 JAR 未发现这组凭据。测试仅使用合成媒体；三项对象及对应 URL 的 CDN 缓存已清理，读取均为 404。证据见 `content-r2-env-20261031/`。

实际值未进入可提交文件、验证日志或 JAR。真实凭据文件留在仓库外，不随本次提交推送。未部署或重启生产应用，未改变 HTTP 契约或客户端交互。
