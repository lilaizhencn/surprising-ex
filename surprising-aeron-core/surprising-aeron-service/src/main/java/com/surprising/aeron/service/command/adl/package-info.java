/**
 * 自动减仓复制命令入口。顺着
 * {@link com.surprising.aeron.service.command.adl.AdlCommands} 的 executeExecuteAdl
 * 进入 state.adl.RuntimeAdlExecution，再按该包的四步导航阅读源强平校验、目标账户减仓、
 * 源亏空扣减和账本完成。
 *
 * <p>异步路径由 DirectCommandSlot 保留续步，Owner 等待全部完成后提交；
 * 本入口不自行扫描选择目标，不把 ADL 嵌入每次强平命令。</p>
 */
package com.surprising.aeron.service.command.adl;
