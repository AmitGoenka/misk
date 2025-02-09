package misk.backoff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith

internal class RetryTest {
  @Test fun deprecatedFunction() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))

    val result = retry(3, backoff) { retry ->
      if (retry < 2) throw IllegalStateException("this failed")
      "succeeded on retry $retry"
    }

    assertThat(result).isEqualTo("succeeded on retry 2")
  }

  @Test fun retries() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))

    val retryConfig = RetryConfig.Builder(3, backoff)
    val result = retry(retryConfig.build()) { retry: Int ->
      if (retry < 2) throw IllegalStateException("this failed")
      "succeeded on retry $retry"
    }

    assertThat(result).isEqualTo("succeeded on retry 2")
  }

  @Test fun failsIfExceedsMaxRetries() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))

    assertFailsWith<IllegalStateException> {
      val retryConfig = RetryConfig.Builder(3, backoff)
      retry(retryConfig.build()) { throw IllegalStateException("this failed") }
    }
  }

  @Test fun honorsBackoff() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))

    assertFailsWith<IllegalStateException> {
      val retryConfig = RetryConfig.Builder(3, backoff)
      retry(retryConfig.build()) { throw IllegalStateException("this failed") }
    }

    // Backoff should have advanced
    assertThat(backoff.nextRetry()).isEqualTo(Duration.ofMillis(40))
  }

  @Test fun resetsBackoffPriorToUse() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))

    // Preseed the backoff with a delay
    backoff.nextRetry()
    backoff.nextRetry()
    backoff.nextRetry()

    assertFailsWith<IllegalStateException> {
      val retryConfig = RetryConfig.Builder(3, backoff)
      retry(retryConfig.build()) { throw IllegalStateException("this failed") }
    }

    // resets backoff prior to use
    assertThat(backoff.nextRetry()).isEqualTo(Duration.ofMillis(40))
  }

  @Test fun resetsBackoffAfterSuccess() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))
    val retryConfig = RetryConfig.Builder(3, backoff)
    val result = retry(retryConfig.build()) { retry: Int ->
      if (retry < 2) throw IllegalStateException("this failed")
      "hello"
    }

    assertThat(result).isEqualTo("hello")

    // Backoff should be reset to base delay after success
    assertThat(backoff.nextRetry()).isEqualTo(Duration.ofMillis(10))
  }

  @Test fun callsOnRetryCallbackIfProvided() {
    var retryCount = 0
    var retried = 0
    // simply counts the number of times it was called
    val onRetryFunction: (retries: Int, exception: Exception) -> Unit = { _, _ ->
      retried = retried.inc()
    }

    // f is a function that throws an exception twice in a row
    val retryConfig = RetryConfig.Builder(3, FlatBackoff()).onRetry(onRetryFunction)
    retry(retryConfig.build()) {
      retryCount = retryCount.inc()
      if (retryCount < 3) throw Exception("a failure that triggers a retry")
    }

    assertThat(retried).isEqualTo(2)
  }

  @Test
  fun throwsDontRetryExceptionWithMessage() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))
    val customMessage = "Custom message for DontRetryException"
    assertFailsWith<DontRetryException> {
      val retryConfig = RetryConfig.Builder(3, backoff)
      retry(retryConfig.build()) {
        throw DontRetryException(customMessage)
      }
    }.also { exception ->
      assertThat(exception.message).isEqualTo(customMessage)
    }
  }

  @Test
  fun throwsDontRetryExceptionWithCause() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))
    val cause = IllegalStateException("Underlying exception")
    assertFailsWith<DontRetryException> {
      val retryConfig = RetryConfig.Builder(3, backoff)

      retry(retryConfig.build()) {
        throw DontRetryException(cause)
      }
    }.also { exception ->
      assertThat(exception.cause).isEqualTo(cause)
    }
  }

  @Test
  fun throwsDontRetryExceptionWithMessageAndCause() {
    val backoff = ExponentialBackoff(Duration.ofMillis(10), Duration.ofMillis(100))
    val customMessage = "Custom message for DontRetryException"
    val cause = IllegalStateException("Underlying exception")
    assertFailsWith<DontRetryException> {
      val retryConfig = RetryConfig.Builder(3, backoff)
      retry(retryConfig.build()) {
        throw DontRetryException(customMessage, cause)
      }
    }.also { exception ->
      assertThat(exception.message).isEqualTo(customMessage)
      assertThat(exception.cause).isEqualTo(cause)
    }
  }

  @Test
  fun dontRetryExceptionCanBeInstantiatedWithNoMessage() {
      // needed to ensure backwards compat with 2 services
      val exception = DontRetryException()
      assertNotNull(exception)
      assertNull(exception.message)
  }

  @Test
  fun dontRetryIfShouldRetryIsFalse() {
    var retryCount = 0

    assertFailsWith<IllegalStateException> {
      retry(RetryConfig.Builder(100, FlatBackoff()).shouldRetry { false }.build()) {
        retryCount = retryCount.inc()
        throw IllegalStateException()
      }
    }

    assertThat(retryCount).isEqualTo(1)
  }

  @Test
  fun dontRetryIfShouldRetryReturnsFalseOnSecondRetry() {
    var retryCount = 0

    assertFailsWith<IllegalStateException> {
      retry(RetryConfig.Builder(100, FlatBackoff()).shouldRetry { e -> e is IllegalArgumentException }.build()) {
        retryCount = retryCount.inc()
        if (retryCount == 1) throw IllegalArgumentException() // We want to retry on this error
        throw IllegalStateException()
      }
    }

    assertThat(retryCount).isEqualTo(2)
  }

}
