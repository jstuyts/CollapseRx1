/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.hystrix.junit.HystrixRequestContextRule;
import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.AbstractCommand.TryableSemaphore;
import com.netflix.hystrix.AbstractCommand.TryableSemaphoreActual;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import rx.*;
import rx.functions.*;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@SuppressWarnings("ConstantConditions")
public class HystrixCommandTest extends CommonHystrixCommandTests<TestHystrixCommand<Integer>> {
    @Rule
    public HystrixRequestContextRule ctx = new HystrixRequestContextRule();

    @After
    public void cleanup() {
        // force properties to be clean as well
        ConfigurationManager.getConfigInstance().clear();

        HystrixCommandKey key = Hystrix.getCurrentThreadExecutingCommand();
        if (key != null) {
            System.out.println("WARNING: Hystrix.getCurrentThreadExecutingCommand() should be null but got: " + key + ". Can occur when calling queue() and never retrieving.");
        }
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testExecutionSuccess() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS);
        assertEquals(FlexibleTestHystrixCommand.EXECUTE_VALUE, command.execute());

        assertNull(command.getFailedExecutionException());
        assertNull(command.getExecutionException());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());

        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
    }

    /**
     * Test that a command can not be executed multiple times.
     */
    @Test
    public void testExecutionMultipleTimes() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS);
        assertFalse(command.isExecutionComplete());
        // first should succeed
        assertEquals(FlexibleTestHystrixCommand.EXECUTE_VALUE, command.execute());
        assertTrue(command.isExecutionComplete());
        assertTrue(command.isExecutedInThread());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());
        assertNull(command.getExecutionException());

        try {
            // second should fail
            command.execute();
            fail("we should not allow this");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            // we want to get here
        }

        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
    }

    /**
     * Test a command execution that throws an HystrixException.
     */
    @Test
    public void testExecutionHystrixFailure() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.HYSTRIX_FAILURE);
        try {
            command.execute();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            assertNotNull(e.getImplementingClass());
        }
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());
    }

    /**
     * Test a command execution that throws an unknown exception (not HystrixException).
     */
    @Test
    public void testExecutionFailureWith() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.FAILURE);
        try {
            command.execute();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            assertNotNull(e.getImplementingClass());
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());
    }
    
    /**
     * Test a command execution that throws an exception that should not be wrapped.
     */
    @Test
    public void testNotWrappedException() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.NOT_WRAPPED_FAILURE);
        try {
            command.execute();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            fail("we shouldn't get a HystrixRuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e instanceof NotWrappedByHystrixTestRuntimeException);
        }
        
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());
        assertTrue(command.getExecutionException() instanceof NotWrappedByHystrixTestRuntimeException);
    }

    /**
     * Test a command execution that throws an exception that should not be wrapped.
     */
    @Test
    public void testNotWrappedBadRequest() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.BAD_REQUEST_NOT_WRAPPED);
        try {
            command.execute();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            fail("we shouldn't get a HystrixRuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e instanceof NotWrappedByHystrixTestRuntimeException);
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.getEventCounts().contains(HystrixEventType.BAD_REQUEST));
        assertCommandExecutionEvents(command, HystrixEventType.BAD_REQUEST);
        assertNotNull(command.getExecutionException());
        assertTrue(command.getExecutionException() instanceof HystrixBadRequestException);
        assertTrue(command.getExecutionException().getCause() instanceof NotWrappedByHystrixTestRuntimeException);
    }

    /**
     * Test a successful command execution (asynchronously).
     */
    @Test
    public void testQueueSuccess() throws Exception {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS);
        Future<Integer> future = command.queue();
        assertEquals(FlexibleTestHystrixCommand.EXECUTE_VALUE, future.get());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());
        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
        assertNull(command.getExecutionException());
    }

    /**
     * Test a command execution (asynchronously) that throws an HystrixException.
     */
    @Test
    public void testQueueKnownFailure() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.HYSTRIX_FAILURE);
        try {
            command.queue().get();
            fail("we shouldn't get here");
        } catch (Exception e) {
            e.printStackTrace();
            if (e.getCause() instanceof HystrixRuntimeException) {
                HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();

                assertNotNull(de.getImplementingClass());
            } else {
                fail("the cause should be HystrixRuntimeException");
            }
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());
    }

    /**
     * Test a command execution (asynchronously) that throws an unknown exception (not HystrixException).
     */
    @Test
    public void testQueueUnknownFailure() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.FAILURE);
        try {
            command.queue().get();
            fail("we shouldn't get here");
        } catch (Exception e) {
            e.printStackTrace();
            if (e.getCause() instanceof HystrixRuntimeException) {
                HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();
                assertNotNull(de.getImplementingClass());
            } else {
                fail("the cause should be HystrixRuntimeException");
            }
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testObserveSuccess() {
        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS);
        assertEquals(FlexibleTestHystrixCommand.EXECUTE_VALUE, command.observe().toBlocking().single());
        assertNull(command.getFailedExecutionException());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());
        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
        assertNull(command.getExecutionException());
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testCallbackThreadForThreadIsolation() throws Exception {

        final AtomicReference<Thread> commandThread = new AtomicReference<>();
        final AtomicReference<Thread> subscribeThread = new AtomicReference<>();

        TestHystrixCommand<Boolean> command = new TestHystrixCommand<Boolean>(TestHystrixCommand.testPropsBuilder()) {

            @Override
            protected Boolean run() {
                commandThread.set(Thread.currentThread());
                return true;
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);

        command.toObservable().subscribe(new Observer<Boolean>() {

            @Override
            public void onCompleted() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                latch.countDown();
                e.printStackTrace();
            }

            @Override
            public void onNext(Boolean args) {
                subscribeThread.set(Thread.currentThread());
            }
        });

        if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
            fail("timed out");
        }

        assertNotNull(commandThread.get());
        assertNotNull(subscribeThread.get());

        System.out.println("Command Thread: " + commandThread.get());
        System.out.println("Subscribe Thread: " + subscribeThread.get());

        assertTrue(commandThread.get().getName().startsWith("hystrix-"));
        assertTrue(subscribeThread.get().getName().startsWith("hystrix-"));
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testCallbackThreadForSemaphoreIsolation() throws Exception {

        final AtomicReference<Thread> commandThread = new AtomicReference<>();
        final AtomicReference<Thread> subscribeThread = new AtomicReference<>();

        TestHystrixCommand<Boolean> command = new TestHystrixCommand<Boolean>(TestHystrixCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))) {

            @Override
            protected Boolean run() {
                commandThread.set(Thread.currentThread());
                return true;
            }
        };

        final CountDownLatch latch = new CountDownLatch(1);

        command.toObservable().subscribe(new Observer<Boolean>() {

            @Override
            public void onCompleted() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                latch.countDown();
                e.printStackTrace();
            }

            @Override
            public void onNext(Boolean args) {
                subscribeThread.set(Thread.currentThread());
            }
        });

        if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
            fail("timed out");
        }

        assertNotNull(commandThread.get());
        assertNotNull(subscribeThread.get());

        System.out.println("Command Thread: " + commandThread.get());
        System.out.println("Subscribe Thread: " + subscribeThread.get());

        String mainThreadName = Thread.currentThread().getName();

        // semaphore should be on the calling thread
        assertEquals(commandThread.get().getName(), mainThreadName);
        assertEquals(subscribeThread.get().getName(), mainThreadName);
    }


    /**
     * Test when a command fails to get queued up in the threadpool.
     * <p>
     * We specifically want to protect against developers getting random thread exceptions and instead just correctly receiving HystrixRuntimeExceptions.
     */
    @Test
    public void testRejectedThread() throws Exception {
        HystrixCommandKey key = HystrixCommandKey.Factory.asKey("Rejection");
        SingleThreadedPoolWithQueue pool = new SingleThreadedPoolWithQueue(1);
        // fill up the queue
        pool.queue.add(() -> {
            System.out.println("**** queue filler1 ****");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        Future<Boolean> f = null;
        TestCommandRejection command1 = null;
        TestCommandRejection command2 = null;
        try {
            command1 = new TestCommandRejection(key, pool, 500);
            f = command1.queue();
            command2 = new TestCommandRejection(key, pool, 500);
            command2.queue();
            fail("we shouldn't get here");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("command.getExecutionTimeInMilliseconds(): " + command2.getExecutionTimeInMilliseconds());
            // will be -1 because it never attempted execution
            assertTrue(command2.isResponseRejected());
            assertNotNull(command2.getExecutionException());

            if (e instanceof HystrixRuntimeException && e.getCause() instanceof RejectedExecutionException) {
                HystrixRuntimeException de = (HystrixRuntimeException) e;
                assertNotNull(de.getImplementingClass());
                assertNotNull(de.getCause());
                assertTrue(de.getCause() instanceof RejectedExecutionException);
            } else {
                fail("the exception should be HystrixRuntimeException with cause as RejectedExecutionException");
            }
        }

        f.get();

        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
    }

    /**
     * Test that we can reject a thread using isQueueSpaceAvailable() instead of just when the pool rejects.
     * <p>
     * For example, we have queue size set to 100 but want to reject when we hit 10.
     * <p>
     * This allows us to use FastProperties to control our rejection point whereas we can't resize a queue after it's created.
     */
    @Test
    public void testRejectedThreadUsingQueueSize() {
        HystrixCommandKey key = HystrixCommandKey.Factory.asKey("Rejection-B");
        SingleThreadedPoolWithQueue pool = new SingleThreadedPoolWithQueue(10, 1);
        // put 1 item in the queue
        // the thread pool won't pick it up because we're bypassing the pool and adding to the queue directly so this will keep the queue full

        pool.queue.add(() -> {
            System.out.println("**** queue filler1 ****");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });


        TestCommandRejection command = new TestCommandRejection(key, pool, 500);
        try {
            // this should fail as we already have 1 in the queue
            command.queue();
            fail("we shouldn't get here");
        } catch (Exception e) {
            e.printStackTrace();

            assertTrue(command.isResponseRejected());
            assertNotNull(command.getExecutionException());

            if (e instanceof HystrixRuntimeException && e.getCause() instanceof RejectedExecutionException) {
                HystrixRuntimeException de = (HystrixRuntimeException) e;
                assertNotNull(de.getImplementingClass());
                assertNotNull(de.getCause());
                assertTrue(de.getCause() instanceof RejectedExecutionException);
            } else {
                fail("the exception should be HystrixRuntimeException with cause as RejectedExecutionException");
            }
        }

        assertCommandExecutionEvents(command, HystrixEventType.THREAD_POOL_REJECTED);
    }

    @Test
    public void testExecutionSemaphoreWithQueue() throws Exception {
        // single thread should work
        TestSemaphoreCommand command1 = new TestSemaphoreCommand(1, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        boolean result = command1.queue().get();
        assertTrue(result);

        final AtomicBoolean exceptionReceived = new AtomicBoolean();

        final TryableSemaphore semaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        final TestSemaphoreCommand command2 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r2 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                command2.queue().get();
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });
        final TestSemaphoreCommand command3 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r3 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                command3.queue().get();
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        // 2 threads, the second should be rejected by the semaphore
        Thread t2 = new Thread(r2);
        Thread t3 = new Thread(r3);

        t2.start();
        // make sure that t2 gets a chance to run before queuing the next one
        Thread.sleep(50);
        t3.start();
        t2.join();
        t3.join();

        if (!exceptionReceived.get()) {
            fail("We expected an exception on the 2nd get");
        }

        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SEMAPHORE_REJECTED);
    }

    @Test
    public void testExecutionSemaphoreWithExecution() throws Exception {
        // single thread should work
        TestSemaphoreCommand command1 = new TestSemaphoreCommand(1, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        boolean result = command1.execute();
        assertFalse(command1.isExecutedInThread());
        assertTrue(result);

        final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<>(2);

        final AtomicBoolean exceptionReceived = new AtomicBoolean();

        final TryableSemaphore semaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        final TestSemaphoreCommand command2 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r2 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                results.add(command2.execute());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });
        final TestSemaphoreCommand command3 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r3 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                results.add(command3.execute());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        // 2 threads, the second should be rejected by the semaphore
        Thread t2 = new Thread(r2);
        Thread t3 = new Thread(r3);

        t2.start();
        // make sure that t2 gets a chance to run before queuing the next one
        Thread.sleep(50);
        t3.start();
        t2.join();
        t3.join();

        if (!exceptionReceived.get()) {
            fail("We expected an exception on the 2nd get");
        }

        // only 1 value is expected as the other should have thrown an exception
        assertEquals(1, results.size());
        // should contain only a true result
        assertTrue(results.contains(Boolean.TRUE));
        assertFalse(results.contains(Boolean.FALSE));
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SEMAPHORE_REJECTED);
    }

    /**
     * Tests that semaphores are counted separately for commands with unique keys
     */
    @Test
    public void testSemaphorePermitsInUse() throws Exception {
        // this semaphore will be shared across multiple command instances
        final TryableSemaphoreActual sharedSemaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(3));

        // used to wait until all commands have started
        final CountDownLatch startLatch = new CountDownLatch((sharedSemaphore.numberOfPermits.get() * 2) + 1);

        // used to signal that all command can finish
        final CountDownLatch sharedLatch = new CountDownLatch(1);

        // tracks failures to obtain semaphores
        final AtomicInteger failureCount = new AtomicInteger();

        final Runnable sharedSemaphoreRunnable = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                new LatchedSemaphoreCommand("Command-Shared", sharedSemaphore, startLatch, sharedLatch).execute();
            } catch (Exception e) {
                startLatch.countDown();
                e.printStackTrace();
                failureCount.incrementAndGet();
            }
        });

        // creates group of threads each using command sharing a single semaphore
        // I create extra threads and commands so that I can verify that some of them fail to obtain a semaphore
        final int sharedThreadCount = sharedSemaphore.numberOfPermits.get() * 2;
        final Thread[] sharedSemaphoreThreads = new Thread[sharedThreadCount];
        for (int i = 0; i < sharedThreadCount; i++) {
            sharedSemaphoreThreads[i] = new Thread(sharedSemaphoreRunnable);
        }

        // creates thread using isolated semaphore
        final TryableSemaphoreActual isolatedSemaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        final CountDownLatch isolatedLatch = new CountDownLatch(1);

        final Thread isolatedThread = new Thread(new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                new LatchedSemaphoreCommand("Command-Isolated", isolatedSemaphore, startLatch, isolatedLatch).execute();
            } catch (Exception e) {
                startLatch.countDown();
                e.printStackTrace();
                failureCount.incrementAndGet();
            }
        }));

        // verifies no permits in use before starting threads
        assertEquals("before threads start, shared semaphore should be unused", 0, sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("before threads start, isolated semaphore should be unused", 0, isolatedSemaphore.getNumberOfPermitsUsed());

        for (int i = 0; i < sharedThreadCount; i++) {
            sharedSemaphoreThreads[i].start();
        }
        isolatedThread.start();

        // waits until all commands have started
        startLatch.await(1000, TimeUnit.MILLISECONDS);

        // verifies that all semaphores are in use
        assertEquals("immediately after command start, all shared semaphores should be in-use",
                sharedSemaphore.numberOfPermits.get().longValue(), sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("immediately after command start, isolated semaphore should be in-use",
                isolatedSemaphore.numberOfPermits.get().longValue(), isolatedSemaphore.getNumberOfPermitsUsed());

        // signals commands to finish
        sharedLatch.countDown();
        isolatedLatch.countDown();

        for (int i = 0; i < sharedThreadCount; i++) {
            sharedSemaphoreThreads[i].join();
        }
        isolatedThread.join();

        assertEquals("after all threads have finished, no shared semaphores should be in-use", 0, sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("after all threads have finished, isolated semaphore not in-use", 0, isolatedSemaphore.getNumberOfPermitsUsed());

        // verifies that some executions failed
        assertEquals("expected some of shared semaphore commands to get rejected", sharedSemaphore.numberOfPermits.get().longValue(), failureCount.get());
    }

    /**
     * Test that HystrixOwner can be passed in dynamically.
     */
    @Test
    public void testDynamicOwner() {
        TestHystrixCommand<Boolean> command = new DynamicOwnerTestCommand(InspectableBuilder.CommandGroupForUnitTest.OWNER_ONE);
        assertEquals(true, command.execute());
        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
    }

    /**
     * Test a successful command execution.
     */
    @Test(expected = IllegalStateException.class)
    public void testDynamicOwnerFails() {
        TestHystrixCommand<Boolean> command = new DynamicOwnerTestCommand(null);
        assertEquals(true, command.execute());
    }

    /**
     * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
     */
    @Test
    public void testRequestCache1() throws Exception {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();

        assertEquals("A", f1.get());
        assertEquals("A", f2.get());

        assertTrue(command1.executed);
        // the second one should not have executed as it should have received the cached value instead
        assertFalse(command2.executed);
        assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command1.isResponseFromCache());
        assertTrue(command2.isResponseFromCache());
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test Request scoped caching doesn't prevent different ones from executing
     */
    @Test
    public void testRequestCache2() throws Exception {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "B");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();

        assertEquals("A", f1.get());
        assertEquals("B", f2.get());

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        assertTrue(command2.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command2.isResponseFromCache());
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertNull(command1.getExecutionException());
        assertFalse(command2.isResponseFromCache());
        assertNull(command2.getExecutionException());
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testRequestCache3() throws Exception {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "B");
        SuccessfulCacheableCommand<String> command3 = new SuccessfulCacheableCommand<>(true, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();
        Future<String> f3 = command3.queue();
        assertEquals("A", f1.get());
        assertEquals("B", f2.get());
        assertEquals("A", f3.get());

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // but the 3rd should come from cache
        assertFalse(command3.executed);
        assertTrue(command3.isResponseFromCache());
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
     */
    @Test
    public void testRequestCacheWithSlowExecution() throws Exception {
        SlowCacheableCommand command1 = new SlowCacheableCommand("A", 200);
        SlowCacheableCommand command2 = new SlowCacheableCommand("A", 100);
        SlowCacheableCommand command3 = new SlowCacheableCommand("A", 100);
        SlowCacheableCommand command4 = new SlowCacheableCommand("A", 100);

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();
        Future<String> f3 = command3.queue();
        Future<String> f4 = command4.queue();

        assertEquals("A", f2.get());
        assertEquals("A", f3.get());
        assertEquals("A", f4.get());
        assertEquals("A", f1.get());

        assertTrue(command1.executed);
        // the second one should not have executed as it should have received the cached value instead
        assertFalse(command2.executed);
        assertFalse(command3.executed);
        assertFalse(command4.executed);

        assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command1.isResponseFromCache());
        assertEquals(command2.getExecutionTimeInMilliseconds(), -1);
        assertTrue(command2.isResponseFromCache());
        assertTrue(command3.isResponseFromCache());
        assertEquals(command3.getExecutionTimeInMilliseconds(), -1);
        assertTrue(command4.isResponseFromCache());
        assertEquals(command4.getExecutionTimeInMilliseconds(), -1);
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertCommandExecutionEvents(command4, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testNoRequestCache3() throws Exception {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(false, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(false, "B");
        SuccessfulCacheableCommand<String> command3 = new SuccessfulCacheableCommand<>(false, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();
        Future<String> f3 = command3.queue();

        assertEquals("A", f1.get());
        assertEquals("B", f2.get());
        assertEquals("A", f3.get());

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // this should also execute since we disabled the cache
        assertTrue(command3.executed);

        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS);
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testRequestCacheViaQueueSemaphore1() throws Exception {
        SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(true, "A");
        SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(true, "B");
        SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(true, "A");

        assertFalse(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();
        Future<String> f3 = command3.queue();

        assertEquals("A", f1.get());
        assertEquals("B", f2.get());
        assertEquals("A", f3.get());

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // but the 3rd should come from cache
        assertFalse(command3.executed);
        assertTrue(command3.isResponseFromCache());
        assertEquals(command3.getExecutionTimeInMilliseconds(), -1);
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testNoRequestCacheViaQueueSemaphore1() throws Exception {
        SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(false, "A");
        SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(false, "B");
        SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(false, "A");

        assertFalse(command1.isCommandRunningInThread());

        Future<String> f1 = command1.queue();
        Future<String> f2 = command2.queue();
        Future<String> f3 = command3.queue();

        assertEquals("A", f1.get());
        assertEquals("B", f2.get());
        assertEquals("A", f3.get());

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // this should also execute because caching is disabled
        assertTrue(command3.executed);
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS);
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testRequestCacheViaExecuteSemaphore1() {
        SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(true, "A");
        SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(true, "B");
        SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(true, "A");

        assertFalse(command1.isCommandRunningInThread());

        String f1 = command1.execute();
        String f2 = command2.execute();
        String f3 = command3.execute();

        assertEquals("A", f1);
        assertEquals("B", f2);
        assertEquals("A", f3);

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // but the 3rd should come from cache
        assertFalse(command3.executed);
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testNoRequestCacheViaExecuteSemaphore1() {
        SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(false, "A");
        SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(false, "B");
        SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(false, "A");

        assertFalse(command1.isCommandRunningInThread());

        String f1 = command1.execute();
        String f2 = command2.execute();
        String f3 = command3.execute();

        assertEquals("A", f1);
        assertEquals("B", f2);
        assertEquals("A", f3);

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // this should also execute because caching is disabled
        assertTrue(command3.executed);
        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SUCCESS);
    }

    @Test
    public void testRequestCacheOnThreadRejectionThrowsException() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);
        RequestCacheThreadRejection r1 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r1: " + r1.execute());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            assertTrue(r1.isResponseRejected());
            // what we want
        }

        RequestCacheThreadRejection r2 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r2: " + r2.execute());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            //                e.printStackTrace();
            assertTrue(r2.isResponseRejected());
            // what we want
        }

        RequestCacheThreadRejection r3 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("f3: " + r3.queue().get());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            //                e.printStackTrace();
            assertTrue(r3.isResponseRejected());
            // what we want
        }

        // let the command finish (only 1 should actually be blocked on this due to the response cache)
        completionLatch.countDown();

        // then another after the command has completed
        RequestCacheThreadRejection r4 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r4: " + r4.execute());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            //                e.printStackTrace();
            assertTrue(r4.isResponseRejected());
            // what we want
        }

        assertCommandExecutionEvents(r1, HystrixEventType.THREAD_POOL_REJECTED);
        assertCommandExecutionEvents(r2, HystrixEventType.THREAD_POOL_REJECTED, HystrixEventType.RESPONSE_FROM_CACHE);
        assertCommandExecutionEvents(r3, HystrixEventType.THREAD_POOL_REJECTED, HystrixEventType.RESPONSE_FROM_CACHE);
        assertCommandExecutionEvents(r4, HystrixEventType.THREAD_POOL_REJECTED, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test that we can do basic execution without a RequestVariable being initialized.
     */
    @Test
    public void testBasicExecutionWorksWithoutRequestVariable() throws Exception {
        /* force the RequestVariable to not be initialized */
        HystrixRequestContext.setContextOnCurrentThread(null);

        TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
        assertEquals(true, command.execute());

        TestHystrixCommand<Boolean> command2 = new SuccessfulTestCommand();
        assertEquals(true, command2.queue().get());
    }

    /**
     * Test that if we try and execute a command with a cacheKey without initializing RequestVariable that it gives an error.
     */
    @Test(expected = HystrixRuntimeException.class)
    public void testCacheKeyExecutionRequiresRequestVariable() throws Exception {
        /* force the RequestVariable to not be initialized */
        HystrixRequestContext.setContextOnCurrentThread(null);

        SuccessfulCacheableCommand command = new SuccessfulCacheableCommand<>(true, "one");
        assertEquals("one", command.execute());

        SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand<>(true, "two");
        assertEquals("two", command2.queue().get());
    }

    /**
     * Test that a BadRequestException can be thrown and not count towards errors.
     */
    @Test
    public void testBadRequestExceptionViaExecuteInThread() {
        BadRequestCommand command1 = null;
        try {
            command1 = new BadRequestCommand(ExecutionIsolationStrategy.THREAD);
            command1.execute();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (HystrixBadRequestException e) {
            // success
            e.printStackTrace();
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
    }

    /**
     * Test that a BadRequestException can be thrown and not count towards errors.
     */
    @Test
    public void testBadRequestExceptionViaQueueInThread() throws Exception {
        BadRequestCommand command1 = null;
        try {
            command1 = new BadRequestCommand(ExecutionIsolationStrategy.THREAD);
            command1.queue().get();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (ExecutionException e) {
            e.printStackTrace();
            if (!(e.getCause() instanceof HystrixBadRequestException)) {
                fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
            }
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
        assertNotNull(command1.getExecutionException());
    }

    /**
     * Test that BadRequestException behavior works the same on a cached response.
     */
    @Test
    public void testBadRequestExceptionViaQueueInThreadOnResponseFromCache() throws Exception {
        // execute once to cache the value
        BadRequestCommand command1 = null;
        try {
            command1 = new BadRequestCommand(ExecutionIsolationStrategy.THREAD);
            command1.execute();
        } catch (Throwable e) {
            // ignore
        }

        BadRequestCommand command2 = null;
        try {
            command2 = new BadRequestCommand(ExecutionIsolationStrategy.THREAD);
            command2.queue().get();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (ExecutionException e) {
            e.printStackTrace();
            if (!(e.getCause() instanceof HystrixBadRequestException)) {
                fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
            }
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
        assertCommandExecutionEvents(command2, HystrixEventType.BAD_REQUEST, HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Test that a BadRequestException can be thrown and not count towards errors.
     */
    @Test
    public void testBadRequestExceptionViaExecuteInSemaphore() {
        BadRequestCommand command1 = new BadRequestCommand(ExecutionIsolationStrategy.SEMAPHORE);
        try {
            command1.execute();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (HystrixBadRequestException e) {
            // success
            e.printStackTrace();
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
    }

    /**
     * Test a checked Exception being thrown
     */
    @Test
    public void testCheckedExceptionViaExecute() {
        CommandWithCheckedException command = new CommandWithCheckedException();
        try {
            command.execute();
            fail("we expect to receive a " + Exception.class.getSimpleName());
        } catch (Exception e) {
            assertEquals("simulated checked exception message", e.getCause().getMessage());
        }

        assertEquals("simulated checked exception message", command.getFailedExecutionException().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
    }

    /**
     * Test a java.lang.Error being thrown
     *
     * @throws InterruptedException
     */
    @Test
    public void testCheckedExceptionViaObserve() throws InterruptedException {
        CommandWithCheckedException command = new CommandWithCheckedException();
        final AtomicReference<Throwable> t = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            command.observe().subscribe(new Observer<Boolean>() {

                @Override
                public void onCompleted() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable e) {
                    t.set(e);
                    latch.countDown();
                }

                @Override
                public void onNext(Boolean args) {

                }

            });
        } catch (Exception e) {
            e.printStackTrace();
            fail("we should not get anything thrown, it should be emitted via the Observer#onError method");
        }

        latch.await(1, TimeUnit.SECONDS);
        assertNotNull(t.get());
        t.get().printStackTrace();

        assertTrue(t.get() instanceof HystrixRuntimeException);
        assertEquals("simulated checked exception message", t.get().getCause().getMessage());
        assertEquals("simulated checked exception message", command.getFailedExecutionException().getMessage());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
    }

    /**
     * Test an Exception implementing NotWrappedByHystrix being thrown
     *
     * @throws InterruptedException
     */
    @Test
    public void testNotWrappedExceptionViaObserve() throws InterruptedException {
        CommandWithNotWrappedByHystrixException command = new CommandWithNotWrappedByHystrixException();
        final AtomicReference<Throwable> t = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            command.observe().subscribe(new Observer<Boolean>() {

                @Override
                public void onCompleted() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable e) {
                    t.set(e);
                    latch.countDown();
                }

                @Override
                public void onNext(Boolean args) {

                }

            });
        } catch (Exception e) {
            e.printStackTrace();
            fail("we should not get anything thrown, it should be emitted via the Observer#onError method");
        }

        latch.await(1, TimeUnit.SECONDS);
        assertNotNull(t.get());
        t.get().printStackTrace();

        assertTrue(t.get() instanceof NotWrappedByHystrixTestException);
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertTrue(command.getExecutionException() instanceof NotWrappedByHystrixTestException);
    }

    /**
     * Test a recoverable java.lang.Error being thrown
     */
    @Test
    public void testRecoverableErrorThrowsError() {
        TestHystrixCommand<Integer> command = getRecoverableErrorCommand(ExecutionIsolationStrategy.THREAD);
        try {
            command.execute();
            fail("we expect to receive a " + Error.class.getSimpleName());
        } catch (Exception e) {
            // the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
            // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
            // so HystrixRuntimeException -> wrapper Exception -> actual Error
            assertEquals("Execution ERROR for TestHystrixCommand", e.getCause().getCause().getMessage());
        }

        assertEquals("Execution ERROR for TestHystrixCommand", command.getFailedExecutionException().getCause().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
    }

    @Test
    public void testRecoverableErrorLogged() {
        TestHystrixCommand<Integer> command = getRecoverableErrorCommand(ExecutionIsolationStrategy.THREAD);

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
    }

    @Test
    public void testUnrecoverableErrorThrown() {
        TestHystrixCommand<Integer> command = getUnrecoverableErrorCommand(ExecutionIsolationStrategy.THREAD);
        try {
            command.execute();
            fail("we expect to receive a " + Error.class.getSimpleName());
        } catch (Exception e) {
            // the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
            // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
            // so HystrixRuntimeException -> wrapper Exception -> actual Error
            assertEquals("Unrecoverable Error for TestHystrixCommand", e.getCause().getCause().getMessage());
        }

        assertEquals("Unrecoverable Error for TestHystrixCommand", command.getFailedExecutionException().getCause().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
    }

    @Override
    protected void assertHooksOnSuccess(Func0<TestHystrixCommand<Integer>> ctor, Action1<TestHystrixCommand<Integer>> assertion) {
        assertExecute(ctor.call(), assertion, true);
        assertBlockingQueue(ctor.call(), assertion, true);
        assertNonBlockingQueue(ctor.call(), assertion, true, false);
        assertBlockingObserve(ctor.call(), assertion, true);
        assertNonBlockingObserve(ctor.call(), assertion, true);
    }

    @Override
    protected void assertHooksOnFailure(Func0<TestHystrixCommand<Integer>> ctor, Action1<TestHystrixCommand<Integer>> assertion) {
        assertExecute(ctor.call(), assertion, false);
        assertBlockingQueue(ctor.call(), assertion, false);
        assertNonBlockingQueue(ctor.call(), assertion, false, false);
        assertBlockingObserve(ctor.call(), assertion, false);
        assertNonBlockingObserve(ctor.call(), assertion, false);
    }

    @Override
    protected void assertHooksOnFailure(Func0<TestHystrixCommand<Integer>> ctor, Action1<TestHystrixCommand<Integer>> assertion, boolean failFast) {
        assertExecute(ctor.call(), assertion, false);
        assertBlockingQueue(ctor.call(), assertion, false);
        assertNonBlockingQueue(ctor.call(), assertion, false, failFast);
        assertBlockingObserve(ctor.call(), assertion, false);
        assertNonBlockingObserve(ctor.call(), assertion, false);
    }

    /**
     * Run the command via {@link com.netflix.hystrix.HystrixCommand#execute()} and then assert
     * @param command command to run
     * @param assertion assertions to check
     * @param isSuccess should the command succeedInteger
     */
    private void assertExecute(TestHystrixCommand<Integer> command, Action1<TestHystrixCommand<Integer>> assertion, boolean isSuccess) {
        System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : Running command.execute() and then assertions...");
        if (isSuccess) {
            command.execute();
        } else {
            try {
                command.execute();
                fail("Expected a command failure!");
            } catch (Exception ex) {
                System.out.println("Received expected ex : " + ex);
                ex.printStackTrace();
            }
        }

        assertion.call(command);
    }

    /**
     * Run the command via {@link com.netflix.hystrix.HystrixCommand#queue()}, immediately block, and then assert
     * @param command command to run
     * @param assertion assertions to check
     * @param isSuccess should the command succeedInteger
     */
    private void assertBlockingQueue(TestHystrixCommand<Integer> command, Action1<TestHystrixCommand<Integer>> assertion, boolean isSuccess) {
        System.out.println("Running command.queue(), immediately blocking and then running assertions...");
        if (isSuccess) {
            try {
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                command.queue().get();
                fail("Expected a command failure!");
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            } catch (ExecutionException ee) {
                System.out.println("Received expected ex : " + ee.getCause());
                ee.getCause().printStackTrace();
            } catch (Exception e) {
                System.out.println("Received expected ex : " + e);
                e.printStackTrace();
            }
        }

        assertion.call(command);
    }

    /**
     * Run the command via {@link com.netflix.hystrix.HystrixCommand#queue()}, then poll for the command to be finished.
     * When it is finished, assert
     * @param command command to run
     * @param assertion assertions to check
     * @param isSuccess should the command succeedInteger
     */
    private void assertNonBlockingQueue(TestHystrixCommand<Integer> command, Action1<TestHystrixCommand<Integer>> assertion, boolean isSuccess, boolean failFast) {
        System.out.println("Running command.queue(), sleeping the test thread until command is complete, and then running assertions...");
        Future<Integer> f = null;
        if (failFast) {
            try {
                f = command.queue();
                fail("Expected a failure when queuing the command");
            } catch (Exception ex) {
                System.out.println("Received expected fail fast ex : " + ex);
                ex.printStackTrace();
            }
        } else {
            try {
                f = command.queue();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        awaitCommandCompletion(command);

        assertion.call(command);

        if (isSuccess) {
            try {
                f.get();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            try {
                f.get();
                fail("Expected a command failure!");
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            } catch (ExecutionException ee) {
                System.out.println("Received expected ex : " + ee.getCause());
                ee.getCause().printStackTrace();
            } catch (Exception e) {
                System.out.println("Received expected ex : " + e);
                e.printStackTrace();
            }
        }
    }

    private <T> void awaitCommandCompletion(TestHystrixCommand<T> command) {
        while (!command.isExecutionComplete()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException("interrupted");
            }
        }
    }

    /**
     * Test a command execution that fails.
     */
    @Test
    public void testExecutionFailure() {
        TestHystrixCommand<Boolean> commandEnabled = new KnownFailureTestCommand();
        try {
            assertEquals(false, commandEnabled.execute());
        } catch (Exception e) {
            e.printStackTrace();
        }

        TestHystrixCommand<Boolean> commandDisabled = new KnownFailureTestCommand();
        try {
            assertEquals(false, commandDisabled.execute());
            fail("expect exception thrown");
        } catch (Exception e) {
            // expected
        }

        assertEquals("we failed with a simulated issue", commandDisabled.getFailedExecutionException().getMessage());

        assertTrue(commandDisabled.isFailedExecution());
        assertCommandExecutionEvents(commandEnabled, HystrixEventType.FAILURE);
        assertCommandExecutionEvents(commandDisabled, HystrixEventType.FAILURE);
        assertNotNull(commandDisabled.getExecutionException());
    }

    @Test
    public void testCancelFutureWithInterruption() throws InterruptedException, ExecutionException {
    	// given
    	InterruptibleCommand cmd = new InterruptibleCommand(true);

        // when
        Future<Boolean> f = cmd.queue();
        Thread.sleep(500);
        f.cancel(true);
        Thread.sleep(500);

        // then
        try {
        	f.get();
        	fail("Should have thrown a CancellationException");
        } catch (CancellationException e) {
            // No action needed. This is the expected case.
        }
    }

    @Test
    public void testChainedCommand() {
        class PrimaryCommand extends TestHystrixCommand<Integer> {
            public PrimaryCommand() {
                super(testPropsBuilder());
            }

            @Override
            protected Integer run() {
                throw new RuntimeException("primary failure");
            }
        }

        assertEquals(2, (int) new PrimaryCommand().execute());
    }

    @Test
    public void testSemaphoreThreadSafety() {
        final int NUM_PERMITS = 1;
        final TryableSemaphoreActual s = new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(NUM_PERMITS));

        final int NUM_THREADS = 10;
        ExecutorService threadPool = Executors.newFixedThreadPool(NUM_THREADS);

        final int NUM_TRIALS = 100;

        for (int t = 0; t < NUM_TRIALS; t++) {

            System.out.println("TRIAL : " + t);

            final AtomicInteger numAcquired = new AtomicInteger(0);
            final CountDownLatch latch = new CountDownLatch(NUM_THREADS);

            for (int i = 0; i < NUM_THREADS; i++) {
                threadPool.submit(() -> {
                    boolean acquired = s.tryAcquire();
                    if (acquired) {
                        try {
                            numAcquired.incrementAndGet();
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        } finally {
                            s.release();
                        }
                    }
                    latch.countDown();
                });
            }

            try {
                assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));
            } catch (InterruptedException ex) {
                fail(ex.getMessage());
            }

            assertEquals("Number acquired should be equal to the number of permits", NUM_PERMITS, numAcquired.get());
            assertEquals("Semaphore should always get released back to 0", 0, s.getNumberOfPermitsUsed());
        }
    }

    @Test
    public void testCancelledTasksInQueueGetRemoved() throws Exception {
        HystrixCommandKey key = HystrixCommandKey.Factory.asKey("Cancellation-A");
        SingleThreadedPoolWithQueue pool = new SingleThreadedPoolWithQueue(10, 1);
        TestCommandRejection command1 = new TestCommandRejection(key, pool, 500);
        TestCommandRejection command2 = new TestCommandRejection(key, pool, 500);

        // this should go through the queue and into the thread pool
        Future<Boolean> poolFiller = command1.queue();
        // this command will stay in the queue until the thread pool is empty
        Observable<Boolean> cmdInQueue = command2.observe();
        Subscription s = cmdInQueue.subscribe();
        assertEquals(1, pool.queue.size());
        s.unsubscribe();
        assertEquals(0, pool.queue.size());
        //make sure we wait for the command to finish so the state is clean for next test
        poolFiller.get();

        assertCommandExecutionEvents(command1, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.CANCELLED);
    }

    @Test
    public void testOnRunStartHookThrowsSemaphoreIsolated() {
        final AtomicBoolean exceptionEncountered = new AtomicBoolean(false);
        final AtomicBoolean onThreadStartInvoked = new AtomicBoolean(false);
        final AtomicBoolean onThreadCompleteInvoked = new AtomicBoolean(false);
        final AtomicBoolean executionAttempted = new AtomicBoolean(false);

        class FailureInjectionHook extends HystrixCommandExecutionHook {
            @Override
            public void onExecutionStart(HystrixInvokable commandInstance) {
                throw new HystrixRuntimeException(HystrixRuntimeException.FailureType.COMMAND_EXCEPTION, commandInstance.getClass(), "Injected Failure", null);
            }

            @Override
            public void onThreadStart(HystrixInvokable commandInstance) {
                onThreadStartInvoked.set(true);
                super.onThreadStart(commandInstance);
            }

            @Override
            public void onThreadComplete(HystrixInvokable commandInstance) {
                onThreadCompleteInvoked.set(true);
                super.onThreadComplete(commandInstance);
            }
        }

        final FailureInjectionHook failureInjectionHook = new FailureInjectionHook();

        class FailureInjectedCommand extends TestHystrixCommand<Integer> {
            public FailureInjectedCommand(ExecutionIsolationStrategy isolationStrategy) {
                super(testPropsBuilder().setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationStrategy)), failureInjectionHook);
            }

            @Override
            protected Integer run() {
                executionAttempted.set(true);
                return 3;
            }
        }

        TestHystrixCommand<Integer> semaphoreCmd = new FailureInjectedCommand(ExecutionIsolationStrategy.SEMAPHORE);
        try {
            int result = semaphoreCmd.execute();
            System.out.println("RESULT : " + result);
        } catch (Throwable ex) {
            ex.printStackTrace();
            exceptionEncountered.set(true);
        }
        assertTrue(exceptionEncountered.get());
        assertFalse(onThreadStartInvoked.get());
        assertFalse(onThreadCompleteInvoked.get());
        assertFalse(executionAttempted.get());

    }

    @Test
    public void testOnRunStartHookThrowsThreadIsolated() {
        final AtomicBoolean exceptionEncountered = new AtomicBoolean(false);
        final AtomicBoolean onThreadStartInvoked = new AtomicBoolean(false);
        final AtomicBoolean onThreadCompleteInvoked = new AtomicBoolean(false);
        final AtomicBoolean executionAttempted = new AtomicBoolean(false);

        class FailureInjectionHook extends HystrixCommandExecutionHook {
            @Override
            public void onExecutionStart(HystrixInvokable commandInstance) {
                throw new HystrixRuntimeException(HystrixRuntimeException.FailureType.COMMAND_EXCEPTION, commandInstance.getClass(), "Injected Failure", null);
            }

            @Override
            public void onThreadStart(HystrixInvokable commandInstance) {
                onThreadStartInvoked.set(true);
                super.onThreadStart(commandInstance);
            }

            @Override
            public void onThreadComplete(HystrixInvokable commandInstance) {
                onThreadCompleteInvoked.set(true);
                super.onThreadComplete(commandInstance);
            }
        }

        final FailureInjectionHook failureInjectionHook = new FailureInjectionHook();

        class FailureInjectedCommand extends TestHystrixCommand<Integer> {
            public FailureInjectedCommand(ExecutionIsolationStrategy isolationStrategy) {
                super(testPropsBuilder().setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationStrategy)), failureInjectionHook);
            }

            @Override
            protected Integer run() {
                executionAttempted.set(true);
                return 3;
            }
        }

        TestHystrixCommand<Integer> threadCmd = new FailureInjectedCommand(ExecutionIsolationStrategy.THREAD);
        try {
            int result = threadCmd.execute();
            System.out.println("RESULT : " + result);
        } catch (Throwable ex) {
            ex.printStackTrace();
            exceptionEncountered.set(true);
        }
        assertTrue(exceptionEncountered.get());
        assertTrue(onThreadStartInvoked.get());
        assertTrue(onThreadCompleteInvoked.get());
        assertFalse(executionAttempted.get());

    }

    @Test
    public void testEarlyUnsubscribeDuringExecutionViaToObservable() {
        class AsyncCommand extends HystrixCommand<Boolean> {

            public AsyncCommand() {
                super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(500);
                    return true;
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        HystrixCommand<Boolean> cmd = new AsyncCommand();

        final CountDownLatch latch = new CountDownLatch(1);

        Observable<Boolean> o = cmd.toObservable();
        Subscription s = o.
                doOnUnsubscribe(() -> {
                    System.out.println("OnUnsubscribe");
                    latch.countDown();
                }).
                subscribe(new Subscriber<Boolean>() {
                    @Override
                    public void onCompleted() {
                        System.out.println("OnCompleted");
                    }

                    @Override
                    public void onError(Throwable e) {
                        System.out.println("OnError : " + e);
                    }

                    @Override
                    public void onNext(Boolean b) {
                        System.out.println("OnNext : " + b);
                    }
                });

        try {
            Thread.sleep(10);
            s.unsubscribe();
            assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use", 0, cmd.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(cmd.isExecutionComplete());
            assertNull(cmd.getFailedExecutionException());
            assertNull(cmd.getExecutionException());
            System.out.println("Execution time : " + cmd.getExecutionTimeInMilliseconds());
            assertTrue(cmd.getExecutionTimeInMilliseconds() > -1);
            assertFalse(cmd.isSuccessfulExecution());
            assertCommandExecutionEvents(cmd, HystrixEventType.CANCELLED);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testEarlyUnsubscribeDuringExecutionViaObserve() {
        class AsyncCommand extends HystrixCommand<Boolean> {

            public AsyncCommand() {
                super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(500);
                    return true;
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        HystrixCommand<Boolean> cmd = new AsyncCommand();

        final CountDownLatch latch = new CountDownLatch(1);

        Observable<Boolean> o = cmd.observe();
        Subscription s = o.
                doOnUnsubscribe(() -> {
                    System.out.println("OnUnsubscribe");
                    latch.countDown();
                }).
                subscribe(new Subscriber<Boolean>() {
                    @Override
                    public void onCompleted() {
                        System.out.println("OnCompleted");
                    }

                    @Override
                    public void onError(Throwable e) {
                        System.out.println("OnError : " + e);
                    }

                    @Override
                    public void onNext(Boolean b) {
                        System.out.println("OnNext : " + b);
                    }
                });

        try {
            Thread.sleep(10);
            s.unsubscribe();
            assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use", 0, cmd.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(cmd.isExecutionComplete());
            assertNull(cmd.getFailedExecutionException());
            assertNull(cmd.getExecutionException());
            assertTrue(cmd.getExecutionTimeInMilliseconds() > -1);
            assertFalse(cmd.isSuccessfulExecution());
            assertCommandExecutionEvents(cmd, HystrixEventType.CANCELLED);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testRequestThenCacheHitAndCacheHitUnsubscribed() {
        AsyncCacheableCommand original = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache = new AsyncCacheableCommand("foo");

        final AtomicReference<Boolean> originalValue = new AtomicReference<>(null);
        final AtomicReference<Boolean> fromCacheValue = new AtomicReference<>(null);

        final CountDownLatch originalLatch = new CountDownLatch(1);
        final CountDownLatch fromCacheLatch = new CountDownLatch(1);

        Observable<Boolean> originalObservable = original.toObservable();
        Observable<Boolean> fromCacheObservable = fromCache.toObservable();

        originalObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original Unsubscribe");
            originalLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnCompleted");
                originalLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnError : " + e);
                originalLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnNext : " + b);
                originalValue.set(b);
            }
        });

        Subscription fromCacheSubscription = fromCacheObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " FromCache Unsubscribe");
            fromCacheLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " FromCache OnCompleted");
                fromCacheLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " FromCache OnError : " + e);
                fromCacheLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " FromCache OnNext : " + b);
                fromCacheValue.set(b);
            }
        });

        try {
            fromCacheSubscription.unsubscribe();
            assertTrue(fromCacheLatch.await(600, TimeUnit.MILLISECONDS));
            assertTrue(originalLatch.await(600, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use (original)", 0, original.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertTrue(original.isExecutionComplete());
            assertTrue(original.isExecutedInThread());
            assertNull(original.getFailedExecutionException());
            assertNull(original.getExecutionException());
            assertTrue(original.getExecutionTimeInMilliseconds() > -1);
            assertTrue(original.isSuccessfulExecution());
            assertCommandExecutionEvents(original, HystrixEventType.SUCCESS);
            assertTrue(originalValue.get());


            assertEquals("Number of execution semaphores in use (fromCache)", 0, fromCache.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(fromCache.isExecutionComplete());
            assertFalse(fromCache.isExecutedInThread());
            assertNull(fromCache.getFailedExecutionException());
            assertNull(fromCache.getExecutionException());
            assertCommandExecutionEvents(fromCache, HystrixEventType.RESPONSE_FROM_CACHE, HystrixEventType.CANCELLED);
            assertEquals(fromCache.getExecutionTimeInMilliseconds(), -1);
            assertFalse(fromCache.isSuccessfulExecution());

            assertFalse(original.isCancelled());  //underlying work
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testRequestThenCacheHitAndOriginalUnsubscribed() {
        AsyncCacheableCommand original = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache = new AsyncCacheableCommand("foo");

        final AtomicReference<Boolean> originalValue = new AtomicReference<>(null);
        final AtomicReference<Boolean> fromCacheValue = new AtomicReference<>(null);

        final CountDownLatch originalLatch = new CountDownLatch(1);
        final CountDownLatch fromCacheLatch = new CountDownLatch(1);

        Observable<Boolean> originalObservable = original.toObservable();
        Observable<Boolean> fromCacheObservable = fromCache.toObservable();

        Subscription originalSubscription = originalObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original Unsubscribe");
            originalLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnCompleted");
                originalLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnError : " + e);
                originalLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnNext : " + b);
                originalValue.set(b);
            }
        });

        fromCacheObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache Unsubscribe");
            fromCacheLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache OnCompleted");
                fromCacheLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache OnError : " + e);
                fromCacheLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache OnNext : " + b);
                fromCacheValue.set(b);
            }
        });

        try {
            Thread.sleep(10);
            originalSubscription.unsubscribe();
            assertTrue(originalLatch.await(600, TimeUnit.MILLISECONDS));
            assertTrue(fromCacheLatch.await(600, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use (original)", 0, original.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(original.isExecutionComplete());
            assertTrue(original.isExecutedInThread());
            assertNull(original.getFailedExecutionException());
            assertNull(original.getExecutionException());
            assertTrue(original.getExecutionTimeInMilliseconds() > -1);
            assertFalse(original.isSuccessfulExecution());
            assertCommandExecutionEvents(original, HystrixEventType.CANCELLED);
            assertNull(originalValue.get());

            assertEquals("Number of execution semaphores in use (fromCache)", 0, fromCache.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertTrue(fromCache.isExecutionComplete());
            assertFalse(fromCache.isExecutedInThread());
            assertNull(fromCache.getFailedExecutionException());
            assertNull(fromCache.getExecutionException());
            assertCommandExecutionEvents(fromCache, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
            assertEquals(fromCache.getExecutionTimeInMilliseconds(), -1);
            assertTrue(fromCache.isSuccessfulExecution());

            assertFalse(original.isCancelled());  //underlying work
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testRequestThenTwoCacheHitsOriginalAndOneCacheHitUnsubscribed() {
        AsyncCacheableCommand original = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache1 = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache2 = new AsyncCacheableCommand("foo");

        final AtomicReference<Boolean> originalValue = new AtomicReference<>(null);
        final AtomicReference<Boolean> fromCache1Value = new AtomicReference<>(null);
        final AtomicReference<Boolean> fromCache2Value = new AtomicReference<>(null);

        final CountDownLatch originalLatch = new CountDownLatch(1);
        final CountDownLatch fromCache1Latch = new CountDownLatch(1);
        final CountDownLatch fromCache2Latch = new CountDownLatch(1);

        Observable<Boolean> originalObservable = original.toObservable();
        Observable<Boolean> fromCache1Observable = fromCache1.toObservable();
        Observable<Boolean> fromCache2Observable = fromCache2.toObservable();

        Subscription originalSubscription = originalObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original Unsubscribe");
            originalLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnCompleted");
                originalLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnError : " + e);
                originalLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnNext : " + b);
                originalValue.set(b);
            }
        });

        fromCache1Observable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 Unsubscribe");
            fromCache1Latch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnCompleted");
                fromCache1Latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnError : " + e);
                fromCache1Latch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnNext : " + b);
                fromCache1Value.set(b);
            }
        });

        Subscription fromCache2Subscription = fromCache2Observable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 Unsubscribe");
            fromCache2Latch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnCompleted");
                fromCache2Latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnError : " + e);
                fromCache2Latch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnNext : " + b);
                fromCache2Value.set(b);
            }
        });

        try {
            Thread.sleep(10);
            originalSubscription.unsubscribe();
            //fromCache1Subscription.unsubscribe();
            fromCache2Subscription.unsubscribe();
            assertTrue(originalLatch.await(600, TimeUnit.MILLISECONDS));
            assertTrue(fromCache1Latch.await(600, TimeUnit.MILLISECONDS));
            assertTrue(fromCache2Latch.await(600, TimeUnit.MILLISECONDS));

            assertEquals("Number of execution semaphores in use (original)", 0, original.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(original.isExecutionComplete());
            assertTrue(original.isExecutedInThread());
            assertNull(original.getFailedExecutionException());
            assertNull(original.getExecutionException());
            assertTrue(original.getExecutionTimeInMilliseconds() > -1);
            assertFalse(original.isSuccessfulExecution());
            assertCommandExecutionEvents(original, HystrixEventType.CANCELLED);
            assertNull(originalValue.get());
            assertFalse(original.isCancelled());   //underlying work


            assertEquals("Number of execution semaphores in use (fromCache1)", 0, fromCache1.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertTrue(fromCache1.isExecutionComplete());
            assertFalse(fromCache1.isExecutedInThread());
            assertNull(fromCache1.getFailedExecutionException());
            assertNull(fromCache1.getExecutionException());
            assertCommandExecutionEvents(fromCache1, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
            assertEquals(fromCache1.getExecutionTimeInMilliseconds(), -1);
            assertTrue(fromCache1.isSuccessfulExecution());
            assertTrue(fromCache1Value.get());


            assertEquals("Number of execution semaphores in use (fromCache2)", 0, fromCache2.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(fromCache2.isExecutionComplete());
            assertFalse(fromCache2.isExecutedInThread());
            assertNull(fromCache2.getFailedExecutionException());
            assertNull(fromCache2.getExecutionException());
            assertCommandExecutionEvents(fromCache2, HystrixEventType.RESPONSE_FROM_CACHE, HystrixEventType.CANCELLED);
            assertEquals(fromCache2.getExecutionTimeInMilliseconds(), -1);
            assertFalse(fromCache2.isSuccessfulExecution());
            assertNull(fromCache2Value.get());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testRequestThenTwoCacheHitsAllUnsubscribed() {
        AsyncCacheableCommand original = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache1 = new AsyncCacheableCommand("foo");
        AsyncCacheableCommand fromCache2 = new AsyncCacheableCommand("foo");

        final CountDownLatch originalLatch = new CountDownLatch(1);
        final CountDownLatch fromCache1Latch = new CountDownLatch(1);
        final CountDownLatch fromCache2Latch = new CountDownLatch(1);

        Observable<Boolean> originalObservable = original.toObservable();
        Observable<Boolean> fromCache1Observable = fromCache1.toObservable();
        Observable<Boolean> fromCache2Observable = fromCache2.toObservable();

        Subscription originalSubscription = originalObservable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original Unsubscribe");
            originalLatch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnCompleted");
                originalLatch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnError : " + e);
                originalLatch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.Original OnNext : " + b);
            }
        });

        Subscription fromCache1Subscription = fromCache1Observable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 Unsubscribe");
            fromCache1Latch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnCompleted");
                fromCache1Latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnError : " + e);
                fromCache1Latch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache1 OnNext : " + b);
            }
        });

        Subscription fromCache2Subscription = fromCache2Observable.doOnUnsubscribe(() -> {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 Unsubscribe");
            fromCache2Latch.countDown();
        }).subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnCompleted");
                fromCache2Latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnError : " + e);
                fromCache2Latch.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Test.FromCache2 OnNext : " + b);
            }
        });

        try {
            Thread.sleep(10);
            originalSubscription.unsubscribe();
            fromCache1Subscription.unsubscribe();
            fromCache2Subscription.unsubscribe();
            assertTrue(originalLatch.await(200, TimeUnit.MILLISECONDS));
            assertTrue(fromCache1Latch.await(200, TimeUnit.MILLISECONDS));
            assertTrue(fromCache2Latch.await(200, TimeUnit.MILLISECONDS));

            assertEquals("Number of execution semaphores in use (original)", 0, original.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(original.isExecutionComplete());
            assertTrue(original.isExecutedInThread());
            System.out.println("FEE : " + original.getFailedExecutionException());
            if (original.getFailedExecutionException() != null) {
                original.getFailedExecutionException().printStackTrace();
            }
            assertNull(original.getFailedExecutionException());
            assertNull(original.getExecutionException());
            assertTrue(original.getExecutionTimeInMilliseconds() > -1);
            assertFalse(original.isSuccessfulExecution());
            assertCommandExecutionEvents(original, HystrixEventType.CANCELLED);
            //assertTrue(original.isCancelled());   //underlying work  This doesn't work yet


            assertEquals("Number of execution semaphores in use (fromCache1)", 0, fromCache1.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(fromCache1.isExecutionComplete());
            assertFalse(fromCache1.isExecutedInThread());
            assertNull(fromCache1.getFailedExecutionException());
            assertNull(fromCache1.getExecutionException());
            assertCommandExecutionEvents(fromCache1, HystrixEventType.RESPONSE_FROM_CACHE, HystrixEventType.CANCELLED);
            assertEquals(fromCache1.getExecutionTimeInMilliseconds(), -1);
            assertFalse(fromCache1.isSuccessfulExecution());


            assertEquals("Number of execution semaphores in use (fromCache2)", 0, fromCache2.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(fromCache2.isExecutionComplete());
            assertFalse(fromCache2.isExecutedInThread());
            assertNull(fromCache2.getFailedExecutionException());
            assertNull(fromCache2.getExecutionException());
            assertCommandExecutionEvents(fromCache2, HystrixEventType.RESPONSE_FROM_CACHE, HystrixEventType.CANCELLED);
            assertEquals(fromCache2.getExecutionTimeInMilliseconds(), -1);
            assertFalse(fromCache2.isSuccessfulExecution());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Some RxJava operators like take(n), zip receive data in an onNext from upstream and immediately unsubscribe.
     * When upstream is a HystrixCommand, Hystrix may get that unsubscribe before it gets to its onCompleted.
     * This should still be marked as a HystrixEventType.SUCCESS.
     */
    @Test
    public void testUnsubscribingDownstreamOperatorStillResultsInSuccessEventType() throws InterruptedException {
        HystrixCommand<Integer> cmd = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 100);

        Observable<Integer> o = cmd.toObservable()
                .doOnNext(i -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " CMD OnNext : " + i))
                .doOnError(throwable -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " CMD OnError : " + throwable))
                .doOnCompleted(() -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " CMD OnCompleted"))
                .doOnSubscribe(() -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " CMD OnSubscribe"))
                .doOnUnsubscribe(() -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " CMD OnUnsubscribe"))
                .take(1)
                .observeOn(Schedulers.io())
                .map(i -> {
                    System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : Doing some more computation in the onNext!!");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                    return i;
                });

        final CountDownLatch latch = new CountDownLatch(1);

        o.doOnSubscribe(() -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : OnSubscribe")).doOnUnsubscribe(() -> System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : OnUnsubscribe")).subscribe(new Subscriber<Integer>() {
            @Override
            public void onCompleted() {
                System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : OnCompleted");
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : OnError : " + e);
                latch.countDown();
            }

            @Override
            public void onNext(Integer i) {
                System.out.println(Thread.currentThread().getName() + " : " + System.currentTimeMillis() + " : OnNext : " + i);
            }
        });

        latch.await(1000, TimeUnit.MILLISECONDS);

        assertTrue(cmd.isExecutedInThread());
        assertCommandExecutionEvents(cmd, HystrixEventType.SUCCESS);
    }

    @Test
    public void testUnsubscribeBeforeSubscribe() throws Exception {
        //this may happen in Observable chain, so Hystrix should make sure that command never executes/allocates in this situation
        Observable<String> error = Observable.error(new RuntimeException("foo"));
        HystrixCommand<Integer> cmd = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 100);
        Observable<Integer> cmdResult = cmd.toObservable()
                .doOnNext(integer -> System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnNext : " + integer))
                .doOnError(ex -> System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnError : " + ex))
                .doOnCompleted(() -> System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnCompleted"))
                .doOnSubscribe(() -> System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnSubscribe"))
                .doOnUnsubscribe(() -> System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnUnsubscribe"));

        //the zip operator will subscribe to each observable.  there is a race between the error of the first
        //zipped observable terminating the zip and the subscription to the command's observable
        Observable<String> zipped = Observable.zip(error, cmdResult, (s, integer) -> s + integer);

        final CountDownLatch latch = new CountDownLatch(1);

        zipped.subscribe(new Subscriber<String>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " OnCompleted");
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " OnError : " + e);
                latch.countDown();
            }

            @Override
            public void onNext(String s) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " OnNext : " + s);
            }
        });

        latch.await(1000, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testRxRetry() throws Exception {
        // see https://github.com/Netflix/Hystrix/issues/1100
        // Since each command instance is single-use, the expectation is that applying the .retry() operator
        // results in only a single execution and propagation out of that error
        HystrixCommand<Integer> cmd = getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.FAILURE, 300);

        final CountDownLatch latch = new CountDownLatch(1);

        System.out.println(System.currentTimeMillis() + " : Starting");
        Observable<Integer> o = cmd.toObservable().retry(2);
        System.out.println(System.currentTimeMillis() + " Created retried command : " + o);

        o.subscribe(new Subscriber<Integer>() {
            @Override
            public void onCompleted() {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnCompleted");
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnError : " + e);
                latch.countDown();
            }

            @Override
            public void onNext(Integer integer) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnNext : " + integer);
            }
        });

        latch.await(1000, TimeUnit.MILLISECONDS);
    }

    /*
     *********************** THREAD-ISOLATED Execution Hook Tests **************************************
     */

    /**
     * Thread/semaphore: THREAD
     * Thread Pool full? : NO
     * Thread Pool Queue full?: NO
     * Execution Result: SUCCESS
     */
    @Test
    public void testExecutionHookThreadSuccess() {
        assertHooksOnSuccess(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(1, 0, 1));
                    assertTrue(hook.executionEventsMatch(1, 0, 1));
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionEmit - onEmit - onExecutionSuccess - onThreadComplete - onSuccess - ", hook.executionSequence.toString());
                });
    }

    @Test
    public void testExecutionHookEarlyUnsubscribe() {
        System.out.println("Running command.observe(), awaiting terminal state of Observable, then running assertions...");
        final CountDownLatch latch = new CountDownLatch(1);

        TestHystrixCommand<Integer> command = getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 1000);
        Observable<Integer> o = command.observe();

        Subscription s = o.
                doOnUnsubscribe(() -> {
                    System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnUnsubscribe");
                    latch.countDown();
                }).
                subscribe(new Subscriber<Integer>() {
                    @Override
                    public void onCompleted() {
                        System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnCompleted");
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                        System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnError : " + e);
                        latch.countDown();
                    }

                    @Override
                    public void onNext(Integer i) {
                        System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " : OnNext : " + i);
                    }
        });

        try {
            Thread.sleep(15);
            s.unsubscribe();
            latch.await(3, TimeUnit.SECONDS);
            TestableExecutionHook hook = command.getBuilder().executionHook;
            assertTrue(hook.commandEmissionsMatch(0, 0, 0));
            assertTrue(hook.executionEventsMatch(0, 0, 0));
            assertEquals("onStart - onThreadStart - onExecutionStart - onUnsubscribe - onThreadComplete - ", hook.executionSequence.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool full? : NO
     * Thread Pool Queue full?: NO
     * Execution Result: synchronous HystrixBadRequestException
     */
    @Test
    public void testExecutionHookThreadBadRequestException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.BAD_REQUEST),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(HystrixBadRequestException.class, hook.getCommandException().getClass());
                    assertEquals(HystrixBadRequestException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", hook.executionSequence.toString());
                });
    }



    /**
     * Thread/semaphore: THREAD
     * Thread Pool full? : NO
     * Thread Pool Queue full?: NO
     * Execution Result: synchronous HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.FAILURE, 0),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool full? : YES
     * Thread Pool Queue full?: YES
     */
    @Test
    public void testExecutionHookThreadPoolQueueFull() {
        assertHooksOnFailFast(
                () -> {
                    HystrixThreadPool pool = new SingleThreadedPoolWithQueue(1);
                    try {
                        // fill the pool
                        getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 500, pool).observe();
                        // fill the queue
                        getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 500, pool).observe();
                    } catch (Exception e) {
                        // ignore
                    }
                    return getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 500, pool);
                },
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RejectedExecutionException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool full? : YES
     * Thread Pool Queue full?: N/A
     */
    @Test
    public void testExecutionHookThreadPoolFull() {
        assertHooksOnFailFast(
                () -> {
                    HystrixThreadPool pool = new SingleThreadedPoolWithNoQueue();
                    try {
                        // fill the pool
                        getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 500, pool).observe();
                    } catch (Exception e) {
                        // ignore
                    }

                    return getLatentCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 500, pool);
                },
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RejectedExecutionException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     */
    @Test
    public void testExecutionHookThread() {
        assertHooksOnFailFast(
                () -> getCircuitOpenCommand(ExecutionIsolationStrategy.THREAD),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Request-cache? : YES
     */
    @Test
    public void testExecutionHookResponseFromCache() {
        final HystrixCommandKey key = HystrixCommandKey.Factory.asKey("Hook-Cache");
        getCommand(key, ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 0, null, AbstractTestHystrixCommand.CacheEnabled.YES, 42, 10).observe();

        assertHooksOnSuccess(
                () -> getCommand(key, ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.SUCCESS, 0, null, AbstractTestHystrixCommand.CacheEnabled.YES, 42, 10),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 0, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals("onCacheHit - ", hook.executionSequence.toString());
                });
    }

    /**
     *********************** END THREAD-ISOLATED Execution Hook Tests **************************************
     */


    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* private HystrixCommand class implementations for unit testing */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    static AtomicInteger uniqueNameCounter = new AtomicInteger(1);

    @Override
    TestHystrixCommand<Integer> getCommand(ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
        HystrixCommandKey commandKey = HystrixCommandKey.Factory.asKey("Flexible-" + uniqueNameCounter.getAndIncrement());
        return FlexibleTestHystrixCommand.from(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    @Override
    TestHystrixCommand<Integer> getCommand(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
        return FlexibleTestHystrixCommand.from(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    private static class FlexibleTestHystrixCommand {

        public static Integer EXECUTE_VALUE = 1;

        public static AbstractFlexibleTestHystrixCommand from(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
            return new FlexibleTestHystrixCommand2(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }
    }

    private static class AbstractFlexibleTestHystrixCommand extends TestHystrixCommand<Integer> {
        protected final AbstractTestHystrixCommand.ExecutionResult executionResult;
        protected final int executionLatency;

        protected final CacheEnabled cacheEnabled;
        protected final Object value;


        AbstractFlexibleTestHystrixCommand(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
            super(testPropsBuilder()
                    .setCommandKey(commandKey)
                    .setThreadPool(threadPool)
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(isolationStrategy))
                    .setExecutionSemaphore(executionSemaphore));
            this.executionResult = executionResult;
            this.executionLatency = executionLatency;

            this.cacheEnabled = cacheEnabled;
            this.value = value;
        }

        @Override
        protected Integer run() {
            System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " starting the run() method");
            addLatency(executionLatency);
            if (executionResult == AbstractTestHystrixCommand.ExecutionResult.SUCCESS) {
                return FlexibleTestHystrixCommand.EXECUTE_VALUE;
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.FAILURE) {
                throw new RuntimeException("Execution Failure for TestHystrixCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.NOT_WRAPPED_FAILURE) {
                throw new NotWrappedByHystrixTestRuntimeException();
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.HYSTRIX_FAILURE) {
                throw new HystrixRuntimeException(HystrixRuntimeException.FailureType.COMMAND_EXCEPTION, AbstractFlexibleTestHystrixCommand.class, "Execution Hystrix Failure for TestHystrixCommand", new RuntimeException("Execution Failure for TestHystrixCommand"));
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.RECOVERABLE_ERROR) {
                throw new java.lang.Error("Execution ERROR for TestHystrixCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.UNRECOVERABLE_ERROR) {
                throw new StackOverflowError("Unrecoverable Error for TestHystrixCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.BAD_REQUEST) {
                throw new HystrixBadRequestException("Execution BadRequestException for TestHystrixCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.BAD_REQUEST_NOT_WRAPPED) {
                throw new HystrixBadRequestException("Execution BadRequestException for TestHystrixCommand", new NotWrappedByHystrixTestRuntimeException());
            } else {
                throw new RuntimeException("You passed in a executionResult enum that can't be represented in HystrixCommand: " + executionResult);
            }
        }

        @Override
        public String getCacheKey() {
            if (cacheEnabled == CacheEnabled.YES)
                return value.toString();
            else
                return null;
        }

        protected void addLatency(int latency) {
            if (latency > 0) {
                try {
                    System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " About to sleep for : " + latency);
                    Thread.sleep(latency);
                    System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " Woke up from sleep!");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    // ignore and sleep some more to simulate a dependency that doesn't obey interrupts
                    try {
                        Thread.sleep(latency);
                    } catch (Exception e2) {
                        // ignore
                    }
                    System.out.println("after interruption with extra sleep");
                }
            }
        }

    }

    private static class FlexibleTestHystrixCommand2 extends AbstractFlexibleTestHystrixCommand {
        FlexibleTestHystrixCommand2(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
            super(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
        }
    }

    /**
     * Successful execution.
     */
    private static class SuccessfulTestCommand extends TestHystrixCommand<Boolean> {

        public SuccessfulTestCommand() {
            this(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter());
        }

        public SuccessfulTestCommand(HystrixCommandProperties.Setter properties) {
            super(testPropsBuilder().setCommandPropertiesDefaults(properties));
        }

        @Override
        protected Boolean run() {
            return true;
        }

    }

    /**
     * Successful execution.
     */
    private static class DynamicOwnerTestCommand extends TestHystrixCommand<Boolean> {

        public DynamicOwnerTestCommand(HystrixCommandGroupKey owner) {
            super(testPropsBuilder().setOwner(owner));
        }

        @Override
        protected Boolean run() {
            System.out.println("successfully executed");
            return true;
        }

    }

    /**
     * Failed execution.
     */
    private static class KnownFailureTestCommand extends TestHystrixCommand<Boolean> {

        public KnownFailureTestCommand() {
            super(testPropsBuilder());
        }

        @Override
        protected Boolean run() {
            System.out.println("*** simulated failed execution ***");
            throw new RuntimeException("we failed with a simulated issue");
        }
    }

    /**
     * A Command implementation that supports caching.
     */
    private static class SuccessfulCacheableCommand<T> extends TestHystrixCommand<T> {

        private final boolean cacheEnabled;
        private volatile boolean executed = false;
        private final T value;

        public SuccessfulCacheableCommand(boolean cacheEnabled, T value) {
            super(testPropsBuilder());
            this.value = value;
            this.cacheEnabled = cacheEnabled;
        }

        @Override
        protected T run() {
            executed = true;
            System.out.println("successfully executed");
            return value;
        }

        public boolean isCommandRunningInThread() {
            return super.getProperties().executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD);
        }

        @Override
        public String getCacheKey() {
            if (cacheEnabled)
                return value.toString();
            else
                return null;
        }
    }

    /**
     * A Command implementation that supports caching.
     */
    private static class SuccessfulCacheableCommandViaSemaphore extends TestHystrixCommand<String> {

        private final boolean cacheEnabled;
        private volatile boolean executed = false;
        private final String value;

        public SuccessfulCacheableCommandViaSemaphore(boolean cacheEnabled, String value) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
            this.value = value;
            this.cacheEnabled = cacheEnabled;
        }

        @Override
        protected String run() {
            executed = true;
            System.out.println("successfully executed");
            return value;
        }

        public boolean isCommandRunningInThread() {
            return super.getProperties().executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD);
        }

        @Override
        public String getCacheKey() {
            if (cacheEnabled)
                return value;
            else
                return null;
        }
    }

    /**
     * A Command implementation that supports caching and execution takes a while.
     * <p>
     * Used to test scenario where Futures are returned with a backing call still executing.
     */
    private static class SlowCacheableCommand extends TestHystrixCommand<String> {

        private final String value;
        private final int duration;
        private volatile boolean executed = false;

        public SlowCacheableCommand(String value, int duration) {
            super(testPropsBuilder());
            this.value = value;
            this.duration = duration;
        }

        @Override
        protected String run() {
            executed = true;
            try {
                Thread.sleep(duration);
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("successfully executed");
            return value;
        }

        @Override
        public String getCacheKey() {
            return value;
        }
    }

    /**
     * This has a ThreadPool that has a single thread and queueSize of 1.
     */
    private static class TestCommandRejection extends TestHystrixCommand<Boolean> {

        private final int sleepTime;

        private TestCommandRejection(HystrixCommandKey key, HystrixThreadPool threadPool, int sleepTime) {
            super(testPropsBuilder()
                    .setCommandKey(key)
                    .setThreadPool(threadPool)
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()));
            this.sleepTime = sleepTime;
        }

        @Override
        protected Boolean run() {
            System.out.println(">>> TestCommandRejection running");
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return true;
        }
    }

    /**
     * The run() will take time.
     */
    private static class TestSemaphoreCommand extends TestHystrixCommand<Boolean> {

        private final long executionSleep;

        private final static int RESULT_SUCCESS = 1;
        private final static int RESULT_FAILURE = 2;
        private final static int RESULT_BAD_REQUEST_EXCEPTION = 3;

        private final int resultBehavior;

        private TestSemaphoreCommand(int executionSemaphoreCount, long executionSleep, int resultBehavior) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)
                            .withExecutionIsolationSemaphoreMaxConcurrentRequests(executionSemaphoreCount)));
            this.executionSleep = executionSleep;
            this.resultBehavior = resultBehavior;
        }

        private TestSemaphoreCommand(TryableSemaphore semaphore, long executionSleep, int resultBehavior) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                    .setExecutionSemaphore(semaphore));
            this.executionSleep = executionSleep;
            this.resultBehavior = resultBehavior;
        }

        @Override
        protected Boolean run() {
            try {
                Thread.sleep(executionSleep);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (resultBehavior == RESULT_SUCCESS) {
                return true;
            } else if (resultBehavior == RESULT_FAILURE) {
                throw new RuntimeException("TestSemaphoreCommand failure");
            } else if (resultBehavior == RESULT_BAD_REQUEST_EXCEPTION) {
                throw new HystrixBadRequestException("TestSemaphoreCommand BadRequestException");
            } else {
                throw new IllegalStateException("Didn't use a proper enum for result behavior");
            }
        }
    }

    /**
     * Semaphore based command that allows caller to use latches to know when it has started and signal when it
     * would like the command to finish
     */
    private static class LatchedSemaphoreCommand extends TestHystrixCommand<Boolean> {

        private final CountDownLatch startLatch, waitLatch;

        private LatchedSemaphoreCommand(String commandName, TryableSemaphore semaphore,
                                        CountDownLatch startLatch, CountDownLatch waitLatch) {
            super(testPropsBuilder()
                    .setCommandKey(HystrixCommandKey.Factory.asKey(commandName))
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                    .setExecutionSemaphore(semaphore));
            this.startLatch = startLatch;
            this.waitLatch = waitLatch;
        }

        @Override
        protected Boolean run() {
            // signals caller that run has started
            this.startLatch.countDown();

            try {
                // waits for caller to countDown latch
                this.waitLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    private static class RequestCacheThreadRejection extends TestHystrixCommand<Boolean> {

        final CountDownLatch completionLatch;

        public RequestCacheThreadRejection(CountDownLatch completionLatch) {
            super(testPropsBuilder()
                    .setThreadPool(new HystrixThreadPool() {

                        @Override
                        public ThreadPoolExecutor getExecutor() {
                            return null;
                        }

                        @Override
                        public void markThreadExecution() {

                        }

                        @Override
                        public void markThreadCompletion() {

                        }

                        @Override
                        public void markThreadRejection() {

                        }

                        @Override
                        public boolean isQueueSpaceAvailable() {
                            // always return false so we reject everything
                            return false;
                        }

                        @Override
                        public Scheduler getScheduler() {
                            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this);
                        }

                        @Override
                        public Scheduler getScheduler(Func0<Boolean> shouldInterruptThread) {
                            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this, shouldInterruptThread);
                        }

                    }));
            this.completionLatch = completionLatch;
        }

        @Override
        protected Boolean run() {
            try {
                if (completionLatch.await(1000, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException("timed out waiting on completionLatch");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return true;
        }

        @Override
        public String getCacheKey() {
            return "A";
        }
    }

    private static class BadRequestCommand extends TestHystrixCommand<Boolean> {

        public BadRequestCommand(ExecutionIsolationStrategy isolationType) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationType)));
        }

        @Override
        protected Boolean run() {
            throw new HystrixBadRequestException("Message to developer that they passed in bad data or something like that.");
        }

        @Override
        protected String getCacheKey() {
            return "one";
        }

    }

    private static class AsyncCacheableCommand extends HystrixCommand<Boolean> {
        private final String arg;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public AsyncCacheableCommand(String arg) {
            super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            this.arg = arg;
        }

        @Override
        protected Boolean run() {
            try {
                Thread.sleep(500);
                return true;
            } catch (InterruptedException ex) {
                cancelled.set(true);
                throw new RuntimeException(ex);
            }
        }

        @Override
        protected String getCacheKey() {
            return arg;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static class CommandWithCheckedException extends TestHystrixCommand<Boolean> {

        public CommandWithCheckedException() {
            super(testPropsBuilder());
        }

        @Override
        protected Boolean run() throws Exception {
            throw new IOException("simulated checked exception message");
        }

    }

    private static class CommandWithNotWrappedByHystrixException extends TestHystrixCommand<Boolean> {

        public CommandWithNotWrappedByHystrixException() {
            super(testPropsBuilder());
        }

        @Override
        protected Boolean run() throws Exception {
            throw new NotWrappedByHystrixTestException();
        }

    }

    private static class InterruptibleCommand extends TestHystrixCommand<Boolean> {

        public InterruptibleCommand(boolean shouldInterruptOnCancel) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                    		.withExecutionIsolationThreadInterruptOnFutureCancel(shouldInterruptOnCancel)));
        }

        @Override
        protected Boolean run() {
            try {
                Thread.sleep(2000);
            }
            catch (InterruptedException e) {
                System.out.println("Interrupted!");
                e.printStackTrace();
            }

            return false;
        }
    }
}
