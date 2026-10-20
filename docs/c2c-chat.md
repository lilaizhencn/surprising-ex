# C2C 聊天整合

## 来源与产品边界

功能以 surprising-social `ed8d3915552e88ba989f2e53310f9b56e44a8708` 的现有实现为参考并迁用业务流程：

- `ChatMsgHandler` / `ChatService`：提交后确认、丢确认包后的持久记录查询与原请求重发。
- `SyncService` / `ReadReceiptService`：按游标补拉、真实未读与单调已读水位。
- `R2Config` / `PresignedUrlService`：R2 S3 客户端配置、服务端签发临时上传凭证。

交易实现适配已有 JDBC、JSON WebSocket 和交易登录，不依赖 social 的 Maven 模块、登录、好友接口、设备会话、Redis Stream 或运行进程。social 源码、配置和数据不因本次整合改变。

订单会话由买卖双方和原订单唯一确定。咨询会话遵守陌生消息与双向拉黑设置；在途订单仍可沟通履约。会话和消息正文不能改变参与者或原文，不迁入编辑、撤回、阅后即焚和社交历史清理。订单事件继续以 `c2c_order_events` 为权威来源。

## 后端入口与权限

所有入口位于 `surprising-gateway` 的 `com.surprising.gateway.c2c` 包。部署开关 `C2C_ENABLED` 默认关闭。

1. `C2cConversations` 按订单校验双方，或按咨询设置建立固定会话。
2. `C2cMessages` 从交易登录身份取发送者，锁定会话后持久化。相同请求 UUID 和相同内容返回原消息；换内容或换身份不能占用旧请求号。GET 查询 UNKNOWN 不发送消息。
3. HTTP 通过现有 `AuthService.authenticateBearer` 鉴权；会话成员权限覆盖历史、已读、消息和附件。客户端输入的身份及客服标识均不能授权。
4. `C2cAdminChat` 只能从订单进入聊天与证据；敏感读取、客服回复必须通过交易后台权限并成功写入审计。客服回复由服务端标记 `SUPPORT`，本人交易不能伪装客服。
5. `C2cAttachments` 固定附件的拥有者、会话/订单、用途、类型、字节数和 SHA-256。保存并校验真实字节后才能 READY。付款确认和收款码配置只接受 READY 文件。

| HTTP | 入口 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/c2c/conversations` | 以 `orderId` 或 `targetUserId` 建立会话 |
| GET | `/api/v1/c2c/conversations` | 本人会话、搜索、最后消息和真实未读 |
| GET/POST | `/api/v1/c2c/conversations/{id}/messages` | 游标历史或原请求 UUID 发消息 |
| GET | `/api/v1/c2c/conversations/{id}/requests/{requestId}` | 只读恢复原请求 |
| POST | `/api/v1/c2c/conversations/{id}/read` | 单调推进 `messageId` 和 `eventId` |
| POST | `/api/v1/c2c/attachments/{id}` | 经过 Gateway 上传并核验 |
| POST | `/api/v1/c2c/attachments/{id}/presigned-put` | 固定上传身份、签发 R2 直传凭证 |
| POST | `/api/v1/c2c/attachments/{id}/confirm` | 核验 R2 实际内容后置 READY |
| GET | `/api/v1/c2c/attachments/{id}` | 本人上传结果；UNKNOWN 不创建上传 |
| GET | `/api/v1/c2c/attachments/{id}/content` | 本人或会话/订单双方鉴权后读取 |
| GET/POST | `/api/v1/admin/c2c/orders/{order}/chat` | 审计后的历史读取或客服回复 |
| GET | `/api/v1/admin/c2c/orders/{order}/chat/attachments/{id}` | 审计后的本订单证据读取 |

JSON 的用户 ID、消息 ID、事件 ID、已读版本均为十进制字符串。消息正文上限 4000 字；文件限 JPG/PNG/PDF、10MiB，收款码只接受图片。图片还校验魔数和像素尺寸。PDF 以下载方式返回；所有文件响应均 `no-store`、`nosniff`，不返回对象密钥或公共资源 URL。

## 与行情共用 WebSocket

聊天共用现有 `/ws/v1` 连接、认证、心跳、有界发送队列和重连，不注册第二个聊天 WebSocket 地址。

```json
{"op":"authenticate","token":"<交易 access token>"}
{"op":"c2c.inbox"}
{"op":"c2c.watch","conversationId":"<uuid>","after":"0","afterEvent":"0"}
{"op":"ping","id":"heartbeat"}
{"op":"c2c.unwatch"}
{"op":"c2c.stop"}
```

`c2c.inbox` 接收 `channel=c2c.inbox` 的变化通知，客户端重新查询会话列表。`c2c.watch` 订阅当前会话，收到 `channel=c2c.messages` 的消息、订单事件和已读水位。关闭会话时 unwatch，释放聊天订阅时 stop；它们均不取消行情订阅。

`WebSocketJwtAuthenticator` 统一调用交易 `AuthService`。身份头和 URL 用户 ID 不再作为认证依据；客户端必须先发 authenticate。聊天订阅持续验证登录会话，失效后关闭连接。

`C2cChatRealtime` 每次最多从已提交 SQL 读取 20 条，使用单独的一个调度线程；节点只保存当前聊天订阅及通知/补拉水位，不复制消息正文或资金状态。行情队列已有积压时暂缓聊天且不推进游标，下一轮继续原位置。跨节点或进程重启后依靠相同数据库和客户端原游标恢复，不承诺通过临时内存队列保存消息。

## R2 配置与密钥

配置模板为 `deployment/c2c-r2.env.example`。实际配置位于仓库外的 `/Users/atomex/.config/surprising-ex/c2c-r2.env`，目录权限 700、文件权限 600。运行时导入环境变量后由 `surprising.c2c.r2` 绑定；没有实际凭据时默认关闭，不伪造上传成功。

本次按用户授权从本地 Nacos 导出复制 endpoint、account ID、Access Key 和 Secret Key。它们没有写入源代码、提交、前端或测试报告。测试服务器发布仍按此前要求延后，受保护的本地配置不代表服务器已发布。

交易文件使用私有存储范围 `private/trading/c2c/`。服务端决定桶和路径，客户端只能操作已授权上传 ID；不接受任意桶名或对象路径。R2 上传签名附带 `If-None-Match: *` 防止覆盖原对象，确认步骤读取实际内容核对 SHA-256、大小和格式，下载始终经过交易权限。

R2 的条件写入说明见 [Cloudflare S3 API](https://developers.cloudflare.com/r2/api/s3/api/)。签名地址使用原始 S3 API 域名，不能替换成自定义 CDN 域名，见 [Cloudflare 预签名 URL 文档](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)。浏览器直传还需要桶的 CORS 允许相应来源和签名头；不能因 C2C 接入而覆盖 social 的桶配置。

新增迁移 `deployment/migrations/20261020-c2c-chat.sql` 已同步 `init.sql`。验证覆盖和当前未交付的前端范围应以本轮验证记录为准；未验收项不宣称完成。

本轮实际 R2 SDK 和 Gateway MVC 链路均已验证，测试对象已清理。现有桶对本轮浏览器来源的直传预检为 403，且凭据无权读取桶 CORS；Web 采用已有的 Gateway multipart 上传入口写入 R2，保持 social 桶配置原样。原生客户端可使用已验证的临时凭证直传。详细用例与范围见 [后端验证记录](validation/c2c-chat-backend-20261020.json)。
