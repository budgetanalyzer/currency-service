package com.bleurubin.budgetanalyzer.currency.config;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "budget-analyzer")
@Validated
public record CurrencyServiceProperties(@Valid Map<String, CsvConfig> csvConfigMap) {}
