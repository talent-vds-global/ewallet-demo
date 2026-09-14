package com.ewallet.notification.repo;

import com.ewallet.notification.entity.NotificationSentLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSentLogRepository extends JpaRepository<NotificationSentLog, Long> {

    List<NotificationSentLog> findByOutboxIdInOrderByIdAsc(List<UUID> outboxIds);
}
