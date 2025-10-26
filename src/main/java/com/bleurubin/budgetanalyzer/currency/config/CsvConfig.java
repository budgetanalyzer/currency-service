package com.bleurubin.budgetanalyzer.currency.config;

import jakarta.validation.constraints.NotBlank;

public record CsvConfig(
    @NotBlank String bankName,
    // defaultCurrencyCode- can override per account basis in future
    @NotBlank String defaultCurrencyIsoCode,
    @NotBlank String creditHeader,
    @NotBlank String dateHeader,
    @NotBlank String dateFormat,
    @NotBlank String debitHeader,
    @NotBlank String descriptionHeader,
    String typeHeader) {}
