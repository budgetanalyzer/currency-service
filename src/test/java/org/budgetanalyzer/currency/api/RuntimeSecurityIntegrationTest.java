package org.budgetanalyzer.currency.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.budgetanalyzer.currency.base.AbstractControllerTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.service.audit.AuditLogger;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

/**
 * Verifies that every application endpoint declares an authorization policy and that state-changing
 * requests are audited.
 */
class RuntimeSecurityIntegrationTest extends AbstractControllerTest {

  /** Handlers allowed to rely on the {@code authenticated()} fallback. Keep this list empty. */
  private static final Set<String> AUTHORIZATION_EXCEPTIONS = Set.of();

  private final Logger auditLogbackLogger =
      (Logger) LoggerFactory.getLogger(AuditLogger.LOGGER_NAME);
  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;
  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @BeforeEach
  void attachAuditAppender() {
    listAppender.start();
    auditLogbackLogger.addAppender(listAppender);
  }

  @AfterEach
  void detachAuditAppender() {
    auditLogbackLogger.detachAppender(listAppender);
    listAppender.stop();
  }

  @Test
  void allApplicationHandlersDeclareAuthorizationPolicy() {
    var applicationHandlers =
        requestMappingHandlerMapping.getHandlerMethods().values().stream()
            .filter(RuntimeSecurityIntegrationTest::isApplicationHandler)
            .toList();
    var handlerIdentifiers =
        applicationHandlers.stream()
            .map(RuntimeSecurityIntegrationTest::handlerIdentifier)
            .collect(Collectors.toSet());
    var handlersWithoutAuthorization =
        applicationHandlers.stream()
            .filter(
                handlerMethod ->
                    !AUTHORIZATION_EXCEPTIONS.contains(handlerIdentifier(handlerMethod)))
            .filter(handlerMethod -> !hasPreAuthorize(handlerMethod))
            .map(RuntimeSecurityIntegrationTest::handlerIdentifier)
            .sorted()
            .toList();

    assertThat(applicationHandlers).isNotEmpty();
    assertThat(handlerIdentifiers).containsAll(AUTHORIZATION_EXCEPTIONS);
    assertThat(handlersWithoutAuthorization)
        .as("application request handlers without @PreAuthorize")
        .isEmpty();
  }

  @Test
  void stateChangingRequestEmitsExactlyOneAuditEvent() throws Exception {
    var currencySeries =
        currencySeriesRepository.save(
            CurrencySeriesTestBuilder.defaultGbp().enabled(false).build());
    var requestJson =
        """
        {
          "enabled": false
        }
        """;

    performPutWithClaims(
            "/v1/currencies/" + currencySeries.getId(),
            requestJson,
            ClaimsHeaderTestBuilder.user(TestConstants.WRITER_USER_ID)
                .withPermissions(TestConstants.PERMISSION_CURRENCIES_WRITE))
        .andExpect(status().isOk());

    assertThat(listAppender.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("\"actor\":\"" + TestConstants.WRITER_USER_ID + "\"")
        .contains("\"operation\":\"PUT /v1/currencies/{id}\"")
        .contains("\"resourceIdentifier\":\"" + currencySeries.getId() + "\"")
        .contains("\"outcome\":200")
        .doesNotContain("enabled");
  }

  private static boolean isApplicationHandler(HandlerMethod handlerMethod) {
    return handlerMethod.getBeanType().getPackageName().startsWith("org.budgetanalyzer");
  }

  private static boolean hasPreAuthorize(HandlerMethod handlerMethod) {
    return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class)
            != null
        || AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), PreAuthorize.class)
            != null;
  }

  private static String handlerIdentifier(HandlerMethod handlerMethod) {
    var parameterTypes =
        Arrays.stream(handlerMethod.getMethod().getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));

    return handlerMethod.getBeanType().getName()
        + "#"
        + handlerMethod.getMethod().getName()
        + "("
        + parameterTypes
        + ")";
  }
}
