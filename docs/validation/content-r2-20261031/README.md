# Surprising-EX 专用公共媒体 R2（2026-10-31）

## 实际资源

- 桶：`surprising-ex-public`，默认 jurisdiction，APAC，Standard。
- 自定义域名：`https://ex-cdn.tokdou.com`，ownership / SSL 均为 active，最低 TLS 1.2。
- r2.dev 保持关闭。对象路径仍为 `public/exchange/content/<UUID>.<扩展名>`。
- CORS 配置与 `deployment/content-r2.cors.json` 一致：仅交易 Web / API 两个来源的 GET、HEAD，以及 Range / If-None-Match 请求头。
- 已创建用户级专用 Token `surprising-ex-public-gateway`，仅含新桶的对象读写权限；Gateway 的 S3 凭据保存在本机 `~/.config/surprising-ex/content-r2.env`，文件权限 `0600`。配置和证据不包含密钥值。凭据格式及权限模型按 [Cloudflare R2 文档](https://developers.cloudflare.com/r2/api/tokens/) 核对，并已用实际 S3 请求验证。

创建使用已登录的 `cloudflare` CLI，未修改 Social 的桶或公共域名，也未移动其对象。只新增交易媒体桶、自定义域名及桶级凭据；应用生产部署未执行。

## 配套代码

Gateway 默认值、严格桶/域名校验、管理页 CSP、Web 富文本清洗与 CSP、App 阅读器 CSP、接口说明及各端中文 README 均切换到专用配置。继续拒绝共享桶、私有桶及非受管媒体来源，未增加旧 CDN 兼容层。运营主体和联系邮箱仍沿用已核对的 Social 信息。

同时修复实际发现的 Android 提示问题：正常的媒体分段预加载中止，在 WebView 回调中可能表现为通用 `ERR_FAILED`。阅读器改为根据图片的加载状态和音视频的 `MediaError` 报告失败；可信原生观察器不开放文章脚本，CSP 的 `script-src 'none'` 保留。每次加载使用文档版本隔离旧回调。真实 404 仍显示错误，点击重试恢复，旧页面回调不覆盖新页面。

## 验证命令和结果

| 范围 | 命令 / 场景 | 实际结果 |
| --- | --- | --- |
| Gateway | `mvn -o -B -ntp -pl surprising-gateway -Dtest=ArticleRenderingServiceTest,ContentMediaServiceTest,MediaFileTypeTest,RichArticleTest test` | 12 通过，零失败/错误/跳过 |
| 真实 S3 | 注入本机私有配置及 `CONTENT_R2_TEST_ENABLED=true`、`CONTENT_R2_TEST_MEDIA_DIR=<临时媒体目录>`，运行 `ContentR2IntegrationTest` | 1 通过；生产 `ContentR2Storage` 对 PNG/WAV/MP4 实际上传、CDN 逐字节比较、视频 Range 206、删除均通过 |
| HTTPS / CORS | 实际 GET、HEAD、Range、OPTIONS；允许及非允许来源 | 三种文件 200、视频 206、预检 204；非允许来源没有 CORS 授权，未放开 PUT |
| 管理端 | `npm run lint`、`npm run build` | 通过；本轮仅更新说明及浏览器脚本中的 CDN 断言，未重复运行全量后台管理场景 |
| Web | `npm run typecheck`、`npm test`、`npm run build` | 通过；586 项测试通过 |
| Web lint | 修改的 ArticleView 单独 `biome check`；全量 `npm run lint` | 修改文件零诊断；全量仍有 78 个既有错误、零警告，不宣称全仓 lint 通过 |
| App | `flutter analyze`；`flutter test test/legal_content_test.dart test/client_messages_test.dart test/language_picker_test.dart` | analyze 无问题，6 项通过 |
| 浏览器 | 构建产物按真实 `_headers` CSP 提供，实际点击公告后读取新 CDN | Chromium 153，1366×900 亮色、390×900 暗色均通过；图片正常、音视频时钟增长，旧共享域名/私有路径被移除 |
| Android | Android 36 x86_64，1080×2400 / 420 dpi，实际原生媒体控件 | 正常播放、刷新、Home 恢复、真实 404 提示、重试恢复及旧回调隔离均通过 |
| iOS | iOS 26.5 / iPhone 16，实际 WKWebView 媒体控件 | 正常播放、真实 404 提示、重试恢复、旧回调隔离及 Home 恢复均通过 |

后端使用 HotSpot JDK 27 / Maven 3.9.16；Android 构建单独使用 JDK 21。截图、CLI 读回配置、HTTP 结果与关键成功日志位于 `evidence/`。

浏览器公告响应和原生预览入口是受控验证夹具；R2、TLS、S3 客户端、媒体文件及浏览器 / 原生 WebView 是真实的。未重新执行交易、资金或真实第三方登录场景。本轮实际媒体覆盖 PNG、WAV、MP4，不代表全部编码和真机兼容性。

Android 模拟器冷启动曾出现 System UI ANR，恢复后重新验证。另保留了修复前的误报截图、网络取消记录与修复后的成功证据。默认 urllib User-Agent 曾遇到该域既有的 Cloudflare 1010 检查；使用浏览器 User-Agent 的 HTTP 检查、Java 客户端和两端 WebView 均经过实际验证，未为此关闭全域安全设置。

`maestro/` 的坐标对应本轮设备，截图目录用 `-e QA_ARTIFACTS=<临时目录>` 指定。前置条件为临时原生预览入口直接展示含三个真实 R2 URL 的 `ArticleReaderPage`，沿用现有 `integration_test/announcements_preview.dart` 的媒体时钟观察。iOS 测试按钮分别将视频源改为不存在的受管 UUID（真实 404），以及投递前一文档版本的失败消息；Android 对应操作通过已授权的模拟器 CDP 执行。预览入口不进入生产包，测试后删除。重新执行须先准备新测试对象并替换 URL。

## 清理与使用

本轮测试对象及临时环境的清理结果见 `evidence/cleanup.json`。专用桶、域名和受限上传凭据作为交付资源保留。部署时从受限配置注入 Gateway 环境变量，并配套发布已更新 CSP 的两端及管理页配置。
