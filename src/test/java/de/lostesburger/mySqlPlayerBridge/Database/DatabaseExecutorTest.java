package de.lostesburger.mySqlPlayerBridge.Database;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExecutorTest {
    @Test
    void slowDatabaseWorkNeverBlocksTheSubmittingThread() throws Exception {
        DatabaseExecutor executor = new DatabaseExecutor(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<String> work = executor.supply(() -> {
                started.countDown();
                if (!release.await(2L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test gate timed out");
                }
                return Thread.currentThread().getName();
            });

            assertTrue(started.await(1L, TimeUnit.SECONDS));
            assertFalse(work.isDone());
            release.countDown();
            assertTrue(work.get(1L, TimeUnit.SECONDS).startsWith("MySqlPlayerBridge-Database-"));
        } finally {
            release.countDown();
            assertTrue(executor.shutdown(Duration.ofSeconds(1L)));
        }
    }
}
