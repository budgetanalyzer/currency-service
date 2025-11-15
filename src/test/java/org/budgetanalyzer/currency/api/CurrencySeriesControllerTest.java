package org.budgetanalyzer.currency.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;

/**
 * Integration tests for {@link CurrencySeriesController}.
 *
 * <p>Tests the read-only public endpoint for retrieving currency series:
 *
 * <ul>
 *   <li>GET /v1/currencies - Get all currency series with optional enabledOnly filter
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Happy paths (all currencies, enabled only filtering)
 *   <li>Edge cases (empty results, large datasets)
 *   <li>Response structure validation (JSON schema, data types)
 *   <li>Query parameter handling (boolean value variations)
 * </ul>
 */
class CurrencySeriesControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @BeforeEach
  void setUp() {
    currencySeriesRepository.deleteAll();
  }

  // ===========================================================================================
  // A. Happy Path Tests
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoCurrencies() throws Exception {
    // Execute
    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldReturnAllCurrencies() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Execute
    performGet("/v1/currencies?enabledOnly=false")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB", "GBP")))
        .andExpect(jsonPath("$[*].id").isNotEmpty())
        .andExpect(jsonPath("$[*].providerSeriesId").isNotEmpty())
        .andExpect(jsonPath("$[*].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[*].updatedAt").isNotEmpty());
  }

  @Test
  void shouldReturnOnlyEnabledCurrencies() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Execute
    performGet("/v1/currencies?enabledOnly=true")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[*].enabled").value(everyItem(is(true))))
        .andExpect(jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB")));
  }

  @Test
  void shouldReturnAllCurrenciesWhenEnabledOnlyIsNotProvided() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Execute (no query parameter - should default to enabledOnly=false)
    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB", "GBP")));
  }

  @Test
  void shouldReturnAllCurrenciesWhenEnabledOnlyIsFalse() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Execute
    performGet("/v1/currencies?enabledOnly=false")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3));
  }

  // ===========================================================================================
  // B. Edge Cases
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoEnabledCurrencies() throws Exception {
    // Setup: Save 2 disabled currencies only
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    var jpy = CurrencySeriesTestBuilder.defaultJpy().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(gbp, jpy));

    // Execute
    performGet("/v1/currencies?enabledOnly=true")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldHandleLargeDataset() throws Exception {
    // Setup: Save 5 currencies
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().build();
    var jpy = CurrencySeriesTestBuilder.defaultJpy().build();
    var cad = CurrencySeriesTestBuilder.defaultCad().build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp, jpy, cad));

    // Execute
    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(
            jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB", "GBP", "JPY", "CAD")));
  }

  // ===========================================================================================
  // C. Response Structure Validation
  // ===========================================================================================

  @Test
  void shouldReturnCorrectResponseStructure() throws Exception {
    // Setup: Save 1 currency
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eur);

    // Execute
    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        // Validate data types and structure
        .andExpect(jsonPath("$[0].id").isNumber())
        .andExpect(jsonPath("$[0].currencyCode").isString())
        .andExpect(jsonPath("$[0].providerSeriesId").isString())
        .andExpect(jsonPath("$[0].enabled").isBoolean())
        .andExpect(jsonPath("$[0].createdAt").isString())
        .andExpect(jsonPath("$[0].updatedAt").isString())
        // Validate actual values
        .andExpect(jsonPath("$[0].currencyCode").value("EUR"))
        .andExpect(jsonPath("$[0].providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$[0].enabled").value(true));
  }

  // ===========================================================================================
  // D. Query Parameter Validation
  // ===========================================================================================

  @Test
  void shouldAcceptBooleanTrueValues() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Test various true values
    performGet("/v1/currencies?enabledOnly=true")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[*].enabled").value(everyItem(is(true))));

    performGet("/v1/currencies?enabledOnly=TRUE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[*].enabled").value(everyItem(is(true))));
  }

  @Test
  void shouldAcceptBooleanFalseValues() throws Exception {
    // Setup: Save 3 currencies (2 enabled, 1 disabled)
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    // Test various false values
    performGet("/v1/currencies?enabledOnly=false")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    performGet("/v1/currencies?enabledOnly=FALSE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }
}
