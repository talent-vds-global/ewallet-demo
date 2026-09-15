package com.ewallet.thirdparty.ws;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;

/**
 * Nối trace qua frame WebSocket.
 *
 * <p>OTel Java Agent tự truyền {@code traceparent} cho HTTP, gRPC, Kafka — nhưng không cho từng
 * frame WebSocket, vì kênh mở một lần lúc khởi động và sống hàng giờ. Nên frame nghiệp vụ
 * ({@code WATCH}, {@code SETTLEMENT}) tự mang trường {@code traceparent} (định dạng W3C), và mỗi
 * bên mở span PRODUCER khi gửi / CONSUMER khi nhận. Nhờ vậy quyết toán về sau vẫn nằm chung trace
 * với giao dịch, và extension call-logger ghi được log {@code SERVICE_CALL} cho nó.</p>
 *
 * <p>Không có agent (unit test, chạy thường) thì mọi thứ là no-op và frame không có trường mới.
 * Frame {@code PING}/{@code SUBSCRIBE} cố ý không gắn trace — chúng không thuộc giao dịch nào.</p>
 */
final class WsTracing {

    static final String FIELD = "traceparent";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private WsTracing() {
    }

    private static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("com.ewallet.websocket");
    }

    /** Span PRODUCER cho một frame sắp gửi, con của context hiện tại. Nhớ {@code makeCurrent()} và {@code end()}. */
    static Span startSend(String frameType, String peer) {
        return tracer().spanBuilder("websocket send " + frameType)
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "websocket")
                .setAttribute("messaging.operation", "send")
                .setAttribute("messaging.destination.name", frameType)
                .setAttribute("peer.service", peer)
                .startSpan();
    }

    /** Span CONSUMER cho một frame vừa nhận; cha là {@code traceparent} trong frame (nếu có). */
    static Span startReceive(String frameType, String peer, String traceparent) {
        Context parent = Context.root();
        if (traceparent != null && !traceparent.isBlank()) {
            parent = W3CTraceContextPropagator.getInstance()
                    .extract(Context.root(), Map.of(FIELD, traceparent), GETTER);
        }
        return tracer().spanBuilder("websocket receive " + frameType)
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "websocket")
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.destination.name", frameType)
                .setAttribute("peer.service", peer)
                .startSpan();
    }

    /** Thêm {@code "traceparent"} vào cuối một JSON object nếu đang có trace; không thì trả nguyên. */
    static String withTraceparent(String jsonObject) {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        String traceparent = carrier.get(FIELD);
        if (traceparent == null || !jsonObject.endsWith("}")) {
            return jsonObject;
        }
        return jsonObject.substring(0, jsonObject.length() - 1)
                + ",\"" + FIELD + "\":\"" + traceparent + "\"}";
    }
}
