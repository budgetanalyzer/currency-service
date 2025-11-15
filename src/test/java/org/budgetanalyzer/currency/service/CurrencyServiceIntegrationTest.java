package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.exception.ServiceUnavailableException;

/**
 * Integration tests for {@link CurrencyService}.
 *
 * <p>Tests all currency service operations including:
 *
 * <ul>
 *   <li>Create operations (happy paths and validation)
 *   <li>Read operations (get by ID, get all with filters)
 *   <li>Update operations (enable/disable)
 *   <li>Provider integration (validation)
 * </ul>
 *
 * <p>Uses modern Spring Boot 3.5.7 testing patterns:
 *
 * <ul>
 *   <li>WireMock server for stubbing FRED API responses
 *   <li>@ServiceConnection for TestContainers (Spring Boot 3.1+)
 *   <li>AssertJ fluent assertions
 * </ul>
 */
class CurrencyServiceIntegrationTest extends AbstractWireMockTest {

  @Autowired private CurrencyService currencyService;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private WireMockServer wireMockServer;

  // ===========================================================================================
  // A. Create Operations - Happy Paths
  // ===========================================================================================

  @Test
  void createValidCurrencySeriesSavesEntity() {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = currencyService.create(series);

    // Assert
    assertThat(created.getId()).isNotNull();
    assertThat(created.getCurrencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(created.getProviderSeriesId()).isEqualTo(TestConstants.FRED_SERIES_EUR);
    assertThat(created.isEnabled()).isTrue();
    assertThat(created.getCreatedAt()).isNotNull();
    assertThat(created.getUpdatedAt()).isNotNull();
  }

  // ===========================================================================================
  // B. Create Operations - Validation
  // ===========================================================================================

  @Test
  void createWithTooShortCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_TOO_SHORT)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  void createWithTooLongCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_TOO_LONG)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  void createWithLowercaseCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_LOWERCASE)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  void createWithNumbersInCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_WITH_NUMBERS)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  void createWithNonExistentCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_ZZZ)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  void createDuplicateCurrencyCodeThrowsException() {
    // Arrange - Stub FRED API responses and create first EUR series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series1 = CurrencySeriesTestBuilder.defaultEur().build();
    currencyService.create(series1);

    // Try to create another EUR series with different provider ID
    FredApiStubs.stubSeriesExistsSuccess("DIFFERENT_SERIES");
    var series2 =
        CurrencySeriesTestBuilder.defaultEur().withProviderSeriesId("DIFFERENT_SERIES").build();

    // Act & Assert
    assertThatThrownBy(() -> currencyService.create(series2))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("already exists");
  }

  // ===========================================================================================
  // C. Provider Integration
  // ===========================================================================================

  @Test
  void createSucceedsWhenProviderReturnsTrue() {
    // Arrange - Stub FRED API to return series exists
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = currencyService.create(series);

    // Assert
    assertThat(created.getId()).isNotNull();
  }

  @Test
  void createFailsWhenProviderReturnsFalse() {
    // Arrange - Stub FRED API to return 404
    FredApiStubs.stubSeriesExistsNotFound(TestConstants.FRED_SERIES_INVALID);
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withProviderSeriesId(TestConstants.FRED_SERIES_INVALID)
            .build();

    // Act & Assert
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("does not exist in the external provider");
  }

  @Test
  void createThrowsServiceUnavailableWhenProviderFails() {
    // Arrange - Stub FRED API to return 500
    FredApiStubs.stubSeriesExistsServerError(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act & Assert
    assertThatThrownBy(() -> currencyService.create(series))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("Unable to validate provider series ID");
  }

  // ===========================================================================================
  // D. Read Operations
  // ===========================================================================================

  @Test
  void getByIdReturnsCorrectSeries() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = currencyService.create(series);

    // Act
    var retrieved = currencyService.getById(created.getId());

    // Assert
    assertThat(retrieved.getId()).isEqualTo(created.getId());
    assertThat(retrieved.getCurrencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(retrieved.getProviderSeriesId()).isEqualTo(TestConstants.FRED_SERIES_EUR);
    assertThat(retrieved.isEnabled()).isTrue();
  }

  @Test
  void getByIdWithNonExistentIdThrowsException() {
    // Act & Assert
    assertThatThrownBy(() -> currencyService.getById(999L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void getAllWithoutFilterReturnsAllSeries() {
    // Arrange - Stub FRED API responses and create 3 series (2 enabled, 1 disabled)
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    currencyService.create(CurrencySeriesTestBuilder.defaultEur().build());
    currencyService.create(CurrencySeriesTestBuilder.defaultThb().build());
    currencyService.create(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Act
    var allSeries = currencyService.getAll(false);

    // Assert
    assertThat(allSeries).hasSize(3);
  }

  @Test
  void getAllWithEnabledFilterReturnsOnlyEnabledSeries() {
    // Arrange - Stub FRED API responses and create 3 series (2 enabled, 1 disabled)
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    currencyService.create(CurrencySeriesTestBuilder.defaultEur().build());
    currencyService.create(CurrencySeriesTestBuilder.defaultThb().build());
    currencyService.create(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Act
    var enabledSeries = currencyService.getAll(true);

    // Assert
    assertThat(enabledSeries).hasSize(2);
    assertThat(enabledSeries).allMatch(series -> series.isEnabled());
  }

  // ===========================================================================================
  // E. Update Operations
  // ===========================================================================================

  @Test
  void updateEnabledTrueToFalseUpdatesDatabase() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = currencyService.create(series);

    // Act
    var updated = currencyService.update(created.getId(), false);

    // Assert
    assertThat(updated.isEnabled()).isFalse();
    assertThat(updated.getUpdatedAt()).isAfter(created.getUpdatedAt());

    // Verify database
    var retrieved = currencyService.getById(created.getId());
    assertThat(retrieved.isEnabled()).isFalse();
  }

  @Test
  void updateEnabledFalseToTrueUpdatesDatabase() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();
    var created = currencyService.create(series);

    // Act
    var updated = currencyService.update(created.getId(), true);

    // Assert
    assertThat(updated.isEnabled()).isTrue();

    // Verify database
    var retrieved = currencyService.getById(created.getId());
    assertThat(retrieved.isEnabled()).isTrue();
  }

  @Test
  void updateNonExistentSeriesThrowsException() {
    // Act & Assert
    assertThatThrownBy(() -> currencyService.update(999L, true))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void updateChangesUpdatedAtTimestamp() throws InterruptedException {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = currencyService.create(series);
    var originalUpdatedAt = created.getUpdatedAt();

    // Sleep to ensure timestamp difference
    Thread.sleep(100);

    // Act
    var updated = currencyService.update(created.getId(), false);

    // Assert
    assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
  }
}
