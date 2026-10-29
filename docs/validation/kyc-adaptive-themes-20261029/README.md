# KYC 闭环、折叠屏、液态玻璃与主题验证

本轮修改涉及 `surprising-ex`、`surprising-client`、`surprising-ex-web`、`surprising-admin-web`。验证使用隔离 PostgreSQL 数据库、合成身份和 1×1 PNG；没有使用真实身份材料、生产提供商账户或生产资金。

## 最终行为

- 服务端公布实际可用的申请人类型与认证等级；Sumsub 每条流程单独配置，Veriff 只公布集成实际支持的个人等级，未配置的流程不允许提交。Sumsub 个人与企业申请人分开，刷新会话保留原流程。
- 首次提交也锁定用户认证行，防止并发创建多个会话。回执校验签名、当前会话、申请人、流程和事件时间，重复与旧事件不回退状态；人工审核有独立时间水位，服务商之后的新拒绝仍可撤销资格。
- 已认证结果有配置有效期上限，不能超过取得的证件期限。过期后可重新申请；交易相关资格读取继续检查有效期。已在审核中或仍有效的认证不能重复提交。
- 人工通过需要本次已提交且归属正确的完整材料、未来到期时间；不能凭空创建第三方认证、改写服务商/会话或提高第三方等级。驳回必须提供补正原因。原件读取审计、后台确认和审批流程保留。
- App 与 Web 完成提交、待审、驳回原因、补正重提、通过、到期与重认证；配置/加载/文件/会话刷新失败可见且可重试。App 后台暂停轮询、回前台刷新，同一账户正常令牌续期保留表单，退出或切换账户隔离旧请求。
- App 按窗口尺寸切换底部/侧边导航，折叠时避让真实铰链，交易界面可分区显示图表和表单；宽屏认证表单限宽，窗口变化保留输入控制器。六套主题分别采用黑金、冷蓝、深海青、森林绿、暮紫、暖棕，主色与背景层次不同，涨跌语义保持一致。
- iOS 26+ 导航使用 UIKit `UIGlassEffect`，旧系统使用系统材质；Android 使用有边界的模糊材质。高对比度、减少透明度及减少动态效果按系统设置处理。

## 验证范围与入口

后端使用 HotSpot JDK 27、Maven 3.9.16；数据库为本机专用 `surprising_kyc_adaptive_qa_20261029`。`KycClosureBrowserQaTest` 启动真实 MVC 控制器、事务、SQL 与文件存储，认证、扫描、限流、邮件和浏览器联调的审批校验边界使用明确夹具；另有 `ComplianceWritePostgresTest` 覆盖真实审批消耗与业务事务。`/__qa/*` 仅存在于测试源集，不进入产品代码。

首次带联调夹具的回归中，C2C 的 4 项用例因空库缺少 `public.assets` 等模板表在 setup 阶段失败，其余 83 项通过。随后在专用库执行完整 `init.sql`，同时补齐新增会话字段的中文数据库注释，使仓库 schema 文档门禁通过，并将 KYC 迁移连续执行两次验证可重复应用。最终回归另存 `backend-regression-release.log`，不把先前失败的环境检查当作通过。

| 检查 | 证据 |
|---|---|
| 后端状态机、数据库并发/回滚、通知事务、材料、提现/C2C 门禁、提供商 HTTP 契约 | `backend-regression-final.log`、`backend-regression-release.log` |
| Flutter 静态分析与相关 Widget/API 测试 | `flutter-analyze.log`、`flutter-unit-final.log`、`flutter-refresh-test.log` |
| Web 类型/构建及 KYC/i18n 测试 | `web-typecheck.log`、`web-build.log`、`web-kyc-test.log` |
| Web 九组交互场景，1440/390/320、深浅主题、60 秒观察 | `web-fixture/result.json` |
| 用户 Web→真实上传/提交→后台原件查看/审核→补正/通过/到期重提 | `web-live/result.json` 及同目录截图 |
| 管理端类型检查、测试与构建 | `admin-lint.log`、`admin-test.log`、`admin-build.log` |
| iPhone、iPad、Pixel Fold 原生点击、截图与运行采样 | 各设备目录的 `device.log`、`result.json` 与 PNG |

最终服务端回归为 **88 项通过，0 failure、0 error、0 skip**；Flutter 相关测试 **58 项通过**，最终静态分析无诊断；用户 Web KYC/i18n **4 项通过**，管理端全量 **146 项通过**，两套 Web 的类型检查与构建通过。用户 Web 全仓 lint 的既存阻断见下文。

Pixel Fold（Android 16/API 36）、iPhone 17 Pro 与 iPad Pro 13（iOS/iPadOS 26.5）各完成主题/导航与真实 HTTP KYC 两个主体场景；完整流程日志在 `android-fold`、`ios-phone`、`ios-tablet`。Android 的实际窗口为折叠约 411×797、展开约 841×701，半折叠返回实际 fold feature；切换后价格 `12345.6` 和同一输入控制器保留。三端均包含 60 秒待审轮询观察。

另用 `--api ''` 单独重复 iOS 界面场景，补上键盘就绪与系统合成器稳定后的采图；最终静态布局截图以 `ios-phone-ui`、`ios-tablet-ui` 为准。这两次 UI 复验有意跳过 KYC，完整 KYC 通过证据保留在上述完整流程目录。转场早期的原始截图不用于判定最终静态布局。

