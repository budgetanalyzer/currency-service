package org.budgetanalyzer.currency.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.service.security.test.JwtTestBuilder;

/**
 * Integration tests for {@link CurrencySeriesController}.
 *
 * <p>Tests all endpoints for currency series management:
 *
 * <ul>
 *   <li>GET /v1/currencies - Get all currency series with optional enabledOnly filter
 *   <li>POST /v1/currencies - Create new currency series
 *   <li>GET /v1/currencies/{id} - Get currency series by ID
 *   <li>PUT /v1/currencies/{id} - Update currency series
 * </ul>
 *
 * <p>These are full integration tests covering HTTP layer → Controller → Service → Repository →
 * Database using real PostgreSQL via TestContainers and WireMock for FRED API mocking.
 */
class CurrencySeriesControllerTest extends AbstractControllerTest {

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @BeforeEach
  void setUp() {
    currencySeriesRepository.deleteAll();
    setCustomJwt(currenciesReadJwt());
  }

  // ===========================================================================================
  // JWT Helpers
  // ===========================================================================================

  private static Jwt currenciesReadJwt() {
    return JwtTestBuilder.user("usr_reader").withPermissions("currencies:read").build();
  }

  private static Jwt currenciesWriteJwt() {
    return JwtTestBuilder.user("usr_writer")
        .withPermissions("currencies:read", "currencies:write")
        .build();
  }

  private static Jwt noPermissionsJwt() {
    return JwtTestBuilder.user("usr_noperms").withPermissions("transactions:read").build();
  }

