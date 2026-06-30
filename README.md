# surprising-ex

[English](README.md) | [简体中文](README_CN.md)

Exchange backend services for Surprising.

This repository is the root reactor for exchange backend modules. Each business module keeps its own detailed README and deployment notes.

## Modules

- `surprising-dependencies`: centralized dependency versions copied from `surprising-wallet`.
- `surprising-parent`: shared parent POM copied from `surprising-wallet`.
- `surprising-candlestick`: perpetual candlestick service.

## Module Documentation

- [surprising-candlestick](surprising-candlestick/README.md)

## Build

```bash
mvn test
```
