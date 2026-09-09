package com.ewallet.thirdparty.repo;

import com.ewallet.thirdparty.entity.PartnerTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerTransactionRepository extends JpaRepository<PartnerTransaction, UUID> {

    List<PartnerTransaction> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    Optional<PartnerTransaction> findFirstByPartnerRef(String partnerRef);
}
