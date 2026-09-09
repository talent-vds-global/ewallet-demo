package com.ewallet.payment.repo;

import com.ewallet.payment.entity.AccountBalance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    /**
     * Khoá bi quan khi cập nhật số dư. R-P2P-07: gọi theo thứ tự account_id tăng dần
     * để hai giao dịch chéo nhau không deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AccountBalance b WHERE b.accountId = :id")
    Optional<AccountBalance> findForUpdate(@Param("id") UUID id);
}
