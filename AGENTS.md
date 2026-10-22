# 开发规范

资金正确性优先。**性能优化、压测或 JMH/JFR 前，必须读取子规范 [AGENTS-performance.md](AGENTS-performance.md)。**

## MVC 分层

- `controller`：请求/消息入口、参数绑定、响应转换；不写业务或 SQL。
- `service`：业务校验、流程、事务；按 `order`、`matcher`、`account`、`risk` 等业务分包。
- `repository`：数据库访问；`client`：外部调用。
- `config`：YAML 配置绑定、校验、Bean 装配；`bootstrap`：Aeron 初始化、线程启停、资源释放。
- `model/dto/event`：数据与契约；`support/utils`：必要的共享能力；`*-api`：共享模型、契约和纯公式。
- 入口 → service → repository/client，禁止反向或循环依赖；共享 API 不依赖应用实现，框架适配依赖按需设为 `optional`。

## 编码约束

- 面向对象、职责单一；类、变量、方法使用直白的业务用语。主流程顺着读，不过度设计，不机械拆类、加接口或层层包装传参。
- 构造器注入、`final` 依赖；配置统一绑定，不散落读取环境变量。`proxyBeanMethods=false` 时不直接调用 `@Bean` 方法。
- Lombok 只简化样板；校验、复制、状态转换显式实现，可变交易对象不用 `@Data`。区分业务拒绝和系统故障，不吞异常，不记录敏感信息。
- 六产品线隔离；状态只有一个权威来源，保持 Owner/Lane 所有权。保留整数单位、舍入、溢出、冻结、幂等、事务、事件顺序和恢复语义。
- 纯公式不读数据库、容器、时钟或 Core 状态。热路径不新增无意义阶段、全表扫描、临时集合或状态副本。
- 不新增 fallback、legacy 或重复兼容层；默认不启动 wallet，不修改其他项目或覆盖其他任务改动。

## 验证与提交

- 使用 HotSpot JDK 27；执行前检查 Java/Maven 版本和磁盘。结构探索优先 CodeGraph，字面量用 `rg`。
- 按依赖和业务影响测试；公共契约/根 POM 扩大验证，迁移/删除类用干净构建。交易改动核对做市、资金、持仓、终态和恢复。
- 同步中文 README；交付列明命令、结果、跳过项及未测范围，不以未验证内容宣称通过或性能提升。
- 归档摘要后清理本轮临时资源。仅在 `master` 开发，验证通过后 commit 并 push，不改写已推送历史。