  // ===========================================================================================
  // A. Authorization Tests
  // ===========================================================================================

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToGetCurrencies() throws Exception {
    performGetUnauthenticated("/v1/currencies").andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToCreateCurrency() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPostUnauthenticated("/v1/currencies", requestJson).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToGetCurrencyById() throws Exception {
    performGetUnauthenticated("/v1/currencies/{id}", 1L).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn401WhenUnauthenticatedUserTriesToUpdateCurrency() throws Exception {
    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    performPutUnauthenticated("/v1/currencies/1", requestJson).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturn403WhenUserWithoutCurrenciesReadTriesToGetCurrencies() throws Exception {
    performGetWithJwt("/v1/currencies", noPermissionsJwt()).andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn403WhenUserWithoutCurrenciesReadTriesToGetCurrencyById() throws Exception {
    performGetWithJwt("/v1/currencies/{id}", noPermissionsJwt(), 1L)
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn403WhenUserWithOnlyReadTriesToCreateCurrency() throws Exception {
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPostWithJwt("/v1/currencies", requestJson, currenciesReadJwt())
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturn403WhenUserWithOnlyReadTriesToUpdateCurrency() throws Exception {
    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    performPutWithJwt("/v1/currencies/1", requestJson, currenciesReadJwt())
        .andExpect(status().isForbidden());
  }

  // ===========================================================================================
  // B. GET /v1/currencies - Happy Path Tests
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoCurrencies() throws Exception {
    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldReturnAllCurrencies() throws Exception {
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

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
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

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
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB", "GBP")));
  }

  @Test
  void shouldReturnAllCurrenciesWhenEnabledOnlyIsFalse() throws Exception {
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    performGet("/v1/currencies?enabledOnly=false")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(3));
  }

  // ===========================================================================================
  // C. GET /v1/currencies - Edge Cases
  // ===========================================================================================

  @Test
  void shouldReturnEmptyListWhenNoEnabledCurrencies() throws Exception {
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    var jpy = CurrencySeriesTestBuilder.defaultJpy().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(gbp, jpy));

    performGet("/v1/currencies?enabledOnly=true")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldHandleLargeDataset() throws Exception {
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().build();
    var jpy = CurrencySeriesTestBuilder.defaultJpy().build();
    var cad = CurrencySeriesTestBuilder.defaultCad().build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp, jpy, cad));

    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(
            jsonPath("$[*].currencyCode", containsInAnyOrder("EUR", "THB", "GBP", "JPY", "CAD")));
  }

  // ===========================================================================================
  // D. GET /v1/currencies - Response Structure Validation
  // ===========================================================================================

  @Test
  void shouldReturnCorrectResponseStructure() throws Exception {
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(eur);

    performGet("/v1/currencies")
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").isNumber())
        .andExpect(jsonPath("$[0].currencyCode").isString())
        .andExpect(jsonPath("$[0].providerSeriesId").isString())
        .andExpect(jsonPath("$[0].enabled").isBoolean())
        .andExpect(jsonPath("$[0].createdAt").isString())
        .andExpect(jsonPath("$[0].updatedAt").isString())
        .andExpect(jsonPath("$[0].currencyCode").value("EUR"))
        .andExpect(jsonPath("$[0].providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$[0].enabled").value(true));
  }

  // ===========================================================================================
  // E. GET /v1/currencies - Query Parameter Validation
  // ===========================================================================================

  @Test
  void shouldAcceptBooleanTrueValues() throws Exception {
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

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
    var eur = CurrencySeriesTestBuilder.defaultEur().build();
    var thb = CurrencySeriesTestBuilder.defaultThb().build();
    var gbp = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();
    currencySeriesRepository.saveAll(List.of(eur, thb, gbp));

    performGet("/v1/currencies?enabledOnly=false")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    performGet("/v1/currencies?enabledOnly=FALSE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  // ===========================================================================================
  // F. POST /v1/currencies - Success Cases
  // ===========================================================================================

  @Test
  void shouldCreateCurrencySeriesSuccessfully() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);

    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.providerSeriesId").value("DEXUSEU"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.updatedAt").isString());

    var saved = currencySeriesRepository.findAll();
    assert saved.size() == 1;
    assert saved.get(0).getCurrencyCode().equals("EUR");
  }

  @Test
  void shouldCreateCurrencySeriesWithEnabledFalse() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    var requestJson =
        """
        {
          "currencyCode": "GBP",
          "providerSeriesId": "DEXUSUK",
          "enabled": false
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.enabled").value(false));

    var saved = currencySeriesRepository.findAll();
    assert saved.size() == 1;
    assert !saved.get(0).isEnabled();
  }

  // ===========================================================================================
  // G. POST /v1/currencies - Validation Errors (400 Bad Request)
  // ===========================================================================================

  @Test
  void shouldReturn400WhenCurrencyCodeIsMissing() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenCurrencyCodeIsBlank() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenCurrencyCodeIsTooShort() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "US",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenCurrencyCodeIsTooLong() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "USDA",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenCurrencyCodeIsLowercase() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "eur",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenCurrencyCodeContainsNumbers() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "US1",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
  }

  @Test
  void shouldReturn400WhenProviderSeriesIdIsMissing() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("providerSeriesId"));
  }

  @Test
  void shouldReturn400WhenProviderSeriesIdIsBlank() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("providerSeriesId"));
  }

  // ===========================================================================================
  // H. POST /v1/currencies - Business Errors (422 Unprocessable Entity)
  // ===========================================================================================

  @Test
  void shouldReturn422WhenCurrencyCodeAlreadyExists() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var existing = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(existing);

    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);

    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("DUPLICATE_CURRENCY_CODE")));
  }

  @Test
  void shouldReturn422ForInvalidIso4217Code() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "currencyCode": "QQQ",
          "providerSeriesId": "DEXUSEU",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("INVALID_ISO_4217_CODE")));
  }

  @Test
  void shouldReturn422ForInvalidProviderSeriesId() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    FredApiStubs.stubSeriesExistsNotFound(TestConstants.FRED_SERIES_INVALID);

    var requestJson =
        """
        {
          "currencyCode": "EUR",
          "providerSeriesId": "INVALID_SERIES_ID",
          "enabled": true
        }
        """;

    performPost("/v1/currencies", requestJson)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
        .andExpect(jsonPath("$.code").value(containsString("INVALID_PROVIDER_SERIES_ID")));
  }

  // ===========================================================================================
  // I. GET /v1/currencies/{id} - Success Cases
  // ===========================================================================================

  @Test
  void shouldReturnCurrencySeriesById() throws Exception {
    var saved = currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultEur().build());

    performGet("/v1/currencies/{id}", saved.getId())
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
  void shouldReturnDisabledCurrencySeries() throws Exception {
    var saved =
        currencySeriesRepository.save(
            CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    performGet("/v1/currencies/{id}", saved.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("GBP"))
        .andExpect(jsonPath("$.enabled").value(false));
  }

  // ===========================================================================================
  // J. GET /v1/currencies/{id} - Not Found (404)
  // ===========================================================================================

  @Test
  void shouldReturn404ForNonExistentId() throws Exception {
    performGet("/v1/currencies/{id}", 999L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  @Test
  void shouldReturn404WhenDatabaseIsEmpty() throws Exception {
    performGet("/v1/currencies/{id}", 1L)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  // ===========================================================================================
  // K. PUT /v1/currencies/{id} - Update Success
  // ===========================================================================================

  @Test
  void shouldEnableCurrencySeries() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var saved =
        currencySeriesRepository.save(
            CurrencySeriesTestBuilder.defaultEur().enabled(false).build());
    final var originalUpdatedAt = saved.getUpdatedAt();

    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    Thread.sleep(10);

    performPut("/v1/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.enabled").value(true));

    var updated = currencySeriesRepository.findById(saved.getId()).orElseThrow();
    assert updated.isEnabled();
    assert updated.getUpdatedAt().isAfter(originalUpdatedAt);
  }

  @Test
  void shouldDisableCurrencySeries() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var saved =
        currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultThb().enabled(true).build());

    var requestJson =
        """
        {
          "enabled": false
        }
        """;

    performPut("/v1/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(saved.getId()))
        .andExpect(jsonPath("$.currencyCode").value("THB"))
        .andExpect(jsonPath("$.enabled").value(false));

    var updated = currencySeriesRepository.findById(saved.getId()).orElseThrow();
    assert !updated.isEnabled();
  }

  @Test
  void shouldPreserveImmutableFields() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var saved = currencySeriesRepository.save(CurrencySeriesTestBuilder.defaultGbp().build());
    currencySeriesRepository.flush();
    var originalCurrencyCode = saved.getCurrencyCode();
    var originalProviderSeriesId = saved.getProviderSeriesId();
    final var originalCreatedAt = saved.getCreatedAt();

    var requestJson =
        """
        {
          "enabled": false
        }
        """;

    performPut("/v1/currencies/" + saved.getId(), requestJson)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencyCode").value(originalCurrencyCode))
        .andExpect(jsonPath("$.providerSeriesId").value(originalProviderSeriesId));

    var updated = currencySeriesRepository.findById(saved.getId()).orElseThrow();
    assert updated.getCurrencyCode().equals(originalCurrencyCode);
    assert updated.getProviderSeriesId().equals(originalProviderSeriesId);
    assert updated.getCreatedAt() != null;
    assert originalCreatedAt != null;
    assert Math.abs(Duration.between(updated.getCreatedAt(), originalCreatedAt).toMillis()) < 1000;
  }

  // ===========================================================================================
  // L. PUT /v1/currencies/{id} - Not Found (404)
  // ===========================================================================================

  @Test
  void shouldReturn404WhenUpdatingNonExistentId() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    var requestJson =
        """
        {
          "enabled": true
        }
        """;

    performPut("/v1/currencies/999", requestJson)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(containsString("NOT_FOUND")));
  }

  // ===========================================================================================
  // M. ISO 4217 Validation Tests
  // ===========================================================================================

  @Test
  void shouldAcceptValidIso4217Codes() throws Exception {
    setCustomJwt(currenciesWriteJwt());
    String[] validCodes = {"EUR", "GBP", "JPY", "CHF", "CAD", "AUD"};
    String[] validSeriesIds = {"DEXUSEU", "DEXUSUK", "DEXJPUS", "DEXSZUS", "DEXCAUS", "DEXUSAL"};

    for (int i = 0; i < validCodes.length; i++) {
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

      performPost("/v1/currencies", requestJson).andExpect(status().isCreated());
    }

    assert currencySeriesRepository.count() == validCodes.length;
  }

  @Test
  void shouldRejectInvalidIso4217Codes() throws Exception {
    setCustomJwt(currenciesWriteJwt());
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

      performPost("/v1/currencies", requestJson)
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.type").value(containsString("APPLICATION_ERROR")))
          .andExpect(jsonPath("$.code").value(containsString("INVALID_ISO_4217_CODE")));
    }

    assert currencySeriesRepository.count() == 0;
  }
}
