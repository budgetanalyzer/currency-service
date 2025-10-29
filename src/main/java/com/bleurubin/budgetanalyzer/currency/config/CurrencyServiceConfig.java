package com.bleurubin.budgetanalyzer.currency.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan({"com.bleurubin.core.csv", "com.bleurubin.service.api"})
public class CurrencyServiceConfig {}
