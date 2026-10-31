# 公告媒体上传凭据改为 YAML 直配（2026-10-31）

后续按用户要求，R2 敏感凭据已迁回仓库外的 `.env`；当前方式见 [环境变量凭据验证](content-r2-env-20261031.md)。本页保留当时本地 YAML 方案的验证记录，真实密钥始终未提交。

- 实际凭据已写入 `surprising-gateway/config/application.yaml`，包含启用状态、endpoint、access-key-id、secret-access-key、bucket 和 public-base-url，所有值均直接配置，没有环境变量占位符。文件权限 `0600`，已被 Git 忽略。
- 主 `application.yml` 保留可提交的非敏感默认值，并从仓库工作目录导入该本地文件；从 Gateway 模块目录启动时由 Spring Boot 默认的 `config/application.yaml` 位置加载。对应机制见 [Spring Boot 外部配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)。
- 使用 Spring ConfigData 和配置绑定检查器，移除环境变量配置源后，分别从仓库目录和 Gateway 模块目录运行，均成功绑定实际 YAML 的凭据与桶配置。检查器不启动业务服务、数据库、Core 或 wallet，也不输出密钥值。
- `AGENTS.md` 增加默认使用 YAML、一般不使用环境变量的约束；密钥使用本地忽略文件，禁止提交远端。旧 `.env` 示例和本机旧凭据文件已删除，当前示例为 `deployment/content-r2.application.example.yaml`。

## 验证

使用 HotSpot JDK 27 / Maven 3.9.16：

```bash
mvn -o -B -ntp -pl surprising-gateway \
  -Dtest=ArticleRenderingServiceTest,ContentMediaServiceTest,MediaFileTypeTest,RichArticleTest,ContentR2IntegrationTest \
  -Dcontent.r2.test.enabled=true \
  -Dcontent.r2.test.config=/实际仓库路径/surprising-gateway/config/application.yaml \
  -Dcontent.r2.test.media-dir=/本轮临时媒体目录 test

mvn -o -B -ntp -pl surprising-gateway -DskipTests package
```

13 项测试通过，零失败、错误或跳过。真实 R2 测试直接从 YAML 绑定配置，完成 PNG/WAV/MP4 上传、CDN 逐字节读取、视频 Range 206 和删除。测试开关及临时目录是测试命令的系统属性，凭据不通过命令参数或环境变量提供。

打包通过；检查可执行 JAR，未包含本地 `config/application.yaml` 或真实凭据值。三项测试对象已由测试删除，仅清除这三条 URL 的 CDN 缓存，逐项检查为 404。专用桶和实际本地 YAML 凭据保留。证据位于 `content-r2-yaml-20261031/`。

本轮只修改后端配置来源、测试入口和说明，未改变 HTTP 契约或前端交互；未启动或部署生产应用，未重跑资金/撮合及客户端 UI 场景。
