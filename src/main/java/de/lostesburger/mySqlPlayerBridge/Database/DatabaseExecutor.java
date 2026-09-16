package de.lostesburger.mySqlPlayerBridge.Database;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class DatabaseExecutor implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 1_024;

    private final ThreadPoolExecutor executor;

    public DatabaseExecutor(int parallelism) {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "MySqlPlayerBridge-Database-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<Void> run(DatabaseRunnable runnable) {
        return supply(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> supply(DatabaseSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public int queuedTaskCount() {
        return this.executor.getQueue().size();
    }

    public boolean shutdown(Duration timeout) {
        this.executor.shutdown();
        try {
            if (this.executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            this.executor.shutdownNow();
            return this.executor.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
            return false;
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(10L));
    }

    @FunctionalInterface
    public interface DatabaseRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface DatabaseSupplier<T> {
        T get() throws Exception;
    }
}
