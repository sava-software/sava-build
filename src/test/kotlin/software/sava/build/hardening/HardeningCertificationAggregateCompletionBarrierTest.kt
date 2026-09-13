package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HardeningCertificationAggregateCompletionBarrierTest {

  @TempDir
  lateinit var root: File

  @Test
  fun `publication waits for both anchor and published child in either delivery order`() {
    listOf(true, false).forEach { anchorFirst ->
      val waiting = LinkedBlockingQueue<Unit>()
      val barrier = barrier(waiting)
      barrier.activate(listOf(":child"))
      // Child task actions finish before the publisher runs; only event delivery is delayed.
      barrier.recordReceiptPublished(":child")

      withExecutor { executor ->
        val result = executor.submit { barrier.awaitPublicationEvidence() }
        awaitWaiting(waiting)
        assertFalse(result.isDone)
        if (anchorFirst) barrier.recordAggregateAnchorFinished(true)
        else barrier.recordProjectTaskFinished(":child", true)

        awaitWaiting(waiting)
        assertFalse(result.isDone, "one completion event cannot authorize publication")
        if (anchorFirst) barrier.recordProjectTaskFinished(":child", true)
        else barrier.recordAggregateAnchorFinished(true)

        result.get(5, TimeUnit.SECONDS)
        assertTrue(barrier.aggregateAnchorCompletedSuccessfully())
        assertTrue(barrier.projectTaskSucceeded(":child") == true)
      }
    }
  }

  @Test
  fun `late child failure settles the wait without authorizing its published receipt`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val barrier = barrier(waiting)
    barrier.activate(listOf(":child", ":sibling"))
    barrier.recordAggregateAnchorFinished(true)
    barrier.recordReceiptPublished(":child")
    barrier.recordReceiptPublished(":sibling")

    withExecutor { executor ->
      val result = executor.submit { barrier.awaitPublicationEvidence() }
      awaitWaiting(waiting)
      barrier.recordProjectTaskFinished(":child", false)

      result.get(5, TimeUnit.SECONDS)
      assertFalse(barrier.projectTaskSucceeded(":child") == true)
      assertTrue(barrier.projectTaskSucceeded(":sibling") == null)
    }
  }

  @Test
  fun `late anchor failure settles the wait despite pending child events`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val barrier = barrier(waiting)
    barrier.activate(listOf(":child"))
    barrier.recordReceiptPublished(":child")

    withExecutor { executor ->
      val result = executor.submit { barrier.awaitPublicationEvidence() }
      awaitWaiting(waiting)
      barrier.recordAggregateAnchorFinished(false)

      result.get(5, TimeUnit.SECONDS)
      assertTrue(barrier.aggregateAnchorFailed())
      assertFalse(barrier.aggregateAnchorCompletedSuccessfully())
    }
  }

  @Test
  fun `a known child failure leaves only the anchor completion relevant to the diagnostic`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val barrier = barrier(waiting)
    barrier.activate(listOf(":failed", ":pending"))
    barrier.recordProjectTaskFinished(":failed", false)
    barrier.recordReceiptPublished(":pending")
    val observations = mutableListOf<CertificationAggregateCompletionWait>()

    withExecutor { executor ->
      val result = executor.submit { barrier.awaitPublicationEvidence { observations += it } }
      awaitWaiting(waiting)
      barrier.recordAggregateAnchorFinished(true)
      result.get(5, TimeUnit.SECONDS)
    }

    assertTrue(observations.single().pendingAnchor)
    assertEquals(0, observations.single().pendingChildTasks)
  }

  @Test
  fun `unpublished child needs no completion event and remains unsuccessful`() {
    val barrier = CertificationAggregateCompletionBarrier(timeoutNanos = 0)
    barrier.activate(listOf(":child"))
    barrier.recordAggregateAnchorFinished(true)

    barrier.awaitPublicationEvidence { error("settled evidence must not report a wait") }

    assertTrue(barrier.projectTaskSucceeded(":child") == null)
  }

  @Test
  fun `events delivered before publication need no wait`() {
    val barrier = CertificationAggregateCompletionBarrier(timeoutNanos = 0)
    barrier.activate(listOf(":child"))
    barrier.recordAggregateAnchorFinished(true)
    barrier.recordReceiptPublished(":child")
    barrier.recordProjectTaskFinished(":child", true)

    barrier.awaitPublicationEvidence { error("settled evidence must not report a wait") }

    assertTrue(barrier.aggregateAnchorCompletedSuccessfully())
    assertTrue(barrier.projectTaskSucceeded(":child") == true)
  }

  @Test
  fun `missing anchor or published child completion fails at the deadline`() {
    listOf(true, false).forEach { missingAnchor ->
      val barrier = CertificationAggregateCompletionBarrier(timeoutNanos = 0)
      barrier.activate(listOf(":child"))
      barrier.recordReceiptPublished(":child")
      if (missingAnchor) barrier.recordProjectTaskFinished(":child", true)
      else barrier.recordAggregateAnchorFinished(true)

      val failure = assertThrows(IllegalStateException::class.java) {
        barrier.awaitPublicationEvidence()
      }

      assertTrue(failure.message.orEmpty().contains("timed out"), failure.message)
    }
  }

  @Test
  fun `an engaged wait reports its initial counts and monotonic duration once outside the lock`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val elapsed = AtomicLong()
    val observations = mutableListOf<CertificationAggregateCompletionWait>()
    val barrier = CertificationAggregateCompletionBarrier(
      nanoTime = elapsed::get,
      beforeWait = { waiting.add(Unit) },
    )
    barrier.activate(listOf(":child", ":unpublished"))
    barrier.recordReceiptPublished(":child")

    withExecutor { callbackExecutor ->
      withExecutor { waiter ->
        val result = waiter.submit {
          barrier.awaitPublicationEvidence { observation ->
            // A different thread must be able to acquire the barrier during the diagnostic.
            assertTrue(callbackExecutor.submit<Boolean> {
              barrier.aggregateAnchorCompletedSuccessfully()
            }.get(5, TimeUnit.SECONDS))
            observations += observation
          }
        }
        awaitWaiting(waiting)
        elapsed.set(TimeUnit.MILLISECONDS.toNanos(7))
        barrier.recordAggregateAnchorFinished(true)
        awaitWaiting(waiting)
        elapsed.set(TimeUnit.MILLISECONDS.toNanos(12))
        barrier.recordProjectTaskFinished(":child", true)
        result.get(5, TimeUnit.SECONDS)
      }
    }

    assertEquals(listOf(CertificationAggregateCompletionWait(
      pendingAnchor = true,
      pendingChildTasks = 1,
      elapsedNanos = TimeUnit.MILLISECONDS.toNanos(12),
      outcome = "completed",
    )), observations)
    assertEquals(
      "hardeningCertifyAll: publication evidence wait completed after 12 ms; initially awaiting " +
        "1 aggregate anchor and 1 child task completion(s)",
      observations.single().diagnostic(),
    )
  }

  @Test
  fun `a delivered event does not restart the total wait budget`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val elapsed = AtomicLong()
    val budget = TimeUnit.SECONDS.toNanos(30)
    val barrier = CertificationAggregateCompletionBarrier(
      timeoutNanos = budget,
      nanoTime = elapsed::get,
      beforeWait = { waiting.add(Unit) },
    )
    barrier.activate(listOf(":child"))
    barrier.recordReceiptPublished(":child")
    val observations = mutableListOf<CertificationAggregateCompletionWait>()

    withExecutor { executor ->
      val result = executor.submit { barrier.awaitPublicationEvidence { observations += it } }
      awaitWaiting(waiting)
      elapsed.set(budget)
      barrier.recordAggregateAnchorFinished(true)

      val failure = assertThrows(ExecutionException::class.java) {
        result.get(5, TimeUnit.SECONDS)
      }.cause
      assertTrue(failure is IllegalStateException, failure.toString())
      assertTrue(failure?.message.orEmpty().contains("timed out"), failure?.message)
    }
    assertEquals("timed out", observations.single().outcome)
    assertEquals(budget, observations.single().elapsedNanos)
  }

  @Test
  fun `interruption preserves the refusal marker and previous receipt with interrupt status set`() {
    val waiting = LinkedBlockingQueue<Unit>()
    val waiter = AtomicReference<Thread>()
    val barrier = barrier(waiting)
    barrier.activate(listOf(":child"))
    val receipt = root.resolve("aggregate.tsv")
    val marker = root.resolve("aggregate.running")
    val previousReceipt = "previous successful receipt\n".toByteArray()
    receipt.writeBytes(previousReceipt)
    marker.writeText("session\tcurrent-attempt\n")
    val observations = mutableListOf<CertificationAggregateCompletionWait>()

    withExecutor { executor ->
      val result = executor.submit<Boolean> {
        waiter.set(Thread.currentThread())
        val failure = assertThrows(IllegalStateException::class.java) {
          barrier.awaitPublicationEvidence { observations += it }
        }
        assertTrue(failure.cause is InterruptedException, failure.toString())
        assertTrue(Thread.currentThread().isInterrupted)
        BaselineFiles.preserveReceiptUnderIncompleteMarker(
          root, receipt, marker, "refused\t${failure.message}\n")
        Thread.currentThread().isInterrupted
      }
      awaitWaiting(waiting)
      waiter.get().interrupt()

      assertTrue(result.get(5, TimeUnit.SECONDS))
    }
    assertEquals("interrupted", observations.single().outcome)
    assertArrayEquals(previousReceipt, receipt.readBytes())
    assertEquals(
      "refused\tinterrupted while waiting for aggregate anchor completion and receipt-published " +
        "child task completion\n",
      marker.readText(),
    )
    assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".sava-hardening-") })
  }

  @Test
  fun `a failed diagnostic preserves the original interruption and its flag`() {
    val barrier = CertificationAggregateCompletionBarrier()
    barrier.activate(listOf(":child"))
    val diagnosticFailure = IllegalStateException("diagnostic failed")
    withExecutor { executor ->
      executor.submit {
        Thread.currentThread().interrupt()
        val failure = assertThrows(IllegalStateException::class.java) {
          barrier.awaitPublicationEvidence { throw diagnosticFailure }
        }
        assertTrue(failure.cause is InterruptedException)
        assertTrue(Thread.currentThread().isInterrupted)
        assertEquals(listOf(diagnosticFailure), failure.suppressed.toList())
      }.get(5, TimeUnit.SECONDS)
    }
  }

  private fun barrier(waiting: LinkedBlockingQueue<Unit>) =
    CertificationAggregateCompletionBarrier(beforeWait = { waiting.add(Unit) })

  private fun awaitWaiting(waiting: LinkedBlockingQueue<Unit>) {
    assertNotNull(waiting.poll(5, TimeUnit.SECONDS), "publisher never reached the event wait")
  }

  private fun withExecutor(action: (java.util.concurrent.ExecutorService) -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    try {
      action(executor)
    } finally {
      executor.shutdownNow()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "publication waiter leaked")
    }
  }
}
