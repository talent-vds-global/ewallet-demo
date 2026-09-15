package com.ewallet.otel.calllogger;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.List;

/**
 * Điểm nạp của extension (khai báo trong META-INF/services).
 *
 * <p>Cấu hình qua system property hoặc biến môi trường:</p>
 * <ul>
 *   <li>{@code otel.call-logger.enabled} (mặc định true)</li>
 *   <li>{@code otel.call-logger.include-db} — ghi cả span JDBC (mặc định false)</li>
 *   <li>{@code otel.call-logger.exclude-paths} — bỏ qua các HTTP path này (mặc định /actuator)</li>
 * </ul>
 */
public final class CallLoggerCustomizerProvider implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addTracerProviderCustomizer((builder, config) -> {
            if (!config.getBoolean("otel.call-logger.enabled", true)) {
                return builder;
            }
            return builder.addSpanProcessor(create(config));
        });
    }

    private static CallLogSpanProcessor create(ConfigProperties config) {
        boolean includeDb = config.getBoolean("otel.call-logger.include-db", false);
        List<String> excluded = config.getList("otel.call-logger.exclude-paths", List.of("/actuator"));
        // Logger lấy lười ở span đầu tiên kết thúc: lúc đó agent đã đăng ký SDK làm global.
        return new CallLogSpanProcessor(
                () -> GlobalOpenTelemetry.get().getLogsBridge().get("com.ewallet.otel.call-logger"),
                includeDb, excluded);
    }
}
