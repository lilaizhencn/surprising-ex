# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Surprising 交易所后端服务。

这个仓库是交易所后端模块的根聚合工程。每个业务模块维护自己的详细 README 和部署说明。

## 模块

- `surprising-dependencies`：从 `surprising-wallet` 复制过来的统一依赖版本管理模块。
- `surprising-parent`：从 `surprising-wallet` 复制过来的公共父 POM。
- `surprising-candlestick`：合约 K 线服务。

## 模块文档

- [surprising-candlestick](surprising-candlestick/README_CN.md)

## 构建

```bash
mvn test
```
