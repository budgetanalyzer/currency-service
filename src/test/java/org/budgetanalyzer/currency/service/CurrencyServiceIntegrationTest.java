package org.budgetanalyzer.currency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.github.tomakehurst.wiremock.WireMockServer;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.domain.event.CurrencyCreatedEvent;
import org.budgetanalyzer.currency.domain.event.CurrencyUpdatedEvent;
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
 *   <li>Event publishing (CurrencyCreatedEvent, CurrencyUpdatedEvent)
 *   <li>Provider integration (validation)
 *   <li>Spring Modulith transactional outbox pattern
 * </ul>
 *
 * <p>Uses modern Spring Boot 3.5.7 testing patterns:
 *
 * <ul>
 *   <li>@RecordApplicationEvents for event capture (official Spring approach)
 *   <li>WireMock server for stubbing FRED API responses
 *   <li>@ServiceConnection for TestContainers (Spring Boot 3.1+)
 *   <li>AssertJ fluent assertions
 * </ul>
 */
@RecordApplicationEvents
class CurrencyServiceIntegrationTest extends AbstractWireMockTest {

  @Autowired private CurrencyService service;

  @Autowired private CurrencySeriesRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private WireMockServer wireMockServer;

  // ===========================================================================================
  // Setup and Cleanup
  // ===========================================================================================

  @BeforeEach
  void setUp() {
    // Clear database tables
    jdbcTemplate.execute("DELETE FROM exchange_rate");
    jdbcTemplate.execute("DELETE FROM currency_series");
    jdbcTemplate.execute("DELETE FROM event_publication");

    // Reset all WireMock stubs
    wireMockServer.resetAll();
  }

  // ===========================================================================================
  // A. Create Operations - Happy Paths
  // ===========================================================================================

