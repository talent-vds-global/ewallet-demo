package com.ewallet.payment.repo;

import com.ewallet.payment.entity.PaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /** Giao dịch gốc của một order (không tính bút toán bù trừ). */
    Optional<PaymentTransaction> findFirstByOrderIdAndReversedTxnIdIsNullOrderByCreatedAtAsc(UUID orderId);
}
