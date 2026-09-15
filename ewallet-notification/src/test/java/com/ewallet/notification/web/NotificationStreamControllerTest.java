package com.ewallet.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.notification.entity.NotificationOutbox;
import com.ewallet.notification.entity.NotificationSentLog;
import com.ewallet.notification.repo.NotificationOutboxRepository;
import com.ewallet.notification.repo.NotificationSentLogRepository;
import com.ewallet.notification.service.SseHub;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** API thông báo (docs/specs/01-api-contracts.md §10): SSE realtime và danh sách đã sinh. */
@ExtendWith(MockitoExtension.class)
class NotificationStreamControllerTest {

    private static final String CUSTOMER = "CUST-001";

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private NotificationSentLogRepository sentLogRepository;
    @Mock private SseHub sseHub;

    @InjectMocks
    private NotificationStreamController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static NotificationOutbox outbox(UUID id, String channel, String status) {
        NotificationOutbox o = new NotificationOutbox();
        o.setId(id);
        o.setEventId(UUID.randomUUID());
        o.setEventType("PaymentCompleted");
        o.setCustomerId(CUSTOMER);
        o.setChannel(channel);
        o.setStatus(status);
        o.setPayload("Giao dich thanh cong 500.000d");
        o.setOrderId(UUID.randomUUID());
        o.setAttempt(1);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    @Test
    @DisplayName("GET /api/notifications/ping báo số dòng outbox và số kết nối SSE đang mở")
    void ping() throws Exception {
        when(outboxRepository.count()).thenReturn(12L);
        when(sseHub.count()).thenReturn(2);

        mockMvc.perform(get("/api/notifications/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("ewallet-notification"))
                .andExpect(jsonPath("$.outboxRows").value(12))
                .andExpect(jsonPath("$.sseConnections").value(2));
    }

    @Nested
    @DisplayName("GET /api/notifications/stream — kết nối dài")
    class Stream {

        @Test
        @DisplayName("đăng ký theo customerId thì hub giữ kết nối cho đúng khách đó")
        void dangKyTheoKhach() {
            when(sseHub.subscribe(CUSTOMER)).thenReturn(new SseEmitter(0L));

            assertThat(controller.stream(CUSTOMER)).isNotNull();
            verify(sseHub).subscribe(CUSTOMER);
        }

        @Test
        @DisplayName("không ghi customerId thì nghe kênh chung ALL")
        void nghieKenhChung() {
            when(sseHub.subscribe("ALL")).thenReturn(new SseEmitter(0L));

            controller.stream("");

            verify(sseHub).subscribe("ALL");
        }

        @Test
        @DisplayName("customerId chỉ có khoảng trắng cũng coi là kênh chung")
        void customerIdRong() {
            when(sseHub.subscribe("ALL")).thenReturn(new SseEmitter(0L));

            controller.stream("   ");

            verify(sseHub).subscribe("ALL");
        }
    }

    @Nested
    @DisplayName("GET /api/notifications — danh sách đã sinh")
    class Danhsach {

        @Test
        @DisplayName("trả thông báo kèm lịch sử từng lần gửi")
        void traKemLichSuGui() throws Exception {
            UUID outboxId = UUID.randomUUID();
            when(outboxRepository.findByCustomerIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                    .thenReturn(List.of(outbox(outboxId, NotificationOutbox.PUSH, NotificationOutbox.SENT)));
            when(sentLogRepository.findByOutboxIdInOrderByIdAsc(List.of(outboxId))).thenReturn(List.of(
                    NotificationSentLog.of(outboxId, 1, NotificationSentLog.FAILED),
                    NotificationSentLog.of(outboxId, 2, NotificationSentLog.SENT)));

            mockMvc.perform(get("/api/notifications").param("customerId", CUSTOMER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value(CUSTOMER))
                    .andExpect(jsonPath("$.count").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(outboxId.toString()))
                    .andExpect(jsonPath("$.items[0].channel").value(NotificationOutbox.PUSH))
                    .andExpect(jsonPath("$.items[0].status").value(NotificationOutbox.SENT))
                    .andExpect(jsonPath("$.items[0].message").value("Giao dich thanh cong 500.000d"))
                    .andExpect(jsonPath("$.items[0].attempts.length()").value(2))
                    .andExpect(jsonPath("$.items[0].attempts[1].result").value(NotificationSentLog.SENT));
        }

        @Test
        @DisplayName("khách chưa có thông báo nào thì trả danh sách rỗng, không hỏi bảng log")
        void chuaCoThongBao() throws Exception {
            when(outboxRepository.findByCustomerIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/notifications").param("customerId", "CUST-999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));

            verify(sentLogRepository, never()).findByOutboxIdInOrderByIdAsc(any());
        }

        @Test
        @DisplayName("thông báo chưa gửi lần nào thì danh sách lần thử để rỗng")
        void chuaGuiLanNao() throws Exception {
            UUID outboxId = UUID.randomUUID();
            when(outboxRepository.findByCustomerIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                    .thenReturn(List.of(outbox(outboxId, NotificationOutbox.SMS, NotificationOutbox.PENDING)));
            when(sentLogRepository.findByOutboxIdInOrderByIdAsc(any())).thenReturn(List.of());

            mockMvc.perform(get("/api/notifications").param("customerId", CUSTOMER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].attempts.length()").value(0));
        }

        @ParameterizedTest(name = "limit={0} -> lấy {1} bản ghi")
        @CsvSource({
                "5,    5",
                "100,  100",
                "500,  100",     // kẹp trần ở 100
                "0,    20",      // không hợp lệ -> mặc định
                "-1,   20"
        })
        @DisplayName("limit mặc định 20, trần 100")
        void chuanHoaLimit(int limit, int expectedSize) throws Exception {
            when(outboxRepository.findByCustomerIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/notifications")
                            .param("customerId", CUSTOMER)
                            .param("limit", String.valueOf(limit)))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(outboxRepository).findByCustomerIdOrderByCreatedAtDesc(anyString(), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(expectedSize);
        }

        @Test
        @DisplayName("thiếu customerId thì trả 400 vì đó là tham số bắt buộc")
        void thieuCustomerId() throws Exception {
            mockMvc.perform(get("/api/notifications"))
                    .andExpect(status().isBadRequest());
        }
    }
}