  @Test
  @DisplayName("Create valid currency series saves entity with all fields populated")
  void createValidCurrencySeriesSavesEntity() {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = service.create(series);

    // Assert
    assertThat(created.getId()).isNotNull();
    assertThat(created.getCurrencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(created.getProviderSeriesId()).isEqualTo(TestConstants.FRED_SERIES_EUR);
    assertThat(created.isEnabled()).isTrue();
    assertThat(created.getCreatedAt()).isNotNull();
    assertThat(created.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("Create currency publishes CurrencyCreatedEvent with correct data")
  void createCurrencyPublishesEvent(ApplicationEvents events) {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = service.create(series);

    // Assert - Modern event verification
    long eventCount = events.stream(CurrencyCreatedEvent.class).count();
    assertThat(eventCount).isEqualTo(1);

    var event =
        events.stream(CurrencyCreatedEvent.class)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected CurrencyCreatedEvent"));

    assertThat(event.currencySeriesId()).isEqualTo(created.getId());
    assertThat(event.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(event.enabled()).isTrue();
    assertThat(event.correlationId()).isNull(); // MDC not set in test context
  }

  @Test
  @DisplayName("Create currency persists event to Spring Modulith outbox table")
  void createCurrencyPersistsEventToOutbox() {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Get initial count
    var initialCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE ?",
            Long.class,
            "%CurrencyCreatedEvent");

    // Act
    service.create(series);

    // Assert - Query event_publication table (includes both pending and completed events)
    var finalCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE ?",
            Long.class,
            "%CurrencyCreatedEvent");

    assertThat(finalCount).isGreaterThan(initialCount);
  }

  // ===========================================================================================
  // B. Create Operations - Validation
  // ===========================================================================================

  @Test
  @DisplayName("Create with invalid ISO 4217 code (too short) throws BusinessException")
  void createWithTooShortCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_TOO_SHORT)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  @DisplayName("Create with invalid ISO 4217 code (too long) throws BusinessException")
  void createWithTooLongCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_TOO_LONG)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  @DisplayName("Create with invalid ISO 4217 code (lowercase) throws BusinessException")
  void createWithLowercaseCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_LOWERCASE)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  @DisplayName("Create with invalid ISO 4217 code (with numbers) throws BusinessException")
  void createWithNumbersInCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_WITH_NUMBERS)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  @DisplayName("Create with non-existent ISO 4217 code (ZZZ) throws BusinessException")
  void createWithNonExistentCurrencyCodeThrowsException() {
    // Arrange
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withCurrencyCode(TestConstants.INVALID_CURRENCY_ZZZ)
            .build();

    // Act & Assert (no need to stub - validation happens before provider call)
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Invalid ISO 4217");
  }

  @Test
  @DisplayName("Create duplicate currency code throws BusinessException")
  void createDuplicateCurrencyCodeThrowsException() {
    // Arrange - Stub FRED API responses and create first EUR series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series1 = CurrencySeriesTestBuilder.defaultEur().build();
    service.create(series1);

    // Try to create another EUR series with different provider ID
    FredApiStubs.stubSeriesExistsSuccess("DIFFERENT_SERIES");
    var series2 =
        CurrencySeriesTestBuilder.defaultEur().withProviderSeriesId("DIFFERENT_SERIES").build();

    // Act & Assert
    assertThatThrownBy(() -> service.create(series2))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("already exists");
  }

  // ===========================================================================================
  // C. Provider Integration
  // ===========================================================================================

  @Test
  @DisplayName("Create succeeds when provider validates series exists")
  void createSucceedsWhenProviderReturnsTrue() {
    // Arrange - Stub FRED API to return series exists
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act
    var created = service.create(series);

    // Assert
    assertThat(created.getId()).isNotNull();
  }

  @Test
  @DisplayName("Create fails when provider returns 404 for invalid series")
  void createFailsWhenProviderReturnsFalse() {
    // Arrange - Stub FRED API to return 404
    FredApiStubs.stubSeriesExistsNotFound(TestConstants.FRED_SERIES_INVALID);
    var series =
        CurrencySeriesTestBuilder.defaultEur()
            .withProviderSeriesId(TestConstants.FRED_SERIES_INVALID)
            .build();

    // Act & Assert
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("does not exist in the external provider");
  }

  @Test
  @DisplayName("Create throws ServiceUnavailableException when provider returns 500")
  void createThrowsServiceUnavailableWhenProviderFails() {
    // Arrange - Stub FRED API to return 500
    FredApiStubs.stubSeriesExistsServerError(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();

    // Act & Assert
    assertThatThrownBy(() -> service.create(series))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageContaining("Unable to validate provider series ID");
  }

  // ===========================================================================================
  // D. Read Operations
  // ===========================================================================================

  @Test
  @DisplayName("Get by ID returns correct currency series with all fields")
  void getByIdReturnsCorrectSeries() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = service.create(series);

    // Act
    var retrieved = service.getById(created.getId());

    // Assert
    assertThat(retrieved.getId()).isEqualTo(created.getId());
    assertThat(retrieved.getCurrencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(retrieved.getProviderSeriesId()).isEqualTo(TestConstants.FRED_SERIES_EUR);
    assertThat(retrieved.isEnabled()).isTrue();
  }

  @Test
  @DisplayName("Get by ID with non-existent ID throws ResourceNotFoundException")
  void getByIdWithNonExistentIdThrowsException() {
    // Act & Assert
    assertThatThrownBy(() -> service.getById(999L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("not found");
  }

  @Test
  @DisplayName("Get all without filter returns all currency series")
  void getAllWithoutFilterReturnsAllSeries() {
    // Arrange - Stub FRED API responses and create 3 series (2 enabled, 1 disabled)
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    service.create(CurrencySeriesTestBuilder.defaultEur().build());
    service.create(CurrencySeriesTestBuilder.defaultThb().build());
    service.create(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Act
    var allSeries = service.getAll(false);

    // Assert
    assertThat(allSeries).hasSize(3);
  }

  @Test
  @DisplayName("Get all with enabled-only filter returns only enabled series")
  void getAllWithEnabledFilterReturnsOnlyEnabledSeries() {
    // Arrange - Stub FRED API responses and create 3 series (2 enabled, 1 disabled)
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    service.create(CurrencySeriesTestBuilder.defaultEur().build());
    service.create(CurrencySeriesTestBuilder.defaultThb().build());
    service.create(CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());

    // Act
    var enabledSeries = service.getAll(true);

    // Assert
    assertThat(enabledSeries).hasSize(2);
    assertThat(enabledSeries).allMatch(series -> series.isEnabled());
  }

  // ===========================================================================================
  // E. Update Operations
  // ===========================================================================================

  @Test
  @DisplayName("Update enabled from true to false updates database correctly")
  void updateEnabledTrueToFalseUpdatesDatabase() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = service.create(series);

    // Act
    var updated = service.update(created.getId(), false);

    // Assert
    assertThat(updated.isEnabled()).isFalse();
    assertThat(updated.getUpdatedAt()).isAfter(created.getUpdatedAt());

    // Verify database
    var retrieved = service.getById(created.getId());
    assertThat(retrieved.isEnabled()).isFalse();
  }

  @Test
  @DisplayName("Update enabled from false to true updates database correctly")
  void updateEnabledFalseToTrueUpdatesDatabase() {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();
    var created = service.create(series);

    // Act
    var updated = service.update(created.getId(), true);

    // Assert
    assertThat(updated.isEnabled()).isTrue();

    // Verify database
    var retrieved = service.getById(created.getId());
    assertThat(retrieved.isEnabled()).isTrue();
  }

  @Test
  @DisplayName("Update publishes CurrencyUpdatedEvent with correct data")
  void updatePublishesCurrencyUpdatedEvent(ApplicationEvents events) {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = service.create(series);

    // Clear create events by counting them first
    long createEventCount = events.stream(CurrencyCreatedEvent.class).count();
    assertThat(createEventCount).isEqualTo(1);

    // Act
    service.update(created.getId(), false);

    // Assert - Verify CurrencyUpdatedEvent published
    long updateEventCount = events.stream(CurrencyUpdatedEvent.class).count();
    assertThat(updateEventCount).isEqualTo(1);

    var updateEvent =
        events.stream(CurrencyUpdatedEvent.class)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected CurrencyUpdatedEvent"));

    assertThat(updateEvent.currencySeriesId()).isEqualTo(created.getId());
    assertThat(updateEvent.currencyCode()).isEqualTo(TestConstants.VALID_CURRENCY_EUR);
    assertThat(updateEvent.enabled()).isFalse();
    assertThat(updateEvent.correlationId()).isNull(); // MDC not set in test context
  }

  @Test
  @DisplayName("Update non-existent series throws ResourceNotFoundException")
  void updateNonExistentSeriesThrowsException() {
    // Act & Assert
    assertThatThrownBy(() -> service.update(999L, true))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("not found");
  }

  @Test
  @DisplayName("Update changes updatedAt timestamp")
  void updateChangesUpdatedAtTimestamp() throws InterruptedException {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = service.create(series);
    var originalUpdatedAt = created.getUpdatedAt();

    // Sleep to ensure timestamp difference
    Thread.sleep(100);

    // Act
    var updated = service.update(created.getId(), false);

    // Assert
    assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
  }

  // ===========================================================================================
  // F. Event Verification
  // ===========================================================================================

  @Test
  @DisplayName("CurrencyCreatedEvent contains all required fields")
  void currencyCreatedEventContainsAllRequiredFields(ApplicationEvents events) {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    var series = CurrencySeriesTestBuilder.defaultThb().build();

    // Act
    var created = service.create(series);

    // Assert
    var event =
        events.stream(CurrencyCreatedEvent.class)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected CurrencyCreatedEvent"));

    assertThat(event.currencySeriesId()).isNotNull().isEqualTo(created.getId());
    assertThat(event.currencyCode()).isNotNull().isEqualTo(TestConstants.VALID_CURRENCY_THB);
    assertThat(event.enabled()).isTrue();
    // correlationId is null in test context (no HTTP request / MDC)
  }

  @Test
  @DisplayName("Multiple create operations publish multiple distinct events")
  void multipleCreatesPublishMultipleEvents(ApplicationEvents events) {
    // Arrange - Stub FRED API responses
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_THB);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);

    // Act - Create 3 different currency series
    service.create(CurrencySeriesTestBuilder.defaultEur().build());
    service.create(CurrencySeriesTestBuilder.defaultThb().build());
    service.create(CurrencySeriesTestBuilder.defaultGbp().build());

    // Assert
    long eventCount = events.stream(CurrencyCreatedEvent.class).count();
    assertThat(eventCount).isEqualTo(3);
  }

  @Test
  @DisplayName("Update publishes CurrencyUpdatedEvent, not CurrencyCreatedEvent")
  void updatePublishesCorrectEventType(ApplicationEvents events) {
    // Arrange - Stub FRED API responses and create series
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    var series = CurrencySeriesTestBuilder.defaultEur().build();
    var created = service.create(series);

    // Count create events first
    long createEventsBefore = events.stream(CurrencyCreatedEvent.class).count();

    // Act - Update the series
    service.update(created.getId(), false);

    // Assert - No new CurrencyCreatedEvent
    long createEventsAfter = events.stream(CurrencyCreatedEvent.class).count();
    assertThat(createEventsAfter).isEqualTo(createEventsBefore);

    // Assert - Exactly one CurrencyUpdatedEvent
    long updateEventCount = events.stream(CurrencyUpdatedEvent.class).count();
    assertThat(updateEventCount).isEqualTo(1);
  }
}
