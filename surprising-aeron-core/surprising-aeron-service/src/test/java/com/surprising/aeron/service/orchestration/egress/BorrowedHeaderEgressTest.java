package com.surprising.aeron.service.orchestration.egress;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.TradingCoreOwner;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 用实际输出环与解码缓存验证响应 Header 和数据的借用期：Owner 归还输入后，回调编码完成前仍须保留响应所需对象。另检查旧会话丢弃、超限隔离及并发关闭请求，网络只用本地代理。
 */
class BorrowedHeaderEgressTest {
    /**
     * 参数分别选择传输句柄请求关闭或从出口移除实际会话；排出时必须不调用发送器，最后连续借还解码器确认原槽可再次使用。本测试 Owner 未启动，不把此断言扩大为运行时响应池回收验证。
     * @param removed 为真就从出口移除会话，为假只标记传输句柄关闭
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closedOrRemovedSessionDropsTheResponseAndReturnsItsLeases(boolean removed) {
        // 实际生产输出环与会话出口，只替换网络发送器
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 当前 ClientSession 代理或工厂附带会话，具体发送由场景控制 */ actual) -> /* 本场景的测试会话发送器，只提供明确指定的发送行为，不创建真实网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 本场景已关闭或移除目标会话，任何实际发送都应立即断言失败。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                throw new AssertionError("closed session must not send a response");
            }
            /**
             * 本夹具没有创建真实 Publication，无额外资源需关闭。
             */
            public void close() { }
        });
        // 提供固定 Leader 角色和时间的测试 Cluster 代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> throw new AssertionError(method.getName());
                });
        // 当前 ClientSession 代理或工厂附带会话，具体发送由场景控制
        var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "id" -> 0L;
                    case "isClosing" -> false;
                    default -> throw new AssertionError(method.getName());
                });
        // 反复借还消息的实际有界解码器
        var decoder = new CoreMessageFlyweightDecoder();
        // 测试请求的完整编码帧或其 DirectBuffer 包装
        var bytes = new UnsafeBuffer(frame(1));
        // 未启动业务运行时的 Core Owner，仅用作响应归还入口与出口身份
        var processor = new TradingCoreOwner(ProductLine.SPOT, (/* Owner 构造回调附带的会话 ID，本场景空回调不读取 */ id, /* Owner 构造回调附带的头，本场景空回调不读取 */ header, /* Owner 构造回调附带的响应，本场景空回调不处理 */ response, /* Owner 构造回调附带的提交序号，本场景空回调不读取 */ sequence) -> { });
        egress.start(cluster);
        try {
            // 本场景目标会话的实际传输句柄
            var transport = egress.transportSession(actual);
            // 当前持有解码租约的原消息，响应编码前不能被别的帧覆盖
            var message = decoder.decodeBorrowed(bytes, 0, bytes.capacity());
            // 本场景手动构造并交给实际出口的终态响应
            var response = message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                    CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD, 0, 0);
            egress.publishResponse(processor, transport.id(), message.header(), response, 1, 0);
            message.releaseDecoderSlot();
            if (removed) egress.closeSession(actual);
            else transport.close();
            assertThat(egress.drainResponses(processor)).isOne();
            // 检查原缓存槽是否已经可以再次取得的标记或槽引用
            boolean reused = false;
            for (/* 本轮解码绕回或输出环绕回的次数 */ int i = 0; i < 1024; i++) {
                // 本轮额外借出的解码消息，用后归还
                var next = decoder.decodeBorrowed(bytes, 0, bytes.capacity());
                reused |= next == message;
                next.releaseDecoderSlot();
            }
            assertThat(reused).as("discard returns both response and Header leases").isTrue();
        } finally { egress.clear(); }
    }

    /**
     * 通过真实 publishResponse 入口交出借用消息自身的响应，归还解码租约后仍不能提前复用；排出后核对 RESPONSE 头与数据，再构造八千三百次响应绕过输出环，早先复制的数据保持稳定。这里构造响应，不执行八千三百笔交易。
     */
    @Test void actualPublisherRetainsThePooledResponseAcrossDecoderAndOutputQueueWraps() {
        // 反复借还消息的实际有界解码器
        var decoder = new CoreMessageFlyweightDecoder();
        // 测试请求的完整编码帧或其 DirectBuffer 包装
        var bytes = frame(1);
        // 当前持有解码租约的原消息，响应编码前不能被别的帧覆盖
        var message = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        // 实际生产输出环与会话出口，只替换网络发送器
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 匿名发送器借用的目标测试会话 */ target) -> /* 本场景的测试会话发送器，只提供明确指定的发送行为，不创建真实网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 把一次编码响应交给测试会话发送，不读取任期与时间参数。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            /**
             * 本夹具没有创建独立 Publication，关闭不做额外操作。
             */
            public void close() { }
        });
        // 提供固定 Leader 角色和时间的测试 Cluster 代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        // 未启动业务运行时的 Core Owner，仅用作响应归还入口与出口身份
        var processor = new TradingCoreOwner(ProductLine.SPOT, (/* Owner 构造回调附带的会话 ID，本场景空回调不读取 */ session, /* Owner 构造回调附带的头，本场景空回调不读取 */ header, /* Owner 构造回调附带的响应，本场景空回调不处理 */ response, /* Owner 构造回调附带的提交序号，本场景空回调不读取 */ sequence) -> { });
        // 保存最近一次成功发送并独立解码的响应
        var sent = new AtomicReference<CoreMessage>();
        // 会复制成功发送字节的测试会话代理
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> {
                    if (method.getName().equals("id")) return 1L;
                    if (method.getName().equals("offer")) {
                        // 会话代理收到并复制的当前完整响应字节
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        sent.set(CoreMessageCodec.decode(result)); return 1L;
                    }
                    return zero(method.getReturnType());
                });
        // 调用实际 egress.publishResponse 的终态出口函数
        TradingCoreOwner.ResponseSink publisherSink = (/* 真实发布回调交来的目标会话 ID，原样交给 egress */ transport, /* 真实发布回调交来的响应头，保持租约交给 egress */ header, /* 真实发布回调交来的终态响应，交给输出环 */ response, /* 真实发布回调交来的已提交 Core 序号 */ sequence) ->
                egress.publishResponse(processor, transport, header, response, sequence, 0);
        try (/* 本场景自动关闭的独立 Core，只借取提交序号，不通过它执行业务命令 */ var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 本场景手动构造并交给实际出口的终态响应
            var response = message.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                    1, new byte[]{1, 2, 3}, 0, 3);
            TradingCoreOwner.publishResponse(publisherSink, egress.transportSession(session).id(),
                    message, response, state.committedCoreSequence());
            message.releaseDecoderSlot();
            for (/* 本轮解码绕回或输出环绕回的次数 */ int i = 0; i < 1024; i++) {
                // 本轮额外借出的解码消息，用后归还
                var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
                assertThat(next).isNotSameAs(message);
                next.releaseDecoderSlot();
            }
            assertThat(response.data()).containsExactly(1, 2, 3);
            egress.drainResponses(processor);
            assertThat(sent.get().header().kind()).isEqualTo(WireMessageKind.RESPONSE);
            assertThat(CoreProtocol.decodeResponse(sent.get().payloadUnsafe()).data()).containsExactly(1, 2, 3);
            // 第一次成功发送后独立保存的响应载荷，用后续绕回验证不变
            byte[] stable = sent.get().payload();
            for (/* 本轮解码绕回或输出环绕回的次数 */ int i = 0; i < 8300; i++) {
                // 本轮额外借出的解码消息，用后归还
                var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
                // 本次输出环绕回手动构造的后续终态响应
                var reply = next.ownedResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE,
                        i + 2, new byte[0], 0, 0);
                TradingCoreOwner.publishResponse(publisherSink, egress.transportSession(session).id(), next, reply, state.committedCoreSequence());
                next.releaseDecoderSlot();
                egress.drainResponses(processor);
                assertThat(CoreProtocol.decodeResponse(sent.get().payloadUnsafe()).appliedCommandCount()).isEqualTo(i + 2);
            }
            assertThat(CoreProtocol.decodeResponse(stable).data()).containsExactly(1, 2, 3);
        } finally { egress.clear(); }
    }

    /**
     * 按指定序号编码现货余额加一命令，供反复解码与消息租约测试使用。
     * @param id 测试帧唯一命令与来源序号
     */
    private static byte[] frame(long id) {
        return CoreMessageCodec.encode(new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                new UUID(1, id), ProductLine.SPOT, CommandSource.GATEWAY, 1, id, 7, 1, id), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 1))));
    }
    /**
     * 把借用 Header 留在输出环，Owner 侧先归还解码租约，再用不同 UUID 轮换解码器；编码前原 Header 不能改变，排出后允许原槽复用，独立保存消息的身份仍不变。
     */
    @Test void outputQueueRetainsHeaderUntilEncodingEvenAfterOwnerRetiresAndDecoderWraps() {
        // 反复借还消息的实际有界解码器
        var decoder = new CoreMessageFlyweightDecoder();
        // 测试请求的完整编码帧或其 DirectBuffer 包装
        var bytes = frame(1);
        // 当前持有解码租约的原消息，响应编码前不能被别的帧覆盖
        var message = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        // 按借用消息头和载荷创建的独立消息，用来验证原槽复用后身份仍稳定
        var detached = new CoreMessage(message.header(), message.payloadUnsafe());
        // 输出环编码前必须保留租约的原借用头
        var header = message.header();
        // 实际生产输出环与会话出口，只替换网络发送器
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 匿名发送器借用的目标测试会话 */ target) -> /* 本场景的测试会话发送器，只提供明确指定的发送行为，不创建真实网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 借用测试会话发送编码响应，网络发送本身由代理控制。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            /**
             * 测试发送器不持有实际网络 Publication，无额外关闭动作。
             */
            public void close() { }
        });
        // 提供固定 Leader 角色和时间的测试 Cluster 代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        // 未启动业务运行时的 Core Owner，仅用作响应归还入口与出口身份
        var processor = new TradingCoreOwner(ProductLine.SPOT, (/* Owner 构造回调附带的会话 ID，本场景空回调不读取 */ session, /* Owner 构造回调附带的头，本场景空回调不读取 */ requestHeader, /* Owner 构造回调附带的响应，本场景空回调不处理 */ response, /* Owner 构造回调附带的提交序号，本场景空回调不读取 */ sequence) -> { });
        // 保存最近一次成功发送并独立解码的响应
        var sent = new AtomicReference<CoreMessage>();
        // 会复制成功发送字节的测试会话代理
        var session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> {
                    if (method.getName().equals("id")) return 1L;
                    if (method.getName().equals("offer")) {
                        // 会话代理收到并复制的当前完整响应字节
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        sent.set(CoreMessageCodec.decode(result)); return 1L;
                    }
                    return zero(method.getReturnType());
                });
        egress.publishResponse(processor, egress.transportSession(session).id(), header,
                new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD), 1, 0);
        message.releaseDecoderSlot();
        for (/* 本轮借用缓存槽或访问会话的循环下标 */ int index = 0; index < 1024; index++) {
            // 带不同 UUID 的后续输入帧，检查不会覆盖仍被保留的原头
            var nextBytes = frame(index + 2);
            // 本轮额外借出的解码消息，用后归还
            var next = decoder.decodeBorrowed(new UnsafeBuffer(nextBytes), 0, nextBytes.length);
            assertThat(next).isNotSameAs(message);
            next.releaseDecoderSlot();
        }
        assertThat(header.commandId()).isEqualTo(new UUID(1, 1));
        assertThat(detached.header().commandId()).isEqualTo(new UUID(1, 1));
        egress.drainResponses(processor);
        assertThat(sent.get().header().commandId()).isEqualTo(new UUID(1, 1));
        // 检查原缓存槽是否已经可以再次取得的标记或槽引用
        CoreMessage reused = null;
        for (/* 本轮借用缓存槽或访问会话的循环下标 */ int index = 0; index < 512; index++) {
            // 本轮额外借出的解码消息，用后归还
            var next = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
            if (next == message) reused = next;
            next.releaseDecoderSlot();
        }
        assertThat(reused).isSameAs(message);
        assertThat(detached.header().commandId()).isEqualTo(new UUID(1, 1));
        egress.clear();
    }
    /**
     * 先入队正常会话的小响应，再让另一会话发布超过十六 MiB 的响应；排出后只把超限会话标记关闭，正常会话继续收到响应。
     */
    @Test void overflowIsolatesToOffendingSessionWithoutDisconnectingHealthySessions() {
        // 反复借还消息的实际有界解码器
        var decoder = new CoreMessageFlyweightDecoder();
        // 测试请求的完整编码帧或其 DirectBuffer 包装
        var bytes = frame(1);
        // 实际生产输出环与会话出口，只替换网络发送器
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 匿名发送器借用的目标测试会话 */ target) -> /* 本场景的测试会话发送器，只提供明确指定的发送行为，不创建真实网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 将正常会话的编码响应交给测试会话代理，超限响应不会进入这里编码发送。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) {
                return target.offer(source, offset, length);
            }
            /**
             * 测试发送器未持有网络资源，无额外释放。
             */
            public void close() { }
        });
        // 提供固定 Leader 角色和时间的测试 Cluster 代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "time" -> 1L;
                    default -> zero(method.getReturnType());
                });
        egress.start(cluster);
        // 未启动业务运行时的 Core Owner，仅用作响应归还入口与出口身份
        var processor = new TradingCoreOwner(ProductLine.SPOT, (/* Owner 构造回调附带的会话 ID，本场景空回调不读取 */ session, /* Owner 构造回调附带的头，本场景空回调不读取 */ requestHeader, /* Owner 构造回调附带的响应，本场景空回调不处理 */ response, /* Owner 构造回调附带的提交序号，本场景空回调不读取 */ sequence) -> { });
        // 记录正常会话实际收到的独立响应
        var healthyReceived = new AtomicReference<CoreMessage>();
        // 故意发布超大响应的会话代理
        var offendingSession = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "id" -> 100L;
                    default -> zero(method.getReturnType());
                });
        // 正常小响应的目标会话代理
        var healthySession = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> switch (method.getName()) {
                    case "id" -> 200L;
                    case "offer" -> {
                        // 会话代理收到并复制的当前完整响应字节
                        byte[] result = new byte[(int) args[2]];
                        ((DirectBuffer) args[0]).getBytes((int) args[1], result);
                        healthyReceived.set(CoreMessageCodec.decode(result));
                        yield 1L;
                    }
                    default -> zero(method.getReturnType());
                });

        // 超限会话的真实传输句柄
        var offendingTransport = egress.transportSession(offendingSession);
        // 正常会话的真实传输句柄
        var healthyTransport = egress.transportSession(healthySession);

        // 提供本次响应头的借用请求消息
        var normalMsg = decoder.decodeBorrowed(new UnsafeBuffer(bytes), 0, bytes.length);
        // 正常会话要接收的小型成功响应
        var normalResponse = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, CoreMessage.EMPTY_PAYLOAD);

        // 先让正常会话发布一条小型终态响应。
        egress.publishResponse(processor, healthyTransport.id(), normalMsg.header(), normalResponse, 1, 0);

        // 另一会话发布超过输出环十六 MiB 编码预算的响应，制造出口超限。
        // 十七 MiB 数据，故意超过输出交接环的编码字节预算
        byte[] hugePayload = new byte[17 * 1024 * 1024];
        // 含超限载荷的已构造终态，用来验证只处理出口与会话
        var overflowResponse = new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED, CoreResultCode.NONE, 2, hugePayload);
        egress.publishResponse(processor, offendingTransport.id(), normalMsg.header(), overflowResponse, 2, 0);

        normalMsg.releaseDecoderSlot();

        // 排出后只标记超限会话关闭，正常会话仍应收到此前的小响应。
        egress.drainResponses(processor);

        assertThat(offendingTransport.isClosing()).as("Offending session must be marked for closure").isTrue();
        assertThat(healthyTransport.isClosing()).as("Healthy session must stay alive").isFalse();
        assertThat(healthyReceived.get()).isNotNull();
        assertThat(healthyReceived.get().payload()).isNotNull();

        egress.clear();
    }

    /**
     * 额外线程对两千个会话重复发布超限响应，回调线程并发消费关闭请求；最终所有目标均标记关闭，未发布超限响应的额外会话保持正常。本场景检查关闭请求，不额外发送健康会话响应。
     */
    @Test void concurrentOverflowPublicationDoesNotLoseDistinctSessionsOrCloseHealthyOnes() throws Exception {
        // 实际生产输出环与会话出口，只替换网络发送器
        var egress = new ClusterServiceEgress((/* 发送器工厂附带的 Cluster，本夹具不读取 */ ignored, /* 当前 ClientSession 代理或工厂附带会话，具体发送由场景控制 */ actual) -> /* 本场景的测试会话发送器，只提供明确指定的发送行为，不创建真实网络 Publication */ new ClusterServiceEgress.SessionEgress() {
            /**
             * 提供成功发送返回值；本场景超限响应在入队前被挡住，主要验证并发关闭请求集合。
             * @param term 出口契约的领导任期，本夹具不读取
             * @param timestamp 出口契约的时间，本夹具不读取
             * @param source 本轮已编码响应缓冲
             * @param offset 有效字节的起始偏移
             * @param length 本轮有效字节数
             */
            public long offer(long term, long timestamp, DirectBuffer source, int offset, int length) { return 1; }
            /**
             * 夹具没有创建实际 Publication，无额外释放。
             */
            public void close() { }
        });
        // 提供固定 Leader 角色和时间的测试 Cluster 代理
        var cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) -> method.getName().equals("role") ? Cluster.Role.LEADER : zero(method.getReturnType()));
        egress.start(cluster);
        // 需要并发登记关闭请求的目标会话数，两千个
        int count = 2_000;
        // 两千个超限目标以及一个未发布超限响应的会话句柄
        var sessions = new ClusterServiceEgress.TransportSession[count + 1];
        for (/* 本轮借用缓存槽或访问会话的循环下标 */ int index = 0; index <= count; index++) {
            // 本轮创建会话使用的唯一纯 ID
            long id = index + 1_000L;
            // 当前 ClientSession 代理或工厂附带会话，具体发送由场景控制
            var actual = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (/* 动态代理对象本身，方法分发不读取 */ proxy, /* 当前代理方法，按名称分发会话或 Cluster 操作 */ method, /* 代理调用实参；发送分支读取编码缓冲及范围，元数据分支不读 */ args) ->
                            method.getName().equals("id") ? id : zero(method.getReturnType()));
            sessions[index] = egress.transportSession(actual);
        }
        // 未启动运行时的测试 Owner，供并发异常出口调用
        var owner = new TradingCoreOwner(ProductLine.SPOT, (/* Owner 构造回调附带的会话 ID，本场景空回调不读取 */ id, /* Owner 构造回调附带的头，本场景空回调不读取 */ header, /* Owner 构造回调附带的响应，本场景空回调不处理 */ response, /* Owner 构造回调附带的提交序号，本场景空回调不读取 */ sequence) -> { });
        // 为并发超限响应提供稳定头部的独立请求
        var request = CoreMessageCodec.decode(frame(1));
        // 本场景手动构造并交给实际出口的终态响应
        var response = CoreResponse.owned(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                CoreResultCode.NONE, 1, new byte[17 * 1024 * 1024]);
        // 并发生产者捕获的故障，用原子引用交回测试
        var failure = new AtomicReference<Throwable>();
        // 生产者开始发布异常响应的门闩
        var started = new java.util.concurrent.CountDownLatch(1);
        // 并发重复发布各会话超限响应的额外线程
        Thread producer = Thread.ofPlatform().start(() -> {
            try {
                started.countDown();
                for (/* 重复覆盖全部超限目标的轮次 */ int round = 0; round < 8; round++) for (/* 本轮借用缓存槽或访问会话的循环下标 */ int index = 0; index < count; index++)
                    egress.publishResponse(owner, sessions[index].id(), request.header(), response, 1, 0);
            } catch (/* 额外线程捕获的故障，交回主测试断言 */ Throwable caught) { failure.set(caught); }
        });
        try {
            assertThat(started.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 主测试并发排出关闭请求的十秒截止时间
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (producer.isAlive() && System.nanoTime() - deadline < 0) egress.drainResponses(owner);
            producer.join(1_000);
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            egress.drainResponses(owner);
            for (/* 本轮借用缓存槽或访问会话的循环下标 */ int index = 0; index < count; index++) assertThat(sessions[index].isClosing()).isTrue();
            assertThat(sessions[count].isClosing()).isFalse();
        } finally {
            producer.interrupt(); producer.join(1_000); egress.clear();
        }
    }

    /**
     * 只为代理所需的 long、int 和 boolean 返回对应零值，其余类型返回 null；不尝试实现完整 Java 基本类型默认值集合。
     * @param type 代理方法的返回类型，用来给出测试所需零值
     */
    private static Object zero(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }
}
