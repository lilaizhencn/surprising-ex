package com.surprising.instrument.api.cache;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.product.api.ProductLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一处理各业务模块的合约快照初始化和增量事件校验。
 */
public final class InstrumentSnapshotSupport {

    private InstrumentSnapshotSupport() {
    }

    /**
     * 通过 Instrument 聚合 RPC 加载指定产品线的完整快照。
     */
    public static void initialize(InstrumentRpcApi instrumentRpcApi,
                                  InstrumentSnapshotCache snapshotCache,
                                  ProductLine productLine,
                                  String serviceName) {
        InstrumentSnapshotResponse snapshot = instrumentRpcApi.snapshot(productLine);
        if (snapshot == null || snapshot.productLine() != productLine) {
            throw new IllegalStateException(serviceName + "合约快照产品线不匹配: " + productLine);
        }
        snapshotCache.replace(productLine, snapshot.instruments(), snapshot.assetScales());
        if (!snapshotCache.ready(productLine)) {
            throw new IllegalStateException(serviceName + "合约快照为空，拒绝启动: " + productLine);
        }
    }

    /**
     * 校验并应用 Instrument 增量事件。
     */
    public static void apply(InstrumentSnapshotCache snapshotCache,
                             String recordKey,
                             InstrumentEvent event,
                             ProductLine productLine,
                             String serviceName) {
        if (!InstrumentEventKeys.matches(recordKey, event)
                || event.productLine() != productLine
                || !snapshotCache.apply(event)) {
            throw new IllegalArgumentException(serviceName + "合约事件产品线、key 或快照不匹配");
        }
    }

    /**
     * 统一解析并应用 Instrument Kafka 增量事件。
     *
     * <p>业务模块只保留 Kafka 监听器、消费组和自身刷新动作；事件解析、产品线校验、key 校验
     * 以及错误包装全部由这里完成。</p>
     */
    public static InstrumentEvent consume(ObjectMapper objectMapper,
                                          ConsumerRecord<String, String> record,
                                          InstrumentSnapshotCache snapshotCache,
                                          ProductLine productLine,
                                          String serviceName) {
        try {
            InstrumentEvent event = decode(objectMapper, record);
            apply(snapshotCache, record.key(), event, productLine, serviceName);
            return event;
        } catch (Exception ex) {
            throw new IllegalStateException(serviceName + "合约快照更新失败", ex);
        }
    }

    /**
     * 处理需要同时缓存多个产品线的服务，例如做市服务。
     * 事件中的产品线仍会参与 key 校验，不允许从快照内容推导或省略。
     */
    public static InstrumentEvent consumeAnyProductLine(ObjectMapper objectMapper,
                                                         ConsumerRecord<String, String> record,
                                                         InstrumentSnapshotCache snapshotCache,
                                                         String serviceName) {
        try {
            InstrumentEvent event = decode(objectMapper, record);
            apply(snapshotCache, record.key(), event, event.productLine(), serviceName);
            return event;
        } catch (Exception ex) {
            throw new IllegalStateException(serviceName + "合约快照更新失败", ex);
        }
    }

    private static InstrumentEvent decode(ObjectMapper objectMapper,
                                         ConsumerRecord<String, String> record) throws Exception {
        return objectMapper.readValue(record.value(), InstrumentEvent.class);
    }
}
