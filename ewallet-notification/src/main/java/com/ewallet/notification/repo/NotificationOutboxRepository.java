package com.ewallet.notification.repo;

import com.ewallet.notification.entity.NotificationOutbox;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    /**
     * R-NOTIF-05: mot event, mot kenh, mot khach hang thi chi sinh mot thong bao.
     * Phai co customer_id vi giao dich P2P bao cho ca hai ben qua cung kenh PUSH (R-NOTIF-02).
     */
    Optional<NotificationOutbox> findByEventIdAndChannelAndCustomerId(
            UUID eventId, String channel, String customerId);

    List<NotificationOutbox> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);
}
