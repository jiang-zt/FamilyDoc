package com.itzixi.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ChatExecutorCapacityTest {
    @Test
    void fullExecutorRejectsAdditionalTask() throws Exception {
        // 使用真实配置方法，只缩小本测试的容量。
        var executor = new ChatAsyncConfig().chatStreamExecutor(1, 2, 1);

        // 不经过 Spring 容器，必须手动初始化。
        executor.initialize();

        var workersStarted = new CountDownLatch(2);
        var unblockWorkers = new CountDownLatch(1);
        var queuedTaskFinished = new CountDownLatch(1);

        var rejectedTaskRan = new AtomicBoolean(false);

        Runnable blockingTask = () -> {
            workersStarted.countDown();

            try {
                if (!unblockWorkers.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("工作线程等待放行超时");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            // 1. 核心线程被占用
            executor.execute(blockingTask);

            // 2. 队列被占用
            executor.execute(queuedTaskFinished::countDown);

            // 3. 扩展到第二个工作线程(非核心线程 1)，也被占用;非核心线程：用完就“开除”
            executor.execute(blockingTask);

            assertThat(workersStarted.await(2, TimeUnit.SECONDS))
                    .as("两个工作线程应已开始执行阻塞任务")
                    .isTrue();
            
            assertThat(executor.getPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().size()).isEqualTo(1);

            // 4. 第四个任务必须拒绝，不能继续排队。
            assertThatThrownBy(() ->
                    executor.execute(() -> rejectedTaskRan.set(true))
            ).isInstanceOf(TaskRejectedException.class);

            assertThat(
                    executor.getThreadPoolExecutor()
                            .getQueue().size()
            ).isEqualTo(1);

            // 5. 放行已接受任务，确认队列中的任务能继续执行。
            unblockWorkers.countDown();
            //队列中的任务就是queuedTaskFinished::countDown
            assertThat(queuedTaskFinished.await(2, TimeUnit.SECONDS)).isTrue();//队列中的任务执行完

            // 等已接受任务全部退出，再确认拒绝任务从未执行。
            executor.getThreadPoolExecutor().shutdown();//温柔地关闭线程池

            assertThat(
                executor.getThreadPoolExecutor()
                        .awaitTermination(3, TimeUnit.SECONDS)
            ).isTrue();
            assertThat(rejectedTaskRan.get()).isFalse();//验证一个“被拒绝的任务”到底有没有偷偷执行

        }finally {
            unblockWorkers.countDown();
            executor.shutdown();//只要线程池没有被 shutdown()，核心线程就永远不会死
        }
    }
}
