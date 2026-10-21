package com.surprising.gateway.c2c.model;

import java.util.List;

/** 当前有界页的响应；额外读取的一条只用于判断 hasMore，不返回给调用者。 */
public record C2cPage<T>(List<T> items, boolean hasMore, int offset) {
}
