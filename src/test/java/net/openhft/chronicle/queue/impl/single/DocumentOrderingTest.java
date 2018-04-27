package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.ValueOut;
import org.junit.After;
import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public final class DocumentOrderingTest {
    private static final RollCycles ROLL_CYCLE = RollCycles.TEST_SECONDLY;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger counter = new AtomicInteger(0);
    private final boolean progressOnContention;

    public DocumentOrderingTest(final String testType, final boolean progressOnContention) {
        this.progressOnContention = progressOnContention;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[]{"progressOnContention", true}, new Object[]{"waitOnContention", false});
    }

    private static void expectValue(final int expectedValue, final ExcerptTailer tailer) {
        try (final DocumentContext documentContext = tailer.readingDocument()) {
            assertTrue(documentContext.isPresent());
            assertEquals(expectedValue, documentContext.wire().getValueIn().int32());
        }
    }

    @Ignore("Flaky test - https://github.com/OpenHFT/Chronicle-Queue/issues/459")
    @Test
    public void queuedWriteInPreviousCycleShouldRespectTotalOrdering() throws Exception {
        try (final SingleChronicleQueue queue =
                     builder(DirectoryUtils.tempDir("document-ordering"), 1_000L).build()) {
            Assume.assumeFalse(
                    "ordering/atomicity is not guaranteed when using progressOnContention = true," +
                            "as multiple threads can be concurrently executing within a queue's " +
                            "document context when the queue head is contented",
                    progressOnContention);

            final ExcerptAppender excerptAppender = queue.acquireAppender();
            // write initial document
            excerptAppender.writeDocument("foo", ValueOut::text);

            // begin a record in the first cycle file
            final DocumentContext firstOpenDocument = excerptAppender.writingDocument();
            firstOpenDocument.wire().getValueOut().int32(counter.getAndIncrement());

            // start another record in the first cycle file
            // this may be written to either the first or the second cycle file
            final Future<RecordInfo> secondDocumentInFirstCycle = attemptToWriteDocument(queue);

            // move time to beyond the next cycle
            clock.addAndGet(TimeUnit.SECONDS.toMillis(2L));

            final Future<RecordInfo> otherDocumentWriter = attemptToWriteDocument(queue);

            firstOpenDocument.close();
            secondDocumentInFirstCycle.get(5L, TimeUnit.SECONDS);
            final ExcerptTailer tailer = queue.createTailer();
            // discard first record
            tailer.readingDocument().close();

            // assert that records are committed in order
            expectValue(0, tailer);
            expectValue(1, tailer);
            expectValue(2, tailer);
            assertThat(tailer.readingDocument().isPresent(), is(false));
        }
    }

    @Test
    public void shouldRecoverFromUnfinishedFirstMessageInPreviousQueue() throws Exception {
        // as below, but don't actually close the initial context
        try (final SingleChronicleQueue queue =
                     builder(DirectoryUtils.tempDir("document-ordering"), 1_000L).
                             progressOnContention(progressOnContention).build()) {

            final ExcerptAppender excerptAppender = queue.acquireAppender();
            final Future<RecordInfo> otherDocumentWriter;
            // begin a record in the first cycle file
            final DocumentContext documentContext = excerptAppender.writingDocument();
            documentContext.wire().getValueOut().int32(counter.getAndIncrement());

            // move time to beyond the next cycle
            clock.addAndGet(TimeUnit.SECONDS.toMillis(2L));

            otherDocumentWriter = attemptToWriteDocument(queue);

            assertEquals(1, otherDocumentWriter.get(5L, TimeUnit.SECONDS).counterValue);

            final ExcerptTailer tailer = queue.createTailer();
            expectValue(1, tailer);
            assertThat(tailer.readingDocument().isPresent(), is(false));
        }
    }

    @Test
    public void multipleThreadsMustWaitUntilPreviousCycleFileIsCompleted() throws Exception {

        Assume.assumeFalse(
                "ordering/atomicity is not guaranteed when using progressOnContention = true," +
                        "as multiple threads can be concurrently executing within a queue's " +
                        "document context when the queue head is contented",
                progressOnContention);
        final File dir = DirectoryUtils.tempDir("document-ordering");
        // must be different instances of queue to work around synchronization on acquireStore()
        try (final SingleChronicleQueue queue =
                     builder(dir, 5_000L).build();
             final SingleChronicleQueue queue2 =
                     builder(dir, 5_000L).build();
             final SingleChronicleQueue queue3 =
                     builder(dir, 5_000L).build();
             final SingleChronicleQueue queue4 =
                     builder(dir, 5_000L).build();
        ) {

            final ExcerptAppender excerptAppender = queue.acquireAppender();
            final Future<RecordInfo> firstWriter;
            final Future<RecordInfo> secondWriter;
            final Future<RecordInfo> thirdWriter;
            try (final DocumentContext documentContext = excerptAppender.writingDocument()) {

                // move time to beyond the next cycle
                clock.addAndGet(TimeUnit.SECONDS.toMillis(2L));
                // add some jitter to allow threads to race
                firstWriter = attemptToWriteDocument(queue2);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
                secondWriter = attemptToWriteDocument(queue3);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
                thirdWriter = attemptToWriteDocument(queue4);

                // stall this thread, other threads should not be able to advance,
                // since this DocumentContext is still open
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2L));

                documentContext.wire().getValueOut().int32(counter.getAndIncrement());
            }

            firstWriter.get(5L, TimeUnit.SECONDS);
            secondWriter.get(5L, TimeUnit.SECONDS);
            thirdWriter.get(5L, TimeUnit.SECONDS);

            final ExcerptTailer tailer = queue.createTailer();
            expectValue(0, tailer);
            expectValue(1, tailer);
            expectValue(2, tailer);
            expectValue(3, tailer);
        }
    }

    @Test
    public void codeWithinPriorDocumentMustExecuteBeforeSubsequentDocumentWhenQueueIsEmpty() throws Exception {
        try (final SingleChronicleQueue queue =
                     builder(DirectoryUtils.tempDir("document-ordering"), 3_000L).build()) {

            final ExcerptAppender excerptAppender = queue.acquireAppender();
            final Future<RecordInfo> otherDocumentWriter;
            try (final DocumentContext documentContext = excerptAppender.writingDocument()) {

                // move time to beyond the next cycle
                clock.addAndGet(TimeUnit.SECONDS.toMillis(2L));

                otherDocumentWriter = attemptToWriteDocument(queue);

                // stall this thread, other thread should not be able to advance,
                // since this DocumentContext is still open
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2L));

                documentContext.wire().getValueOut().int32(counter.getAndIncrement());
            }

            assertEquals(1, otherDocumentWriter.get(5L, TimeUnit.SECONDS).counterValue);

            final ExcerptTailer tailer = queue.createTailer();
            expectValue(0, tailer);
            expectValue(1, tailer);
        }
    }

    @Test
    public void codeWithinPriorDocumentMustExecuteBeforeSubsequentDocumentWhenQueueIsNotEmpty() throws Exception {
        try (final SingleChronicleQueue queue =
                     builder(DirectoryUtils.tempDir("document-ordering"), 3_000L).build()) {

            final ExcerptAppender excerptAppender = queue.acquireAppender();
            excerptAppender.writeDocument("foo", ValueOut::text);
            final Future<RecordInfo> otherDocumentWriter;
            try (final DocumentContext documentContext = excerptAppender.writingDocument()) {

                // move time to beyond the next cycle
                clock.addAndGet(TimeUnit.SECONDS.toMillis(2L));

                otherDocumentWriter = attemptToWriteDocument(queue);

                // stall this thread, other thread should not be able to advance,
                // since this DocumentContext is still open
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2L));

                documentContext.wire().getValueOut().int32(counter.getAndIncrement());
            }

            assertEquals(1, otherDocumentWriter.get(5L, TimeUnit.SECONDS).counterValue);

            final ExcerptTailer tailer = queue.createTailer();
            final DocumentContext documentContext = tailer.readingDocument();
            assertTrue(documentContext.isPresent());
            documentContext.close();
            expectValue(0, tailer);
            expectValue(1, tailer);
        }
    }

    @After
    public void tearDown() {
        executorService.shutdownNow();
    }

    private Future<RecordInfo> attemptToWriteDocument(final SingleChronicleQueue queue) throws InterruptedException {
        final CountDownLatch startedLatch = new CountDownLatch(1);
        final Future<RecordInfo> future = executorService.submit(() -> {
            final int counterValue;
            startedLatch.countDown();
            try (final DocumentContext documentContext = queue.acquireAppender().writingDocument()) {
                counterValue = counter.getAndIncrement();
                documentContext.wire().getValueOut().int32(counterValue);
            }
            return new RecordInfo(counterValue);
        });
        assertTrue("Task did not start", startedLatch.await(1, TimeUnit.MINUTES));
        return future;
    }

    private SingleChronicleQueueBuilder builder(final File dir, final long timeoutMS) {
        return SingleChronicleQueueBuilder.binary(dir).
                testBlockSize().rollCycle(ROLL_CYCLE).
                progressOnContention(progressOnContention).
                timeProvider(clock::get).timeoutMS(timeoutMS);
    }

    private static final class RecordInfo {
        private final int counterValue;

        RecordInfo(final int counterValue) {
            this.counterValue = counterValue;
        }
    }
}