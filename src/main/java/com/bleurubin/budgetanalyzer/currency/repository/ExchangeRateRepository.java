package com.bleurubin.budgetanalyzer.currency.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {}
