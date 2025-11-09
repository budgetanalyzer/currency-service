package com.bleurubin.budgetanalyzer.currency.api.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request DTO for updating an existing currency series.
 *
 * <p>Note: Currency code and provider series ID are immutable and cannot be changed after creation.
 * Only the enabled status can be updated.
 */
@Schema(
    description =
        "Request to update an existing currency series "
            + "(currency code and provider series ID are immutable)")
public record CurrencySeriesUpdateRequest(
    @Schema(
            description = "Whether this currency is enabled for exchange rate access",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        boolean enabled) {}
