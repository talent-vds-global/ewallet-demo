package com.ewallet.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ewallet.notification.entity.NotificationOutbox;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Bảng định tuyến thông báo — R-NOTIF-01 (kênh theo loại event),
 * R-NOTIF-02 (người nhận P2P cũng được báo), R-NOTIF-07 (giao dịch lớn thêm SMS).
 *
 * <p>Bảng đầy đủ: docs/specs/F5-async-notification.md §2.4.</p>
 */
class NotificationRouterTest {

    private static final String CUSTOMER = "CUST-001";
    private static final String RECEIVER = "CUST-002";

    private final NotificationRouter router = new NotificationRouter();

    private List<NotificationRouter.Target> route(String eventType, String counterparty,
                                                  long amount, long amountVnd) {
        return router.route(eventType, CUSTOMER, counterparty, amount, amountVnd, "OK");
    }

    @Nested
    @DisplayName("PaymentCompleted")
    class Completed {

        @Test
        @DisplayName("giao dịch nhỏ chỉ đẩy một thông báo PUSH cho người trả tiền")
        void giaoDichNho() {
            List<NotificationRouter.Target> targets = route("PaymentCompleted", null, 500_000L, 500_000L);

            assertThat(targets).hasSize(1)
                    .extracting(NotificationRouter.Target::customerId,
                            NotificationRouter.Target::channel)
                    .containsExactly(Tuple.tuple(CUSTOMER, NotificationOutbox.PUSH));
        }

        @Test
        @DisplayName("R-NOTIF-07: từ 10 triệu trở lên thì gửi thêm SMS")
        void giaoDichLonThemSms() {
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", null, 10_000_000L, 10_000_000L);

            assertThat(targets).hasSize(2)
                    .extracting(NotificationRouter.Target::channel)
                    .containsExactly(NotificationOutbox.PUSH, NotificationOutbox.SMS);
        }

        @Test
        @DisplayName("R-NOTIF-07: dưới ngưỡng đúng 1 đồng thì không gửi SMS")
        void duoiNguongThiKhongSms() {
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", null, 9_999_999L, 9_999_999L);

            assertThat(targets).hasSize(1);
        }

        @Test
        @DisplayName("ngưỡng SMS tính theo số tiền đã quy đổi VND, không theo số tiền gốc")
        void nguongTinhTheoVnd() {
            // 500 USD: số tiền gốc nhỏ nhưng quy đổi ra đã vượt ngưỡng
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", null, 500L, 12_500_000L);

            assertThat(targets).extracting(NotificationRouter.Target::channel)
                    .contains(NotificationOutbox.SMS);
        }

        @Test
        @DisplayName("R-NOTIF-02: chuyển tiền thì người nhận cũng được báo, nội dung khác")
        void nguoiNhanCungDuocBao() {
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", RECEIVER, 3_000_000L, 3_000_000L);

            assertThat(targets).hasSize(2)
                    .extracting(NotificationRouter.Target::customerId)
                    .containsExactly(CUSTOMER, RECEIVER);

            NotificationRouter.Target toReceiver = targets.get(1);
            assertThat(toReceiver.channel()).isEqualTo(NotificationOutbox.PUSH);
            assertThat(toReceiver.message()).contains("nhan").contains(CUSTOMER);
        }

        @ParameterizedTest(name = "người nhận = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("không có người nhận thì không sinh thông báo thừa")
        void khongCoNguoiNhan(String counterparty) {
            assertThat(route("PaymentCompleted", counterparty, 500_000L, 500_000L)).hasSize(1);
        }

        @Test
        @DisplayName("chuyển tiền lớn thì đủ ba thông báo: PUSH + SMS cho người gửi, PUSH cho người nhận")
        void chuyenTienLon() {
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", RECEIVER, 20_000_000L, 20_000_000L);

            assertThat(targets).hasSize(3)
                    .extracting(NotificationRouter.Target::customerId,
                            NotificationRouter.Target::channel)
                    .containsExactly(
                            Tuple.tuple(CUSTOMER, NotificationOutbox.PUSH),
                            Tuple.tuple(CUSTOMER, NotificationOutbox.SMS),
                            Tuple.tuple(RECEIVER, NotificationOutbox.PUSH));
        }

        @Test
        @DisplayName("số tiền được định dạng có dấu phân cách nghìn")
        void dinhDangSoTien() {
            List<NotificationRouter.Target> targets =
                    route("PaymentCompleted", null, 1_234_567L, 1_234_567L);

            assertThat(targets.get(0).message()).contains("1.234.567d");
        }
    }

    @Nested
    @DisplayName("các loại event còn lại")
    class CacLoaiKhac {

        @Test
        @DisplayName("PaymentFailed chỉ PUSH và nêu lý do thất bại")
        void thatBai() {
            List<NotificationRouter.Target> targets = router.route("PaymentFailed", CUSTOMER, null,
                    500_000L, 500_000L, "INSUFFICIENT_FUNDS");

            assertThat(targets).hasSize(1);
            assertThat(targets.get(0).channel()).isEqualTo(NotificationOutbox.PUSH);
            assertThat(targets.get(0).message()).contains("INSUFFICIENT_FUNDS");
        }

        @ParameterizedTest(name = "lý do = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        @DisplayName("PaymentFailed không rõ lý do thì vẫn gửi được thông báo")
        void thatBaiKhongRoLyDo(String reasonCode) {
            List<NotificationRouter.Target> targets = router.route("PaymentFailed", CUSTOMER, null,
                    500_000L, 500_000L, reasonCode);

            assertThat(targets.get(0).message()).contains("KHONG RO");
        }

        @Test
        @DisplayName("PaymentHeld báo cả PUSH lẫn EMAIL vì khách phải chờ xử lý")
        void treoChoDuyet() {
            List<NotificationRouter.Target> targets =
                    route("PaymentHeld", null, 25_000_000L, 25_000_000L);

            assertThat(targets).hasSize(2)
                    .extracting(NotificationRouter.Target::channel)
                    .containsExactly(NotificationOutbox.PUSH, NotificationOutbox.EMAIL);
            assertThat(targets).allMatch(t -> CUSTOMER.equals(t.customerId()));
        }

        @Test
        @DisplayName("PaymentRefunded báo PUSH và SMS vì liên quan tới tiền về ví")
        void hoanTien() {
            List<NotificationRouter.Target> targets =
                    route("PaymentRefunded", null, 450_000L, 450_000L);

            assertThat(targets).hasSize(2)
                    .extracting(NotificationRouter.Target::channel)
                    .containsExactly(NotificationOutbox.PUSH, NotificationOutbox.SMS);
            assertThat(targets.get(0).message()).contains("hoan");
        }

        @Test
        @DisplayName("hoàn tiền không phụ thuộc ngưỡng SMS — số nhỏ vẫn có SMS")
        void hoanTienNhoVanCoSms() {
            assertThat(route("PaymentRefunded", null, 10_000L, 10_000L)).hasSize(2);
        }

        @Test
        @DisplayName("eventType lạ thì không gửi gì cả")
        void eventTypeLa() {
            assertThat(route("PaymentKhongBiet", RECEIVER, 500_000L, 500_000L)).isEmpty();
        }
    }
}
