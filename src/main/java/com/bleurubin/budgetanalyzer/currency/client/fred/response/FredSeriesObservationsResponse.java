package com.bleurubin.budgetanalyzer.currency.client.fred.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FredSeriesObservationsResponse(
    @JsonProperty("realtime_start") LocalDate realtimeStart,
    @JsonProperty("realtime_end") LocalDate realtimeEnd,
    @JsonProperty("observation_start") LocalDate observationStart,
    @JsonProperty("observation_end") LocalDate observationEnd,
    String units,
    @JsonProperty("output_type") Integer outputType,
    @JsonProperty("file_type") String fileType,
    @JsonProperty("order_by") String orderBy,
    @JsonProperty("sort_order") String sortOrder,
    Integer count,
    Integer offset,
    Integer limit,
    List<Observation> observations) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Observation(
      @JsonProperty("realtime_start") LocalDate realtimeStart,
      @JsonProperty("realtime_end") LocalDate realtimeEnd,
      LocalDate date,
      String value) {

    /** Checks if this observation has valid data. */
    public boolean hasValue() {
      return value != null && !".".equals(value);
    }

    /**
     * Parses the value as a BigDecimal, returning null if the value is "." (missing data
     * indicator).
     */
    public BigDecimal getValueAsBigDecimal() {
      if (!hasValue()) {
        return null;
      }

      try {
        return new BigDecimal(value);
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
