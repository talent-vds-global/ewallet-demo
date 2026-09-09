package com.ewallet.payment.repo;

import com.ewallet.payment.entity.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, String> { }
