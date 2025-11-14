package org.budgetanalyzer.currency.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;

/**
 * Integration tests for {@link AdminCurrencySeriesController}.
 *
 * <p>Tests the admin endpoints for currency series management:
 *
 * <ul>
 *   <li>POST /v1/admin/currencies - Create new currency series
 *   <li>GET /v1/admin/currencies/{id} - Get currency series by ID
 *   <li>PUT /v1/admin/currencies/{id} - Update currency series
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers and WireMock for FRED API mocking.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Happy paths (create, read, update operations)
 *   <li>Validation errors (missing/invalid fields, format violations)
 *   <li>Business errors (duplicate currency codes, invalid ISO 4217, invalid FRED series)
 *   <li>Not found scenarios (get/update non-existent resources)
 *   <li>Response structure validation (JSON schema, data types, headers)
 * </ul>
 */
class AdminCurrencySeriesControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository repository;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
  }

  // ===========================================================================================
  // A. POST /v1/admin/currencies - Success Cases
  // ===========================================================================================

  @Test
  @DisplayName("POST /v1/admin/currencies - should create currency series with all fields")
  void shouldCreateCurrencySeriesSuccessfully() throws Exception {
    // Setup: Mock FRED API to validate series exists
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);

    // Prepare request
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    // Execute
    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.updatedAt").isString());

    // Verify database
    var saved = repository.findAll();
    assert saved.size() == 1;
    assert saved.get(0).getCurrencyCode().equals("EUR");
  }

  @Test
  @DisplayName(
      "POST /v1/admin/currencies - should create currency series with enabled=false by default")
  void shouldCreateCurrencySeriesWithEnabledFalse() throws Exception {
    // Setup: Mock FRED API
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    // Prepare request (enabled=false)
    var requestJson =
        """
        {
          "currencyCode": "GBP",
          "providerSeriesId": "DEXUSUK",
          "enabled": false
        }
        """;

    // Execute
    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.enabled").value(false));

    // Verify database
    var saved = repository.findAll();
    assert saved.size() == 1;
    assert !saved.get(0).isEnabled();
  }

  // ===========================================================================================
  // B. POST /v1/admin/currencies - Validation Errors (400 Bad Request)
  // ===========================================================================================

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode is missing")
  void shouldReturn400WhenCurrencyCodeIsMissing() throws Exception {
    var requestJson =
        """
        {
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode is blank")
  void shouldReturn400WhenCurrencyCodeIsBlank() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode is too short")
  void shouldReturn400WhenCurrencyCodeIsTooShort() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "US",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode is too long")
  void shouldReturn400WhenCurrencyCodeIsTooLong() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "USDA",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode is lowercase")
  void shouldReturn400WhenCurrencyCodeIsLowercase() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "eur",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when currencyCode contains numbers")
  void shouldReturn400WhenCurrencyCodeContainsNumbers() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "US1",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when providerSeriesId is missing")
  void shouldReturn400WhenProviderSeriesIdIsMissing() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("providerSeriesId"));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 400 when providerSeriesId is blank")
  void shouldReturn400WhenProviderSeriesIdIsBlank() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "",
          "enabled": true
        }
        """;

    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("providerSeriesId"));
  }

  // ===========================================================================================
  // C. POST /v1/admin/currencies - Business Errors (422 Unprocessable Entity)
  // ===========================================================================================

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 422 when currency code already exists")
  void shouldReturn422WhenCurrencyCodeAlreadyExists() throws Exception {
    // Setup: Create existing currency
    var existing = CurrencySeriesTestBuilder.defaultEur().build();
    repository.save(existing);

    // Mock FRED API (shouldn't be called due to duplicate check)
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);

    // Prepare request with duplicate currency code
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    // Execute
    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("DUPLICATE_CURRENCY_CODE")));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 422 for invalid ISO 4217 code")
  void shouldReturn422ForInvalidIso4217Code() throws Exception {
    // Prepare request with non-existent ISO 4217 code
    // Note: QQQ is not a valid ISO 4217 code
    var requestJson =
        """
        {
          "currencyCode": "QQQ",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    // Execute (no FRED mock needed - fails at ISO validation first)
    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("INVALID_ISO_4217_CODE")));
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should return 422 for invalid provider series ID")
  void shouldReturn422ForInvalidProviderSeriesId() throws Exception {
    // Mock FRED API to return series does not exist
    FredApiStubs.stubSeriesExistsNotFound(TestConstants.FRED_SERIES_INVALID);

    // Prepare request with invalid FRED series ID
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "INVALID_SERIES_ID",
          "enabled": true
        }
        """;

    // Execute
    performPost("/v1/admin/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("INVALID_PROVIDER_SERIES_ID")));
  }

  // ===========================================================================================
  // D. GET /v1/admin/currencies/{id} - Success Cases
  // ===========================================================================================

  @Test
  @DisplayName("GET /v1/admin/currencies/{id} - should return currency series by ID")
  void shouldReturnCurrencySeriesById() throws Exception {
    // Setup: Save currency series
    var saved = repository.save(CurrencySeriesTestBuilder.defaultEur().build());

    // Execute
    performGet("/v1/admin/currencies/{id}", saved.getId())
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.updatedAt").isString());
  }

  @Test
  @DisplayName("GET /v1/admin/currencies/{id} - should return disabled currency series")
  void shouldReturnDisabledCurrencySeries() throws Exception {
    // Setup: Save disabled currency series
    var saved = repository.save(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Execute
    performGet("/v1/admin/currencies/{id}", saved.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.enabled").value(false));
  }

  // ===========================================================================================
  // E. GET /v1/admin/currencies/{id} - Not Found (404)
  // ===========================================================================================

  @Test
  @DisplayName("GET /v1/admin/currencies/{id} - should return 404 for non-existent ID")
  void shouldReturn404ForNonExistentId() throws Exception {
    performGet("/v1/admin/currencies/{id}", 999L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  @Test
  @DisplayName("GET /v1/admin/currencies/{id} - should return 404 when database is empty")
  void shouldReturn404WhenDatabaseIsEmpty() throws Exception {
    performGet("/v1/admin/currencies/{id}", 1L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  // ===========================================================================================
  // F. PUT /v1/admin/currencies/{id} - Update Success
  // ===========================================================================================

  @Test
  @DisplayName("PUT /v1/admin/currencies/{id} - should enable currency series")
  void shouldEnableCurrencySeries() throws Exception {
    // Setup: Save disabled currency series
    var saved = repository.save(CurrencySeriesTestBuilder.defaultEur().enabled(false).build());
    final var originalUpdatedAt = saved.getUpdatedAt();

    // Prepare request to enable
    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    // Wait a moment to ensure updatedAt timestamp changes
    Thread.sleep(10);

    // Execute
    performPut("/v1/admin/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.enabled").value(true));

    // Verify database
    var updated = repository.findById(saved.getId()).orElseThrow();
    assert updated.isEnabled();
    assert updated.getUpdatedAt().isAfter(originalUpdatedAt);
  }

  @Test
  @DisplayName("PUT /v1/admin/currencies/{id} - should disable currency series")
  void shouldDisableCurrencySeries() throws Exception {
    // Setup: Save enabled currency series
    var saved = repository.save(CurrencySeriesTestBuilder.defaultThb().enabled(true).build());

    // Prepare request to disable
    var requestJson =
        """
        {
          "enabled": false
        }
        """;

    // Execute
    performPut("/v1/admin/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("THB"))
        .andExpect(jsonPath("$.enabled").value(false));

    // Verify database
    var updated = repository.findById(saved.getId()).orElseThrow();
    assert !updated.isEnabled();
  }

  @Test
  @DisplayName("PUT /v1/admin/currencies/{id} - should preserve immutable fields")
  void shouldPreserveImmutableFields() throws Exception {
    // Setup: Save currency series
    var saved = repository.save(CurrencySeriesTestBuilder.defaultGbp().build());
    repository.flush();
    var originalCurrencyCode = saved.getCurrencyCode();
    var originalProviderSeriesId = saved.getProviderSeriesId();
    final var originalCreatedAt = saved.getCreatedAt();

    // Prepare request (only enabled can be changed)
    var requestJson =
        """
        {
          "enabled": false
        }
        """;

    // Execute
    performPut("/v1/admin/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencyCode").value(originalCurrencyCode))
        .andExpect(jsonPath("$.providerSeriesId").value(originalProviderSeriesId));

    // Verify database - immutable fields unchanged
    var updated = repository.findById(saved.getId()).orElseThrow();
    assert updated.getCurrencyCode().equals(originalCurrencyCode);
    assert updated.getProviderSeriesId().equals(originalProviderSeriesId);
    // Verify createdAt is not null and hasn't changed significantly (within 1 second)
    assert updated.getCreatedAt() != null;
    assert originalCreatedAt != null;
    assert Math.abs(
            java.time.Duration.between(updated.getCreatedAt(), originalCreatedAt).toMillis())
        < 1000;
  }

  // ===========================================================================================
  // G. PUT /v1/admin/currencies/{id} - Not Found (404)
  // ===========================================================================================

  @Test
  @DisplayName("PUT /v1/admin/currencies/{id} - should return 404 for non-existent ID")
  void shouldReturn404WhenUpdatingNonExistentId() throws Exception {
    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    performPut("/v1/admin/currencies/999", requestJson)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  // ===========================================================================================
  // H. ISO 4217 Validation Tests
  // ===========================================================================================

  @Test
  @DisplayName("POST /v1/admin/currencies - should accept valid ISO 4217 currency codes")
  void shouldAcceptValidIso4217Codes() throws Exception {
    // Test multiple valid ISO 4217 codes
    String[] validCodes = {"EUR", "GBP", "JPY", "CHF", "CAD", "AUD"};
    String[] validSeriesIds = {"DEXUSEU", "DEXUSUK", "DEXJPUS", "DEXSZUS", "DEXCAUS", "DEXUSAL"};

    for (int i = 0; i < validCodes.length; i++) {
      // Mock FRED API
      FredApiStubs.stubSeriesExistsSuccess(validSeriesIds[i]);

      var requestJson =
          String.format(
              """
              {
                "currencyCode": "%s",
                "providerSeriesId": "%s",
                "enabled": true
              }
              """,
              validCodes[i], validSeriesIds[i]);

      // Execute - should succeed
      performPost("/v1/admin/currencies", requestJson).andExpect(status().isCreated());
    }

    // Verify all were created
    assert repository.count() == validCodes.length;
  }

  @Test
  @DisplayName("POST /v1/admin/currencies - should reject invalid ISO 4217 currency codes")
  void shouldRejectInvalidIso4217Codes() throws Exception {
    // Test invalid ISO 4217 codes - these are not recognized by Currency.getInstance()
    String[] invalidCodes = {"QQQ", "ZZZ", "ABC"};

    for (var invalidCode : invalidCodes) {
      var requestJson =
          String.format(
              """
              {
                "currencyCode": "%s",
                "providerSeriesId": "DEXUSEU",
                "enabled": true
              }
              """,
              invalidCode);

      // Execute - should fail
      performPost("/v1/admin/currencies", requestJson)
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
          .andExpect(jsonPath("$.code").value(containsString("INVALID_ISO_4217_CODE")));
    }

    // Verify none were created
    assert repository.count() == 0;
  }
}
