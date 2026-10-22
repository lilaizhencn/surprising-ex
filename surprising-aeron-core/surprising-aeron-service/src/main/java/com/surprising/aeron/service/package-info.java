/**
 * 按订单、撮合、账户、风控、强平等业务组织的交易服务。
 * 各服务由产品线 Owner 的运行时装配，遵守 Owner、Matcher、Account Lane、Ledger 的状态所有权；
 * 包内的可变业务对象不会因采用分层目录就成为全局 Spring 单例。
 * 协议输入、配置和线程启动分别从 controller、config 和 bootstrap 进入。
 */
package com.surprising.aeron.service;
