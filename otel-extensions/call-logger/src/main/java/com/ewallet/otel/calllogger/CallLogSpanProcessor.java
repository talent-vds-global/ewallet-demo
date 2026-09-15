package com.ewallet.otel.calllogger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Mỗi span "gọi giữa các service" kết thúc thì bắn ra một log record {@code SERVICE_CALL}.
 *
 * <p>Span CLIENT/PRODUCER là lời gọi đi, SERVER/CONSUMER là lời gọi đến. Log record được gắn
 * đúng span context của lời gọi, nên trong Loki nó mang cùng {@code trace_id}/{@code span_id}
 * với span trong Tempo và với log nghiệp vụ của service.</p>
 *
 * <p>Span JDBC (có {@code db.system}) mặc định bị bỏ qua: đó là gọi database, không phải gọi
 * service, và một request N+1 có thể sinh hàng chục span như vậy.</p>
 */
public final class CallLogSpanProcessor implements SpanProcessor {

    static final String EVENT_NAME = "service.call";

    // Semantic conventions mà opentelemetry-javaagent 2.9.0 phát ra
    private static final AttributeKey<String> HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    private static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");
    private static final AttributeKey<String> CLIENT_ADDRESS = AttributeKey.stringKey("client.address");
    private static final AttributeKey<String> NETWORK_PEER_ADDRESS = AttributeKey.stringKey("network.peer.address");
    private static final AttributeKey<String> PEER_SERVICE = AttributeKey.stringKey("peer.service");
    private static final AttributeKey<String> RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    private static final AttributeKey<String> RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    private static final AttributeKey<String> RPC_METHOD = AttributeKey.stringKey("rpc.method");
    private static final AttributeKey<Long> GRPC_STATUS = AttributeKey.longKey("rpc.grpc.status_code");
    private static final AttributeKey<String> MSG_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> MSG_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> MSG_OPERATION = AttributeKey.stringKey("messaging.operation");
    private static final AttributeKey<String> KAFKA_GROUP = AttributeKey.stringKey("messaging.kafka.consumer.group");
    private static final AttributeKey<String> CONSUMER_GROUP = AttributeKey.stringKey("messaging.consumer.group.name");
    private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    // Thuộc tính của log record — Loki đổi dấu chấm thành gạch dưới: call_direction, call_peer...
    static final AttributeKey<String> CALL_DIRECTION = AttributeKey.stringKey("call.direction");
    static final AttributeKey<String> CALL_PROTOCOL = AttributeKey.stringKey("call.protocol");
    static final AttributeKey<String> CALL_PEER = AttributeKey.stringKey("call.peer");
    static final AttributeKey<String> CALL_OPERATION = AttributeKey.stringKey("call.operation");
    static final AttributeKey<String> CALL_STATUS = AttributeKey.stringKey("call.status");
    static final AttributeKey<Long> CALL_DURATION_MS = AttributeKey.longKey("call.duration_ms");
    static final AttributeKey<String> CALL_ERROR = AttributeKey.stringKey("call.error");
    static final AttributeKey<String> CALL_SPAN_NAME = AttributeKey.stringKey("call.span_name");
    static final AttributeKey<String> EVENT_NAME_KEY = AttributeKey.stringKey("event.name");

    private final Supplier<Logger> loggerSupplier;
    private final boolean includeDb;
    private final List<String> excludedPaths;
    private volatile Logger logger;

    public CallLogSpanProcessor(Supplier<Logger> loggerSupplier, boolean includeDb, List<String> excludedPaths) {
        this.loggerSupplier = loggerSupplier;
        this.includeDb = includeDb;
        this.excludedPaths = List.copyOf(excludedPaths);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanKind kind = span.getKind();
        if (kind == SpanKind.INTERNAL) {
            return;
        }
        SpanData data = span.toSpanData();
        Attributes attrs = data.getAttributes();
        if (!includeDb && attrs.get(DB_SYSTEM) != null) {
            return;
        }
        if (isExcludedPath(attrs)) {
            return;
        }

        boolean outbound = kind == SpanKind.CLIENT || kind == SpanKind.PRODUCER;
        String protocol = protocol(attrs);
        String peer = peer(attrs, outbound, protocol);
        String operation = operation(attrs, protocol, data.getName());
        String status = status(attrs, data);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(data.getEndEpochNanos() - data.getStartEpochNanos());
        boolean failed = data.getStatus().getStatusCode() == StatusCode.ERROR;

        String body = String.format("SERVICE_CALL %s %s %s %s %s %s %dms",
                outbound ? "outbound" : "inbound",
                protocol,
                outbound ? "->" : "<-",
                peer,
                operation,
                status,
                durationMs);

        AttributesBuilder out = Attributes.builder()
                .put(EVENT_NAME_KEY, EVENT_NAME)
                .put(CALL_DIRECTION, outbound ? "outbound" : "inbound")
                .put(CALL_PROTOCOL, protocol)
                .put(CALL_PEER, peer)
                .put(CALL_OPERATION, operation)
                .put(CALL_STATUS, status)
                .put(CALL_DURATION_MS, durationMs)
                .put(CALL_SPAN_NAME, data.getName());
        String error = error(attrs, data);
        if (error != null) {
            out.put(CALL_ERROR, error);
        }

        logger().logRecordBuilder()
                .setContext(Context.root().with(Span.wrap(data.getSpanContext())))
                .setTimestamp(data.getEndEpochNanos(), TimeUnit.NANOSECONDS)
                .setSeverity(failed ? Severity.WARN : Severity.INFO)
                .setSeverityText(failed ? "WARN" : "INFO")
                .setBody(body)
                .setAllAttributes(out.build())
                .emit();
    }

