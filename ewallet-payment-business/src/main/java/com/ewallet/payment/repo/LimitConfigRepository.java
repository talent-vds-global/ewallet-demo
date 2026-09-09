package com.ewallet.payment.repo;

import com.ewallet.payment.entity.LimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LimitConfigRepository extends JpaRepository<LimitConfig, String> { }
