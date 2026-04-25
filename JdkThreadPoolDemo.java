import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class JdkThreadPoolDemo {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0].toLowerCase();

        switch (mode) {
            case "fixed" -> runFixedThreadPool();
            case "cached" -> runCachedThreadPool();
            case "single" -> runSingleThreadExecutor();
            case "scheduled" -> runScheduledThreadPool();
            case "custom" -> runCustomThreadPool();
            case "workstealing" -> runWorkStealingPool();
            case "all" -> {
                runFixedThreadPool();
                runCachedThreadPool();
                runSingleThreadExecutor();
                runScheduledThreadPool();
                runCustomThreadPool();
                runWorkStealingPool();
            }
            default -> printUsage();
        }
    }

    private static void runFixedThreadPool() {
        printBanner("newFixedThreadPool");
        ExecutorService pool = Executors.newFixedThreadPool(3, namedThreadFactory("fixed"));
        try {
            IntStream.rangeClosed(1, 6).forEach(taskId ->
                    pool.submit(() -> runTask("fixed", taskId, 400)));
        } finally {
            shutdownAndAwait(pool, "fixed");
        }
    }

    private static void runCachedThreadPool() {
        printBanner("newCachedThreadPool");
        ExecutorService pool = Executors.newCachedThreadPool(namedThreadFactory("cached"));
        try {
            IntStream.rangeClosed(1, 6).forEach(taskId ->
                    pool.submit(() -> runTask("cached", taskId, 250)));
        } finally {
            shutdownAndAwait(pool, "cached");
        }
    }

    private static void runSingleThreadExecutor() {
        printBanner("newSingleThreadExecutor");
        ExecutorService pool = Executors.newSingleThreadExecutor(namedThreadFactory("single"));
        try {
            IntStream.rangeClosed(1, 5).forEach(taskId ->
                    pool.submit(() -> runTask("single", taskId, 300)));
        } finally {
            shutdownAndAwait(pool, "single");
        }
    }

    private static void runScheduledThreadPool() throws InterruptedException {
        printBanner("newScheduledThreadPool");
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, namedThreadFactory("scheduled"));
        CountDownLatch latch = new CountDownLatch(3);

        ScheduledFuture<?> future = pool.scheduleAtFixedRate(() -> {
            log("scheduled", "tick");
            latch.countDown();
        }, 0, 1, TimeUnit.SECONDS);

        try {
            pool.schedule(() -> log("scheduled", "delayed task executed"), 1500, TimeUnit.MILLISECONDS);
            latch.await(5, TimeUnit.SECONDS);
        } finally {
            future.cancel(false);
            shutdownAndAwait(pool, "scheduled");
        }
    }

    private static void runCustomThreadPool() {
        printBanner("custom ThreadPoolExecutor");

        RejectedExecutionHandler handler = (task, executor) ->
                log("custom", "task rejected, queue is full. task=" + task);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                4,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                namedThreadFactory("custom"),
                handler
        );

        try {
            IntStream.rangeClosed(1, 8).forEach(taskId -> {
                pool.execute(() -> {
                    log("custom", "poolSize=" + pool.getPoolSize()
                            + ", active=" + pool.getActiveCount()
                            + ", queue=" + pool.getQueue().size());
                    runTask("custom", taskId, 700);
                });
            });
        } finally {
            shutdownAndAwait(pool, "custom");
        }
    }

    private static void runWorkStealingPool() throws InterruptedException, ExecutionException {
        printBanner("newWorkStealingPool");
        ExecutorService pool = Executors.newWorkStealingPool();
        try {
            List<Callable<String>> tasks = IntStream.rangeClosed(1, 6)
                    .mapToObj(taskId -> (Callable<String>) () -> {
                        runTask("workstealing", taskId, 300);
                        return "result-" + taskId;
                    })
                    .toList();

            List<Future<String>> results = pool.invokeAll(tasks);
            for (Future<String> result : results) {
                log("workstealing", "completed " + result.get());
            }
        } finally {
            shutdownAndAwait(pool, "workstealing");
        }
    }

    private static void runTask(String poolName, int taskId, long sleepMillis) {
        log(poolName, "task-" + taskId + " start");
        sleep(sleepMillis);
        log(poolName, "task-" + taskId + " end");
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-pool-" + counter.getAndIncrement());
            return thread;
        };
    }

    private static void shutdownAndAwait(ExecutorService pool, String poolName) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                log(poolName, "forcing shutdown");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printBanner(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }

    private static void printUsage() {
        System.out.println("Usage: javac JdkThreadPoolDemo.java && java JdkThreadPoolDemo [mode]");
        System.out.println("mode: fixed | cached | single | scheduled | custom | workstealing | all");
        System.out.println("Note: Executors factory methods are convenient for demos,");
        System.out.println("but production code usually prefers an explicit ThreadPoolExecutor.");
    }

    private static void log(String poolName, String message) {
        System.out.printf("%s [%s] [%s] %s%n",
                LocalTime.now(),
                poolName,
                Thread.currentThread().getName(),
                message);
    }
}