    private Logger logger() {
        Logger current = logger;
        if (current == null) {
            current = loggerSupplier.get();
            logger = current;
        }
        return current;
    }

    private boolean isExcludedPath(Attributes attrs) {
        String path = attrs.get(HTTP_ROUTE);
        if (path == null) {
            path = attrs.get(URL_PATH);
        }
        if (path == null) {
            path = pathOf(attrs.get(URL_FULL));   // span HTTP client chỉ có url.full
        }
        if (path == null) {
            return false;
        }
        for (String prefix : excludedPaths) {
            if (!prefix.isEmpty() && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String protocol(Attributes attrs) {
        String rpc = attrs.get(RPC_SYSTEM);
        if (rpc != null) {
            return rpc;
        }
        String messaging = attrs.get(MSG_SYSTEM);
        if (messaging != null) {
            return messaging;
        }
        if (attrs.get(HTTP_METHOD) != null) {
            return "http";
        }
        return "other";
    }

    private static String peer(Attributes attrs, boolean outbound, String protocol) {
        String peerService = attrs.get(PEER_SERVICE);
        if (peerService != null) {
            return peerService;
        }
        if (attrs.get(MSG_SYSTEM) != null && !"websocket".equals(protocol)) {
            // Kafka không có "máy bên kia" cụ thể — chỗ gặp nhau là topic (+ consumer group)
            String topic = orDefault(attrs.get(MSG_DESTINATION), "?");
            String group = attrs.get(CONSUMER_GROUP) != null ? attrs.get(CONSUMER_GROUP) : attrs.get(KAFKA_GROUP);
            return group == null || outbound ? topic : topic + "@" + group;
        }
        if (outbound) {
            String host = attrs.get(SERVER_ADDRESS);
            if (host == null) {
                return "?";
            }
            Long port = attrs.get(SERVER_PORT);
            return port == null ? host : host + ":" + port;
        }
        // HTTP server có client.address; gRPC server chỉ có network.peer.address
        String client = attrs.get(CLIENT_ADDRESS);
        return client != null ? client : orDefault(attrs.get(NETWORK_PEER_ADDRESS), "?");
    }

    private static String operation(Attributes attrs, String protocol, String spanName) {
        if (attrs.get(RPC_METHOD) != null) {
            String service = attrs.get(RPC_SERVICE);
            return service == null ? attrs.get(RPC_METHOD) : service + "/" + attrs.get(RPC_METHOD);
        }
        String method = attrs.get(HTTP_METHOD);
        if (method != null) {
            String target = attrs.get(HTTP_ROUTE);
            if (target == null) {
                target = attrs.get(URL_PATH);
            }
            if (target == null) {
                target = pathOf(attrs.get(URL_FULL));
            }
            return target == null ? method : method + " " + target;
        }
        if (attrs.get(MSG_SYSTEM) != null) {
            String op = orDefault(attrs.get(MSG_OPERATION), spanName);
            String destination = attrs.get(MSG_DESTINATION);
            return destination == null ? op : op + " " + destination;
        }
        return spanName;
    }

    private static String status(Attributes attrs, SpanData data) {
        Long http = attrs.get(HTTP_STATUS);
        if (http != null) {
            return String.valueOf(http);
        }
        Long grpc = attrs.get(GRPC_STATUS);
        if (grpc != null) {
            return "grpc_" + grpc;
        }
        return data.getStatus().getStatusCode() == StatusCode.ERROR ? "ERROR" : "OK";
    }

    private static String error(Attributes attrs, SpanData data) {
        String type = attrs.get(ERROR_TYPE);
        if (type != null) {
            return type;
        }
        if (data.getStatus().getStatusCode() == StatusCode.ERROR) {
            String description = data.getStatus().getDescription();
            return description == null || description.isEmpty() ? "ERROR" : description;
        }
        return null;
    }

    /** Chỉ lấy path, bỏ query string để không lọt tham số (số tài khoản, token...) vào log. */
    static String pathOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            int q = url.indexOf('?');
            return q < 0 ? url : url.substring(0, q);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
