package org.budgetanalyzer.currency.service;

import java.util.Currency;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.domain.event.CurrencyCreatedEvent;
import org.budgetanalyzer.currency.domain.event.CurrencyUpdatedEvent;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.service.provider.ExchangeRateProvider;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ClientException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.exception.ServiceUnavailableException;
import org.budgetanalyzer.service.http.CorrelationIdFilter;

/**
 * Service for managing currency operations.
 *
 * <p>This service handles all currency-related business logic including creation, retrieval,
 * updates, and validation. It publishes domain events for significant business operations (e.g.,
 * currency creation) using Spring's event infrastructure, which are then processed asynchronously
 * by event listeners via Spring Modulith's transactional outbox pattern.
 *
 * @see CurrencyCreatedEvent
 */
@Service
public class CurrencyService {

  private final CurrencySeriesRepository currencySeriesRepository;
  private final ExchangeRateProvider exchangeRateProvider;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Constructor for CurrencyService.
   *
   * @param currencySeriesRepository The currency series repository
   * @param exchangeRateProvider The exchange rate provider for validating series IDs
   * @param eventPublisher The Spring event publisher for publishing domain events
   */
  public CurrencyService(
      CurrencySeriesRepository currencySeriesRepository,
      ExchangeRateProvider exchangeRateProvider,
      ApplicationEventPublisher eventPublisher) {
    this.currencySeriesRepository = currencySeriesRepository;
    this.exchangeRateProvider = exchangeRateProvider;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Create a new currency series.
   *
   * <p>This method validates the currency code and provider series ID against the external provider
   * (FRED), persists the currency series to the database, and publishes a {@link
   * CurrencyCreatedEvent} domain event. The event is automatically persisted to the
   * event_publication table by Spring Modulith within the same database transaction, ensuring
   * guaranteed delivery via the transactional outbox pattern.
   *
   * <p><b>Typical Use Case:</b> This endpoint is intended for adding new currencies when FRED adds
   * support for additional currency pairs. Most users will not need to use this endpoint, as 23
   * commonly-used currencies are pre-populated on application startup and can be enabled via the
   * PUT endpoint.
   *
   * <p><b>Event Publishing Flow:</b>
   *
   * <ol>
   *   <li>Currency entity is saved to the database (within transaction)
   *   <li>Domain event is published via ApplicationEventPublisher (in-memory)
   *   <li>Spring Modulith intercepts the event and persists it to event_publication table (same
   *       transaction)
   *   <li>Transaction commits (currency entity + event both saved atomically)
   *   <li>Spring Modulith asynchronously processes the event via {@code @ApplicationModuleListener}
   *   <li>Event listener publishes external message to RabbitMQ
   * </ol>
   *
   * <p><b>Transactional Guarantees:</b>
   *
   * <p>The event is stored in the same database transaction as the currency entity, guaranteeing:
   *
   * <ul>
   *   <li>No lost events - Event survives application crashes since it's persisted in database
   *   <li>Exactly-once semantics - Event and entity are saved atomically, preventing dual-write
   *       issues
   *   <li>Async processing - HTTP request returns immediately after transaction commit without
   *       waiting for RabbitMQ publishing
   * </ul>
   *
   * @param currencySeries The currency series to create (must include both currencyCode and
   *     providerSeriesId)
   * @return The created currency series with database-generated ID
   * @throws BusinessException if currency code is invalid or already exists, or if provider series
   *     ID does not exist in the external provider
   * @throws ServiceUnavailableException if unable to validate provider series ID due to external
   *     API failure
   * @see CurrencyCreatedEvent
   * @see MessagingEventListener# onCurrencyCreated(CurrencyCreatedEvent)
   */
  @Transactional
  public CurrencySeries create(CurrencySeries currencySeries) {
    validateCurrencyCode(currencySeries.getCurrencyCode());
    validateProviderSeriesId(currencySeries.getProviderSeriesId());

    try {
      // Save currency entity to database
      var saved = currencySeriesRepository.save(currencySeries);

      // Get correlation ID from MDC (set by CorrelationIdFilter for HTTP requests)
      var correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

      // Publish domain event - Spring Modulith will persist this to event_publication table
      // in the SAME transaction, guaranteeing delivery even if application crashes
      eventPublisher.publishEvent(
          new CurrencyCreatedEvent(
              saved.getId(), saved.getCurrencyCode(), saved.isEnabled(), correlationId));

      return saved;
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(
          "Currency code '" + currencySeries.getCurrencyCode() + "' already exists",
          CurrencyServiceError.DUPLICATE_CURRENCY_CODE.name());
    }
  }

  /**
   * Get a currency series by ID.
   *
   * @param id The currency series ID
   * @return The currency series
   * @throws ResourceNotFoundException if currency series not found
   */
  @Transactional(readOnly = true)
  public CurrencySeries getById(Long id) {
    return currencySeriesRepository
        .findById(id)
        .orElseThrow(
            () -> new ResourceNotFoundException("Currency series not found with id: " + id));
  }

  /**
   * Get all currency series.
   *
   * @param enabledOnly If true, return only enabled currency series; if false, return all
   * @return List of currency series
   */
  @Transactional(readOnly = true)
  public List<CurrencySeries> getAll(boolean enabledOnly) {
    return enabledOnly
        ? currencySeriesRepository.findByEnabledTrue()
        : currencySeriesRepository.findAll();
  }

  /**
   * Update an existing currency series.
   *
   * <p>Note: Currency code and providerSeriesId are immutable and cannot be changed after creation.
   * Only the enabled status can be updated.
   *
   * <p>This method publishes a {@link CurrencyUpdatedEvent} domain event which is automatically
   * persisted to the event_publication table by Spring Modulith within the same database
   * transaction. The event listener will only trigger exchange rate imports if the currency is
   * enabled.
   *
   * @param id The currency series ID
   * @param enabled The new enabled status
   * @return The updated currency series
   * @throws ResourceNotFoundException if currency series not found
   */
  @Transactional
  public CurrencySeries update(Long id, boolean enabled) {
    var currencySeries = getById(id);
    currencySeries.setEnabled(enabled);

    var saved = currencySeriesRepository.save(currencySeries);

    // Get correlation ID from MDC (set by CorrelationIdFilter for HTTP requests)
    var correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);

    // Publish domain event - Spring Modulith will persist this to event_publication table
    // in the SAME transaction, guaranteeing delivery even if application crashes
    eventPublisher.publishEvent(
        new CurrencyUpdatedEvent(
            saved.getId(), saved.getCurrencyCode(), saved.isEnabled(), correlationId));

    return saved;
  }

