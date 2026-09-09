package com.surprising.aeron.service.execution;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.MessageHeaderEncoder;
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * 已提交结果的独立Aeron出口，使用SDK的标准Cluster会话消息头。
 * 不调用限制在状态机回调中的ClientSession.offer，不写集群日志；仅Aeron服务线程使用。
 */
final class SessionResponsePublication implements ContinuousTradingClusterService.SessionEgress {
    /** 该会话拥有的发布器句柄，在关闭或卸任leader时释放。 */
    private final Publication publication;
    /** 每会话复用的SDK头部编码器与固定大小缓冲区。 */
    private final UnsafeBuffer header = new UnsafeBuffer(new byte[AeronCluster.SESSION_HEADER_LENGTH]);
    private final SessionMessageHeaderEncoder encoder = new SessionMessageHeaderEncoder();

    SessionResponsePublication(Cluster cluster, ClientSession session) {
        publication = cluster.aeron().addPublication(session.responseChannel(), session.responseStreamId());
        encoder.wrapAndApplyHeader(header, 0, new MessageHeaderEncoder()).clusterSessionId(session.id());
    }

    public long offer(long leadershipTermId, long timestamp, DirectBuffer source, int offset, int length) {
        encoder.leadershipTermId(leadershipTermId).timestamp(timestamp);
        return publication.offer(header, 0, header.capacity(), source, offset, length, null);
    }

    public void close() { publication.close(); }
}
