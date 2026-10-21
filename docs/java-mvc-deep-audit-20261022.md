# Java MVC 深度复扫（2026-10-22）

本次复扫覆盖当前全部 Java 源码，生产、测试、基准及核心状态代码均进入清单；排除 target/build 等产物。原始248项关闭与分批测试通过，均不代表整个项目已符合规范。

## 已有证据

- 解析2128个Java文件，其中1495个生产文件，0语法错误；文件路径、包、角色、扫描结果及SHA见 `audit/java-source-rescan-20261022.csv`。
- Service直接HTTP引用、Controller直接持有Repository、Scheduled未归task、KafkaListener未归consumer，这四项结构检查为0。
- Repository方法级SQL提取505行，以init.sql/迁移的130个已知表/视图过滤，未发现字面量SQL中一个Repository写多张真实表；多表读取已有类或方法注释说明用途。动态SQL、常量组合及注释具体充分性不能只靠此提取认定。
- Gateway最新干净构建1476项，1456通过、20项环境条件跳过；25项独立PostgreSQL提现对账已实际运行。外部R2两个类及多产品Gateway实栈类另行排除，均未启动wallet。

## 仍确认存在的问题

逐条位置见 `audit/java-mvc-additional-findings-20261022.csv` 的ADD-063..092（待整改）：

1. 19个Repository仍解析HTTP分页、排序或游标。包括认证/工单/合规、账户流水/划转/调账、交易费率、保险/ADL及做市事件，不能只修Trade/Trigger历史查询。
2. RiskService仍解析清算候选分页及输入格式；产品、合约、资金和风险业务不变量须保持Service。
3. GatewayProductServices向原生资金调用方传播ResponseStatusException；需原生产品不可用失败并检查全部HTTP入口。
4. 认证/APIKey管理、资产/网络配置、C2C版本/收款资料、账户标识、活动订单/条件单游标、汇率输入等仍有请求格式处理在Service。

## 需要继续审查的候选

公开嵌套模型/异常以及工具/协议组件应逐个核对跨层使用。扫描器只给候选，不能把同属Core包前缀的actor状态、快照或协议类型直接搬到MVC model，也不能将持久化事实解码、配置完整性、密码/签名验证或风险规则误移Controller。需要基于实际调用方判定。

## 可复现命令

使用HotSpot JDK27，在项目根目录执行：

```sh
java tools/mvc/MvcSourceScan.java "$PWD" > /tmp/java-mvc-sources.csv
java tools/mvc/RepositoryMethodScan.java "$PWD" > /tmp/java-mvc-repository-methods.csv
```

两条命令均为只读解析；目前是审计工具，尚未把待整改规则包装成虚假的通过门禁。最终全范围类型检查/回归、真实六产品线交易/做市/资金验收及架构门禁仍未完成。