  /**
   * Validate that the currency code is a valid ISO 4217 code.
   *
   * <p>Note: Format validation (3 uppercase letters) is already handled by Bean Validation in the
   * request DTO. This method only checks ISO 4217 validity.
   *
   * @param currencyCode The currency code to validate
   * @throws BusinessException if currency code is not a valid ISO 4217 code
   */
  private void validateCurrencyCode(String currencyCode) {
    try {
      Currency.getInstance(currencyCode);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          "Invalid ISO 4217 currency code: " + currencyCode,
          CurrencyServiceError.INVALID_ISO_4217_CODE.name());
    }
  }

  /**
   * Validate that the provider series ID exists in the external provider (FRED).
   *
   * @param providerSeriesId The provider series ID to validate
   * @throws BusinessException if provider series ID does not exist in the external provider
   * @throws ServiceUnavailableException if unable to validate due to external API failure
   */
  private void validateProviderSeriesId(String providerSeriesId) {
    boolean exists;
    try {
      exists = exchangeRateProvider.validateSeriesExists(providerSeriesId);
    } catch (ClientException e) {
      throw new ServiceUnavailableException(
          "Unable to validate provider series ID due to external provider API error: "
              + e.getMessage(),
          e);
    }

    if (!exists) {
      throw new BusinessException(
          "Provider series ID '" + providerSeriesId + "' does not exist in the external provider",
          CurrencyServiceError.INVALID_PROVIDER_SERIES_ID.name());
    }
  }
}
