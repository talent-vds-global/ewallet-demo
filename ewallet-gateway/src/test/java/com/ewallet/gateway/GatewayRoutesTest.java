package com.ewallet.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Gateway không có mã nghiệp vụ — toàn bộ hành vi nằm ở bảng route trong
 * {@code application.yml}. Thứ tự route là thứ dễ hỏng nhất: Spring Cloud Gateway
 * khớp theo thứ tự khai báo, nên route cụ thể phải đứng TRƯỚC route {@code /api/**}
 * fallback. Đảo thứ tự thì mọi request đều rơi về mobileapp mà không báo lỗi gì.
 *
 * <p>Test này đọc thẳng file cấu hình để giữ ràng buộc đó, không cần nâng context.</p>
 */
class GatewayRoutesTest {

    private static Map<String, Object> root;
    private static List<Map<String, Object>> routes;
    private static Map<String, Object> gateway;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void docCauHinh() throws Exception {
        try (InputStream in = GatewayRoutesTest.class.getResourceAsStream("/application.yml")) {
            root = new Yaml().load(in);
            Map<String, Object> cloud =
                    (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("cloud");
            gateway = (Map<String, Object>) cloud.get("gateway");
            routes = (List<Map<String, Object>>) gateway.get("routes");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> predicatesOf(Map<String, Object> route) {
        return (List<String>) route.get("predicates");
    }

    private static Map<String, Object> routeById(String id) {
        return routes.stream()
                .filter(r -> id.equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("khong co route id=" + id));
    }

    private static int indexOf(String id) {
        for (int i = 0; i < routes.size(); i++) {
            if (id.equals(routes.get(i).get("id"))) {
                return i;
            }
        }
        throw new AssertionError("khong co route id=" + id);
    }

    @Test
    @DisplayName("đủ năm route của hệ: thông báo, ví, đơn nội bộ, admin và fallback")
    void duCacRoute() {
        assertThat(routes).extracting(r -> r.get("id"))
                .containsExactly("notifications", "wallet", "orders-internal", "admin", "mobileapp");
    }

    @Test
    @DisplayName("route /api/** fallback phải đứng cuối, nếu không nó nuốt hết các route khác")
    void fallbackDungCuoi() {
        assertThat(indexOf("mobileapp")).isEqualTo(routes.size() - 1);
        assertThat(predicatesOf(routes.get(routes.size() - 1))).containsExactly("Path=/api/**");
    }

    @Test
    @DisplayName("route thông báo và route ví đứng trước fallback vì cùng tiền tố /api")
    void routeCuTheDungTruocFallback() {
        int fallback = indexOf("mobileapp");
        assertThat(indexOf("notifications")).isLessThan(fallback);
        assertThat(indexOf("wallet")).isLessThan(fallback);
    }

    @Test
    @DisplayName("mỗi route trỏ đúng service qua biến môi trường, có giá trị mặc định cho Docker")
    void dichDenDungService() {
        assertThat(routeById("notifications").get("uri").toString())
                .contains("NOTIFICATION_URI").contains("ewallet-notification:8085");
        assertThat(routeById("wallet").get("uri").toString())
                .contains("MOBILEAPP_URI").contains("ewallet-business-customer-mobileapp:8081");
        assertThat(routeById("orders-internal").get("uri").toString())
                .contains("ORDER_URI").contains("ewallet-payment-order:8082");
        assertThat(routeById("admin").get("uri").toString())
                .contains("BUSINESS_URI").contains("ewallet-payment-business:8083");
    }

    @Test
    @DisplayName("route đơn nội bộ đổi /orders/** thành /api/orders/** trước khi chuyển tiếp")
    @SuppressWarnings("unchecked")
    void doiDuongDanDonNoiBo() {
        Map<String, Object> route = routeById("orders-internal");

        assertThat(predicatesOf(route)).containsExactly("Path=/orders/**");
        assertThat((List<String>) route.get("filters"))
                .anySatisfy(f -> assertThat(f).startsWith("RewritePath=/orders/")
                        .contains("/api/orders/"));
    }

    @Test
    @DisplayName("route ví và thông báo giữ nguyên đường dẫn, không đổi tiền tố")
    void khongDoiDuongDanCacRouteKhac() {
        assertThat(routeById("wallet")).doesNotContainKey("filters");
        assertThat(routeById("notifications")).doesNotContainKey("filters");
        assertThat(predicatesOf(routeById("notifications")))
                .containsExactly("Path=/api/notifications/**");
        assertThat(predicatesOf(routeById("wallet"))).containsExactly("Path=/api/wallet/**");
    }

    @Test
    @DisplayName("mọi request qua gateway được gắn header đánh dấu nguồn")
    @SuppressWarnings("unchecked")
    void ganHeaderDanhDau() {
        assertThat((List<String>) gateway.get("default-filters"))
                .contains("AddRequestHeader=X-Gateway, ewallet-gateway");
    }

    @Test
    @DisplayName("CORS mở cho localhost ở mọi cổng để frontend demo gọi được")
    @SuppressWarnings("unchecked")
    void corsChoLocalhost() {
        Map<String, Object> cors = (Map<String, Object>)
                ((Map<String, Object>) gateway.get("globalcors")).get("cors-configurations");
        Map<String, Object> all = (Map<String, Object>) cors.get("[/**]");

        assertThat((List<String>) all.get("allowedOriginPatterns"))
                .contains("http://localhost:*", "http://127.0.0.1:*");
        assertThat((List<String>) all.get("allowedMethods"))
                .contains("GET", "POST", "OPTIONS");
        assertThat(all.get("allowCredentials")).isEqualTo(false);
    }

    @Test
    @DisplayName("actuator cũng mở CORS — globalcors không áp cho /actuator/**, thiếu thì Demo Console báo mất kết nối")
    @SuppressWarnings("unchecked")
    void corsChoActuator() {
        Map<String, Object> web = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) root.get("management")).get("endpoints")).get("web");
        Map<String, Object> cors = (Map<String, Object>) web.get("cors");

        assertThat(cors).as("management.endpoints.web.cors").isNotNull();
        assertThat(cors.get("allowed-origin-patterns").toString())
                .contains("http://localhost:*", "http://127.0.0.1:*");
        assertThat(cors.get("allowed-methods").toString()).contains("GET");
    }
}
