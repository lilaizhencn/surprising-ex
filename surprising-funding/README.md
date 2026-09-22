# surprising-funding

此目录只保留 `surprising-funding-api`，供业务网关等模块使用。
资金费计算、结算调度、查询和管理接口的实现已迁入
`surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider`，保留 `com.surprising.funding.provider` 业务包。
不再构建或启动独立 funding provider；接口路径 `/api/v1/funding` 不变，默认端口统一为 9087。
仅 LINEAR_PERPETUAL / INVERSE_PERPETUAL 加载资金费组件。
详见 [衍生品后台说明](../surprising-derivatives-lifecycle/README.md)。
