package com.bleurubin.budgetanalyzer.currency.service;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error codes for API responses")
public enum CurrencyServiceError {
  @Schema(description = "Error encountered parsing csv file")
  CSV_PARSING_ERROR
}
