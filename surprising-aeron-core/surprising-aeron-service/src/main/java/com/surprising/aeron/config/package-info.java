/**
 * 启动步骤 2：把外部配置变成节点拓扑和线程亲和性配置，此时不启动交易线程。
 *
 * <p>从 {@link com.surprising.aeron.config.CoreConfiguration} 阅读 Spring 绑定项；
 * Aeron 资源启动继续到 {@link com.surprising.aeron.bootstrap.AeronCoreLifecycle}，
 * Owner 恢复和工作线程激活继续到 {@link com.surprising.aeron.bootstrap.TradingOwnerLoop}。
 * Lane 拓扑通过 YAML 与 Spring Environment 统一绑定；执行模式和等待策略等 JVM 参数的
 * 具体来源见 Aeron 总 README 的配置表。配置转换调用普通工厂方法，不直接调用其他 Bean 方法。</p>
 */
package com.surprising.aeron.config;
