/**
 * Aeron Cluster 的输入控制层：接收消息、会话、角色和快照回调，校验协议并交接到 Owner。
 * 网络会话和 Publication 留在 Cluster 线程，业务状态由 service 中的实际拥有者修改。
 */
package com.surprising.aeron.controller;
