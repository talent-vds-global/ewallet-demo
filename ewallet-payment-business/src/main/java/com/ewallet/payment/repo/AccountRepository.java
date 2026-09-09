package com.ewallet.payment.repo;

import com.ewallet.payment.entity.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Ví của khách (mỗi khách demo có đúng 1 ví WALLET). */
    @Query("SELECT a FROM Account a WHERE a.customerId = :customerId AND a.accountType = 'WALLET'")
    Optional<Account> findWalletByCustomerId(@Param("customerId") String customerId);

    /** Tài khoản hệ thống theo loại: SYSTEM_SUSPENSE, SYSTEM_FEE, PARTNER_SETTLE. */
    @Query("SELECT a FROM Account a WHERE a.accountType = :type AND a.customerId = :owner")
    Optional<Account> findSystemAccount(@Param("type") String type, @Param("owner") String owner);
}
