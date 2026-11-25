package com.surprising.marketmaker.provider.client;

import com.surprising.instrument.api.client.InstrumentRpcApi;
import com.surprising.instrument.api.model.InstrumentQueryResponse;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentSnapshotResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentTradeEncoding;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Lightweight JDK HttpClient implementation of {@link InstrumentRpcApi} without OpenFeign/HC5/Resilience4j. */
@Component
public class GatewayInstrumentClient implements InstrumentRpcApi {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GatewayInstrumentClient(
            @Value("${surprising.clients.instrument.base-url:${surprising.clients.gateway.url:http://127.0.0.1:9094}}") String baseUrl,
            @Value("${surprising.market-maker.internal-service-token:}") String internalToken,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
    }

    @Override
    public InstrumentResponse latest(int instrumentId, ProductLine productLine) {
        return get("/internal/v1/instruments/latest?instrumentId=" + instrumentId
                + "&productLine=" + encode(productLine.name()), InstrumentResponse.class);
    }

    @Override
    public InstrumentQueryResponse list(ProductLine productLine, InstrumentType type, InstrumentStatus status) {
        StringBuilder path = new StringBuilder("/internal/v1/instruments/list");
        char sep = '?';
        if (productLine != null) { path.append(sep).append("productLine=").append(encode(productLine.name())); sep = '&'; }
        if (type != null) { path.append(sep).append("type=").append(encode(type.name())); sep = '&'; }
        if (status != null) { path.append(sep).append("status=").append(encode(status.name())); }
        return get(path.toString(), InstrumentQueryResponse.class);
    }

    @Override
    public InstrumentTradeEncoding tradeEncoding(ProductLine productLine, int instrumentId, long changeId) {
        return get("/internal/v1/instruments/trade-encoding?productLine=" + encode(productLine.name())
                + "&instrumentId=" + instrumentId + "&changeId=" + changeId, InstrumentTradeEncoding.class);
    }

    @Override
    public InstrumentSnapshotResponse snapshot(ProductLine productLine) {
        return get("/internal/v1/instruments/snapshot?productLine=" + encode(productLine.name()),
                InstrumentSnapshotResponse.class);
    }

    private <T> T get(String path, Class<T> type) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (internalToken != null && !internalToken.isBlank()) {
            request.header("X-Internal-Service-Token", internalToken);
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Instrument RPC failed status=" + response.statusCode() + " path=" + path);
            }
            return objectMapper.readValue(response.body(), type);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Instrument RPC interrupted path=" + path, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Instrument RPC failed path=" + path + ": " + ex.getMessage(), ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
