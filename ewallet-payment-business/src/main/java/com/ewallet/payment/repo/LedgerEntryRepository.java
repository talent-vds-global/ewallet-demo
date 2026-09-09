package com.ewallet.payment.repo;

import com.ewallet.payment.entity.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTxnIdOrderByIdAsc(UUID txnId);

    List<LedgerEntry> findByTxnIdInOrderByIdAsc(List<UUID> txnIds);
}
