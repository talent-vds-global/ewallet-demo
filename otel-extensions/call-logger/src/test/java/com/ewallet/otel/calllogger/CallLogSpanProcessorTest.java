package com.ewallet.otel.calllogger;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallLogSpanProcessorTest {

    private InMemoryLogRecordExporter logs;
    private SdkLoggerProvider loggerProvider;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        logs = InMemoryLogRecordExporter.create();
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
                .build();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new CallLogSpanProcessor(
                        () -> loggerProvider.get("test"), false, List.of("/actuator")))
                .build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        loggerProvider.close();
    }

    private LogRecordData single() {
        assertThat(logs.getFinishedLogRecordItems()).hasSize(1);
        return logs.getFinishedLogRecordItems().get(0);
    }

    @Test
    @DisplayName("gRPC client: log mang đúng trace_id/span_id của span và mô tả lời gọi")
    void grpcClient() {
        Span span = tracer.spanBuilder("PaymentBusinessService/AuthorizePayment")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.service", "PaymentBusinessService")
                .setAttribute("rpc.method", "AuthorizePayment")
                .setAttribute(AttributeKey.longKey("rpc.grpc.status_code"), 0L)
                .setAttribute("server.address", "ewallet-payment-business")
                .setAttribute(AttributeKey.longKey("server.port"), 9091L)
                .startSpan();
        span.end();

        LogRecordData log = single();
        assertThat(log.getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(log.getSpanContext().getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
        assertThat(log.getSeverity()).isEqualTo(Severity.INFO);
        assertThat(log.getBody().asString())
                .startsWith("SERVICE_CALL outbound grpc -> ewallet-payment-business:9091 "
                        + "PaymentBusinessService/AuthorizePayment grpc_0 ");
        assertThat(log.getAttributes().get(CallLogSpanProcessor.CALL_DIRECTION)).isEqualTo("outbound");
        assertThat(log.getAttributes().get(CallLogSpanProcessor.CALL_PROTOCOL)).isEqualTo("grpc");
        assertThat(log.getAttributes().get(CallLogSpanProcessor.CALL_DURATION_MS)).isNotNull();
    }

    @Test
    @DisplayName("HTTP server: dùng http.route, trạng thái là mã HTTP")
    void httpServer() {
        tracer.spanBuilder("POST /api/orders")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", "POST")
                .setAttribute("http.route", "/api/orders")
                .setAttribute(AttributeKey.longKey("http.response.status_code"), 201L)
                .setAttribute("client.address", "172.18.0.9")
                .startSpan().end();

        LogRecordData log = single();
        assertThat(log.getBody().asString())
                .startsWith("SERVICE_CALL inbound http <- 172.18.0.9 POST /api/orders 201 ");
    }

    @Test
    @DisplayName("HTTP client: bỏ query string khỏi log")
    void httpClientKhongLoQuery() {
        tracer.spanBuilder("GET")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("url.full", "http://partner-sim:8090/partner/bill?account=0901234567")
                .setAttribute("server.address", "partner-sim")
                .setAttribute(AttributeKey.longKey("server.port"), 8090L)
                .setAttribute(AttributeKey.longKey("http.response.status_code"), 200L)
                .startSpan().end();

        assertThat(single().getAttributes().get(CallLogSpanProcessor.CALL_OPERATION))
                .isEqualTo("GET /partner/bill");
    }

    @Test
    @DisplayName("Kafka consumer: peer là topic@group")
    void kafkaConsumer() {
        tracer.spanBuilder("ewallet.payment.events process")
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", "ewallet.payment.events")
                .setAttribute("messaging.operation", "process")
                .setAttribute("messaging.kafka.consumer.group", "notification-cg")
                .startSpan().end();

        assertThat(single().getBody().asString())
                .startsWith("SERVICE_CALL inbound kafka <- ewallet.payment.events@notification-cg "
                        + "process ewallet.payment.events OK ");
    }

    @Test
    @DisplayName("span lỗi thì log mức WARN kèm loại lỗi")
    void loiThiWarn() {
        tracer.spanBuilder("POST")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "POST")
                .setAttribute("server.address", "partner-sim")
                .setAttribute("error.type", "java.net.SocketTimeoutException")
                .startSpan()
                .setStatus(StatusCode.ERROR)
                .end();

        LogRecordData log = single();
        assertThat(log.getSeverity()).isEqualTo(Severity.WARN);
        assertThat(log.getAttributes().get(CallLogSpanProcessor.CALL_ERROR))
                .isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    @DisplayName("bỏ qua span INTERNAL, span JDBC và /actuator")
    void boQuaNhieu() {
        tracer.spanBuilder("OrderService.create").startSpan().end();
        tracer.spanBuilder("SELECT orderdb.orders")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "postgresql")
                .startSpan().end();
        tracer.spanBuilder("GET /actuator/health")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.request.method", "GET")
                .setAttribute("http.route", "/actuator/health")
                .startSpan().end();
        tracer.spanBuilder("GET")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("url.full", "http://ewallet-payment-order:8082/actuator/health")
                .startSpan().end();

        assertThat(logs.getFinishedLogRecordItems()).isEmpty();
    }

    @Test
    @DisplayName("span WebSocket tự tạo: giao thức websocket, peer lấy từ peer.service")
    void websocket() {
        tracer.spanBuilder("websocket send WATCH")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "websocket")
                .setAttribute("messaging.operation", "send")
                .setAttribute("messaging.destination.name", "WATCH")
                .setAttribute("peer.service", "partner-sim")
                .startSpan().end();

        assertThat(single().getBody().asString())
                .startsWith("SERVICE_CALL outbound websocket -> partner-sim send WATCH OK ");
    }
}
