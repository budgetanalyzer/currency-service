package org.budgetanalyzer.currency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import org.budgetanalyzer.currency.base.AbstractIntegrationTest;
import org.budgetanalyzer.service.config.HttpLoggingProperties;

@ActiveProfiles("debug-logging")
class DebugLoggingProfileIntegrationTest extends AbstractIntegrationTest {

  @Autowired private Environment environment;
  @Autowired private HttpLoggingProperties httpLoggingProperties;

  @Test
  void debugLoggingProfileEnablesTraceAndHttpBodies() {
    assertThat(environment.getProperty("logging.level.org.budgetanalyzer")).isEqualTo("TRACE");
    assertThat(httpLoggingProperties.isIncludeRequestBody()).isTrue();
    assertThat(httpLoggingProperties.isIncludeResponseBody()).isTrue();
  }
}
