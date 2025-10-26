package com.bleurubin.budgetanalyzer.currency;

import com.bleurubin.budgetanalyzer.currency.config.CurrencyServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CurrencyServiceProperties.class)
public class CurrencyServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CurrencyServiceApplication.class, args);
  }
}