原生验收脚本：`surprising-client/tool/native_kyc_acceptance.py`，调用 `integration_test/kyc_adaptive_themes_test.dart`。截图来自系统合成器，包含 UIKit 原生玻璃。布局和主题场景使用离线行情夹具；KYC 场景通过真实本机 HTTP 接口及数据库，仅以合成 PNG 替换操作系统文件选择器，不把该替换宣称为相机/文件权限验收。Android 使用模拟器真实 `device_state` 的 CLOSED、OPENED、HALF_OPENED 切换，并检查同一交易输入控制器及文本。

Android 构建使用本机 Microsoft HotSpot JDK 21.0.10；先前使用 JDK 27 的 Android `jlink` 构建失败已经保存在 `android-jdk27-build.log`，后端不受该工具链选择影响。Android 自动化关闭启动时的 Dart isolate 暂停，避免调试器暂停导致首次焦点超时；姿态变更后先唤醒/解锁并等待 Flutter 完成布局，再按处于 ON 状态的物理显示器采图，不将锁屏、无响应弹窗或旧布局作为通过证据。

## 已知限制

用户 Web 全仓 Biome 检查仍被既存问题阻塞：同一目录配置下，HEAD 基线 93 errors，本轮后 90 errors；修改的 KYC 文件零新增诊断，单独检查通过。见 `web-lint-summary.json`、基线与最终日志。没有添加 lint 忽略或顺带重构其他业务。

本机闭环与 HTTP 契约测试不能替代 Sumsub/Veriff 商户开通后的真人证件、活体、人脸、企业材料、SDK 权限和公网回调验收。未测试实体折叠屏硬件、旧版 iOS 系统材质及长期运行稳定性；60 秒轮询及导航循环只代表本轮观察窗口。没有部署生产、启动钱包或提交真实交易。

## 复现命令

先对空白专用库执行 `psql -v ON_ERROR_STOP=1 -d DATABASE -f init.sql`。在后端设置 `LOGIN_TEST_JDBC_URL`、`LOGIN_TEST_DB_USER`、`LOGIN_TEST_DB_PASSWORD`，以及指向同一专用测试库的 `INSTRUMENT_TEST_*`；切勿指向生产。回归入口：

```sh
mvn -pl surprising-gateway -am -Dtest=ComplianceServiceTest,ExternalKycProviderTest,KycProviderHttpContractTest,KycApplicationValidationTest,KycEvidenceReferenceTest,KycProviderConcurrencyPostgresTest,KycProviderDatabaseTest,ComplianceWritePostgresTest,KycNotificationTransactionPostgresTest,GatewayProductionSecurityConfigurationTest,KycDocumentServiceTest,KycDocumentStorageServiceTest,CustodyWalletControllerTest,C2cAccessPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
```

浏览器/原生联调另外设置 `KYC_QA_DIR`，将 `KycClosureBrowserQaTest` 加入上述 `-Dtest` 并指定 `-Dsurefire.runOrder=reversealphabetical`。夹具生成 `ready.json` 后，可在用户 Web 的 5183、管理端的 5184 开发服务上运行 `node scripts/kyc-closure-live-qa.mjs`（按脚本设置 Playwright/Chromium 路径）。完成后创建 `$KYC_QA_DIR/qa-done` 退出夹具并清理其 schema。

客户端验证：

```sh
flutter analyze
flutter test --no-pub test/adaptive_client_layout_test.dart test/client_theme_test.dart test/mobile_account_parity_test.dart test/input_boundary_widgets_test.dart test/account_display_test.dart test/passwordless_security_test.dart test/api_key_edit_dialog_test.dart
python3 tool/native_kyc_acceptance.py --device DEVICE_ID --api http://127.0.0.1:19086 --output OUTPUT_DIRECTORY
```

Android 使用 JDK 21，模拟器访问地址为 `http://10.0.2.2:19086`；完整 Android 场景要求支持 0/1/2 三种姿态的折叠屏模拟器。`--kyc-only` 可单独重复认证链路。Web 执行 `npm run typecheck`、`npm run build`、KYC/i18n Vitest 和修改文件的 Biome 检查；管理端执行 `npm run lint && npm test && npm run build`。

## 部署与提供商资料

已有库先应用 `deployment/migrations/20261029-kyc-session-order.sql`。核对新增有效期、Sumsub 各流程名称及 Veriff 集成等级配置；尚有待审会话时不要修改流程名称映射。初始化 SQL 已同步。

协议实现参考官方资料：[Sumsub 外部用户查询](https://docs.sumsub.com/reference/get-applicant-data-via-externaluserid)、[重置申请人](https://docs.sumsub.com/reference/reset-applicant)、[变更流程](https://docs.sumsub.com/reference/change-level-and-reset-steps)、[审核回执](https://docs.sumsub.com/docs/user-verification-webhooks)、[Veriff 创建会话](https://devdocs.veriff.com/apidocs/v1sessions)、[Veriff 决策回执](https://devdocs.veriff.com/docs/decision-webhook)、[Apple UIGlassEffect](https://developer.apple.com/documentation/uikit/uiglasseffect)。商户流程的证件、人脸和地址核验要求仍须在提供商控制台配置并验证。
