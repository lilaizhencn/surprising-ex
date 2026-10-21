package com.surprising.gateway.c2c.model;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** 事件元信息有明确类型，details保留该事件的JSON文档，不反向作为业务命令。 */
public record C2cOrderEvent(long id, Long actorUserId, String type, JsonNode details, Instant createdAt) { }
