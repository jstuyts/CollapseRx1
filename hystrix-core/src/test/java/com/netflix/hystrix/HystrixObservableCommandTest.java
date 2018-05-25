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
import com.netflix.hystrix.AbstractCommand.TryableSemaphoreActual;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import rx.*;
import rx.Observable.OnSubscribe;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class HystrixObservableCommandTest extends CommonHystrixCommandTests<TestHystrixObservableCommand<Integer>> {

    @Rule
    public HystrixRequestContextRule ctx = new HystrixRequestContextRule();

    @After
    public void cleanup() {
        // force properties to be clean as well
        ConfigurationManager.getConfigInstance().clear();

        /*
         * RxJava will create one worker for each processor when we schedule Observables in the
         * Schedulers.computation(). Any leftovers here might lead to a congestion in a following
         * thread. To ensure all existing threads have completed we now schedule some observables
         * that will execute in distinct threads due to the latch..
         */
        int count = Runtime.getRuntime().availableProcessors();
        final CountDownLatch latch = new CountDownLatch(count);
        ArrayList<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            futures.add(Observable.create((OnSubscribe<Boolean>) sub -> {
                latch.countDown();
                try {
                    latch.await();

                    sub.onNext(true);
                    sub.onCompleted();
                } catch (InterruptedException e) {
                    sub.onError(e);
                }
            }).subscribeOn(Schedulers.computation()).toBlocking().toFuture());
        }
        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        //TODO commented out as it has issues when built from command-line even though it works from IDE
        //        HystrixCommandKey key = Hystrix.getCurrentThreadExecutingCommand();
        //        if (key != null) {
        //            throw new IllegalStateException("should be null but got: " + key);
        //        }
    }

    class CompletableCommand extends HystrixObservableCommand<Integer> {

        CompletableCommand() {
            super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("COMPLETABLE")));
        }

        @Override
        protected Observable<Integer> construct() {
            return Completable.complete().toObservable();
        }
    }

    @Test
    public void testCompletable() throws InterruptedException {


        final CountDownLatch latch = new CountDownLatch(1);
        final HystrixObservableCommand<Integer> command = new CompletableCommand();

        command.observe().subscribe(new Subscriber<Integer>() {
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
            public void onNext(Integer integer) {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " OnNext : " + integer);
            }
        });

        latch.await();
        assertNull(command.getFailedExecutionException());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());
        assertCommandExecutionEvents(command, HystrixEventType.SUCCESS);
        assertNull(command.getExecutionException());
    }

    /**
     * Test a successful semaphore-isolated command execution.
     */
    @Test
    public void testSemaphoreObserveSuccess() {
        testObserveSuccess(ExecutionIsolationStrategy.SEMAPHORE);
    }

    /**
     * Test a successful thread-isolated command execution.
     */
    @Test
    public void testThreadObserveSuccess() {
        testObserveSuccess(ExecutionIsolationStrategy.THREAD);
    }

    private void testObserveSuccess(ExecutionIsolationStrategy isolationStrategy) {
        try {
            TestHystrixObservableCommand<Boolean> command = new SuccessfulTestCommand(isolationStrategy);
            assertEquals(true, command.observe().toBlocking().single());

            assertNull(command.getFailedExecutionException());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isSuccessfulExecution());
            assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
            assertNull(command.getExecutionException());
            assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    /**
     * Test that a semaphore command can not be executed multiple times.
     */
    @Test
    public void testSemaphoreIsolatedObserveMultipleTimes() {
        testObserveMultipleTimes(ExecutionIsolationStrategy.SEMAPHORE);
    }

    /**
     * Test that a thread command can not be executed multiple times.
     */
    @Test
    public void testThreadIsolatedObserveMultipleTimes() {
        testObserveMultipleTimes(ExecutionIsolationStrategy.THREAD);
    }

    private void testObserveMultipleTimes(ExecutionIsolationStrategy isolationStrategy) {
        SuccessfulTestCommand command = new SuccessfulTestCommand(isolationStrategy);
        assertFalse(command.isExecutionComplete());
        // first should succeed
        assertEquals(true, command.observe().toBlocking().single());
        assertTrue(command.isExecutionComplete());
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());
        assertNull(command.getExecutionException());

        try {
            // second should fail
            command.observe().toBlocking().single();
            fail("we should not allow this");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            // we want to get here
        }

        assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
        assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
    }

    /**
     * Test a semaphore command execution that throws an HystrixException synchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveKnownSyncFailure() {
        testObserveKnownFailure(ExecutionIsolationStrategy.SEMAPHORE, false);
    }

    /**
     * Test a semaphore command execution that throws an HystrixException asynchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveKnownAsyncFailure() {
        testObserveKnownFailure(ExecutionIsolationStrategy.SEMAPHORE, true);
    }

    /**
     * Test a thread command execution that throws an HystrixException synchronously.
     */
    @Test
    public void testThreadIsolatedObserveKnownSyncFailure() {
        testObserveKnownFailure(ExecutionIsolationStrategy.THREAD, false);
    }

    /**
     * Test a thread command execution that throws an HystrixException asynchronously.
     */
    @Test
    public void testThreadIsolatedObserveKnownAsyncFailure() {
        testObserveKnownFailure(ExecutionIsolationStrategy.THREAD, true);
    }

    private void testObserveKnownFailure(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
        TestHystrixObservableCommand<Boolean> command = new KnownFailureTestCommand(isolationStrategy, asyncException);
        try {
            command.observe().toBlocking().single();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            assertNotNull(e.getImplementingClass());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We should always get an HystrixRuntimeException when an error occurs.");
        }
        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
    }

    /**
     * Test a semaphore command execution that throws an unknown exception (not HystrixException) synchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveUnknownSyncFailure() {
        testObserveUnknownFailure(ExecutionIsolationStrategy.SEMAPHORE, false);
    }

    /**
     * Test a semaphore command execution that throws an unknown exception (not HystrixException) asynchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveUnknownAsyncFailure() {
        testObserveUnknownFailure(ExecutionIsolationStrategy.SEMAPHORE, true);
    }

    /**
     * Test a thread command execution that throws an unknown exception (not HystrixException) synchronously.
     */
    @Test
    public void testThreadIsolatedObserveUnknownSyncFailure() {
        testObserveUnknownFailure(ExecutionIsolationStrategy.THREAD, false);
    }

    /**
     * Test a thread command execution that throws an unknown exception (not HystrixException) asynchronously.
     */
    @Test
    public void testThreadIsolatedObserveUnknownAsyncFailure() {
        testObserveUnknownFailure(ExecutionIsolationStrategy.THREAD, true);
    }

    private void testObserveUnknownFailure(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
        TestHystrixObservableCommand<Boolean> command = new UnknownFailureTestCommand(isolationStrategy, asyncException);
        try {
            command.observe().toBlocking().single();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            assertNotNull(e.getImplementingClass());

        } catch (Exception e) {
            e.printStackTrace();
            fail("We should always get an HystrixRuntimeException when an error occurs.");
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
    }

    /**
     * Test a semaphore command execution that fails synchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveSyncFailure2() {
        testObserveFailure2(ExecutionIsolationStrategy.SEMAPHORE, false);
    }

    /**
     * Test a semaphore command execution that fails asynchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveAsyncFailure2() {
        testObserveFailure2(ExecutionIsolationStrategy.SEMAPHORE, true);
    }

    /**
     * Test a thread command execution that fails synchronously.
     */
    @Test
    public void testThreadIsolatedObserveSyncFailure2() {
        testObserveFailure2(ExecutionIsolationStrategy.THREAD, false);
    }

    /**
     * Test a thread command execution that fails asynchronously.
     */
    @Test
    public void testThreadIsolatedObserveAsyncFailure2() {
        testObserveFailure2(ExecutionIsolationStrategy.THREAD, true);
    }

    private void testObserveFailure2(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
        TestHystrixObservableCommand<Boolean> command = new KnownFailureTestCommand2(isolationStrategy, asyncException);
        try {
            assertEquals(false, command.observe().toBlocking().single());
        } catch (Exception e) {
            e.printStackTrace();
        }

        assertEquals("we failed with a simulated issue", command.getFailedExecutionException().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
    }

    /**
     * Test a command execution that fails synchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveSyncFailure3() {
        testObserveFailure(ExecutionIsolationStrategy.SEMAPHORE, false);
    }

    /**
     * Test a command execution that fails synchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveSyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.SEMAPHORE, false);
    }

    /**
     * Test a command execution that fails asynchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveAyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.SEMAPHORE, true);
    }

    /**
     * Test a command execution that fails asynchronously.
     */
    @Test
    public void testSemaphoreIsolatedObserveAsyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.SEMAPHORE, true);
    }

    /**
     * Test a command execution that fails synchronously.
     */
    @Test
    public void testThreadIsolatedObserveSyncFailure3() {
        testObserveFailure(ExecutionIsolationStrategy.THREAD, false);
    }

    /**
     * Test a command execution that fails synchronously.
     */
    @Test
    public void testThreadIsolatedObserveSyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.THREAD, true);
    }

    /**
     * Test a command execution that fails asynchronously.
     */
    @Test
    public void testThreadIsolatedObserveAyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.THREAD, false);
    }

    /**
     * Test a command execution that fails asynchronously.
     */
    @Test
    public void testThreadIsolatedObserveAsyncFailure() {
        testObserveFailure(ExecutionIsolationStrategy.THREAD, true);
    }


    private void testObserveFailure(ExecutionIsolationStrategy isolationStrategy, boolean asyncConstructException) {
        TestHystrixObservableCommand<Boolean> command = new KnownFailureTestCommand3(isolationStrategy, asyncConstructException);
        try {
            command.observe().toBlocking().single();
            fail("we shouldn't get here");
        } catch (HystrixRuntimeException e) {
            System.out.println("------------------------------------------------");
            e.printStackTrace();
            System.out.println("------------------------------------------------");
        }

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertEquals(isolationStrategy.equals(ExecutionIsolationStrategy.THREAD), command.isExecutedInThread());
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testObserveOnImmediateSchedulerByDefaultForSemaphoreIsolation() throws Exception {

        final AtomicReference<Thread> commandThread = new AtomicReference<>();
        final AtomicReference<Thread> subscribeThread = new AtomicReference<>();

        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))) {

            @Override
            protected Observable<Boolean> construct() {
                commandThread.set(Thread.currentThread());
                return Observable.just(true);
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
        System.out.println("testObserveOnImmediateSchedulerByDefaultForSemaphoreIsolation: " + subscribeThread.get() + " => " + mainThreadName);
        assertEquals(subscribeThread.get().getName(), mainThreadName);

        // semaphore isolated
        assertFalse(command.isExecutedInThread());
        assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertNull(command.getExecutionException());
    }

    @Test
    public void testExecutionSuccess() {
        TestHystrixObservableCommand<Boolean> command = new TestCommand();
        try {
            assertEquals(true, command.observe().toBlocking().single());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }

        assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertNull(command.getExecutionException());
    }

    @Test
    public void testExecutionSemaphoreWithObserve() {
        TestSemaphoreCommand command1 = new TestSemaphoreCommand(1, 200, TestSemaphoreCommand.RESULT_SUCCESS);

        // single thread should work
        try {
            boolean result = command1.observe().toBlocking().toFuture().get();
            assertTrue(result);
        } catch (Exception e) {
            // we shouldn't fail on this one
            throw new RuntimeException(e);
        }

        final AtomicBoolean exceptionReceived = new AtomicBoolean();

        final TryableSemaphoreActual semaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        final TestSemaphoreCommand command2 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r2 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                command2.observe().toBlocking().toFuture().get();
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        final TestSemaphoreCommand command3 = new TestSemaphoreCommand(semaphore, 200, TestSemaphoreCommand.RESULT_SUCCESS);
        Runnable r3 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                command3.observe().toBlocking().toFuture().get();
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        // 2 threads, the second should be rejected by the semaphore
        Thread t2 = new Thread(r2);
        Thread t3 = new Thread(r3);

        t2.start();
        try {
            Thread.sleep(100);
        } catch (Throwable ex) {
            fail(ex.getMessage());
        }

        t3.start();
        try {
            t2.join();
            t3.join();
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed waiting on threads");
        }

        if (!exceptionReceived.get()) {
            fail("We expected an exception on the 2nd get");
        }

        System.out.println("CMD1 : " + command1.getExecutionEvents());
        System.out.println("CMD2 : " + command2.getExecutionEvents());
        System.out.println("CMD3 : " + command3.getExecutionEvents());
        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.SEMAPHORE_REJECTED);
    }

    @Test
    public void testRejectedExecutionSemaphore() {
        final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<>(2);

        final AtomicBoolean exceptionReceived = new AtomicBoolean();

        final TestSemaphoreCommand2 command1 = new TestSemaphoreCommand2(1, 200);
        Runnable r1 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                results.add(command1.observe().toBlocking().single());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        final TestSemaphoreCommand2 command2 = new TestSemaphoreCommand2(1, 200);
        Runnable r2 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            try {
                results.add(command2.observe().toBlocking().single());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        // 2 threads, the second should be rejected by the semaphore
        Thread t1 = new Thread(r1);
        Thread t2 = new Thread(r2);

        t1.start();
        try {
            //give t1 a headstart
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            fail(ex.getMessage());
        }
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed waiting on threads");
        }

        // both threads should have returned values
        assertEquals(2, results.size());
        // should contain both a true and false result
        assertTrue(results.contains(Boolean.TRUE));
        assertTrue(results.contains(Boolean.FALSE));

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SEMAPHORE_REJECTED);
    }

    @Test
    public void testSemaphorePermitsInUse() {
        // this semaphore will be shared across multiple command instances
        final TryableSemaphoreActual sharedSemaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(3));

        // creates thread using isolated semaphore
        final TryableSemaphoreActual isolatedSemaphore =
                new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        //used to wait until all commands are started
        final CountDownLatch startLatch = new CountDownLatch((sharedSemaphore.numberOfPermits.get()) * 2 + 1);

        // used to signal that all command can finish
        final CountDownLatch sharedLatch = new CountDownLatch(1);
        final CountDownLatch isolatedLatch = new CountDownLatch(1);

        final List<HystrixObservableCommand<Boolean>> commands = new ArrayList<>();
        final List<Observable<Boolean>> results = new ArrayList<>();

        HystrixObservableCommand<Boolean> isolated = new LatchedSemaphoreCommand("ObservableCommand-Isolated", isolatedSemaphore, startLatch, isolatedLatch);
        commands.add(isolated);

        for (int s = 0; s < sharedSemaphore.numberOfPermits.get() * 2; s++) {
            HystrixObservableCommand<Boolean> shared = new LatchedSemaphoreCommand("ObservableCommand-Shared", sharedSemaphore, startLatch, sharedLatch);
            commands.add(shared);
            Observable<Boolean> result = shared.toObservable();
            results.add(result);
        }

        Observable<Boolean> isolatedResult = isolated.toObservable();
        results.add(isolatedResult);

        // verifies no permits in use before starting commands
        assertEquals("before commands start, shared semaphore should be unused", 0, sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("before commands start, isolated semaphore should be unused", 0, isolatedSemaphore.getNumberOfPermitsUsed());

        final CountDownLatch allTerminal = new CountDownLatch(1);

        Observable.merge(results)
                .subscribeOn(Schedulers.newThread())
                .subscribe(new Subscriber<Boolean>() {
                    @Override
                    public void onCompleted() {
                        System.out.println(Thread.currentThread().getName() + " OnCompleted");
                        allTerminal.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                        System.out.println(Thread.currentThread().getName() + " OnError : " + e);
                        allTerminal.countDown();
                    }

                    @Override
                    public void onNext(Boolean b) {
                        System.out.println(Thread.currentThread().getName() + " OnNext : " + b);
                    }
                });

        try {
            assertTrue(startLatch.await(20, TimeUnit.SECONDS));
        } catch (Throwable ex) {
            fail(ex.getMessage());
        }

        // verifies that all semaphores are in use
        assertEquals("immediately after command start, all shared semaphores should be in-use",
                sharedSemaphore.numberOfPermits.get().longValue(), sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("immediately after command start, isolated semaphore should be in-use",
                isolatedSemaphore.numberOfPermits.get().longValue(), isolatedSemaphore.getNumberOfPermitsUsed());

        // signals commands to finish
        sharedLatch.countDown();
        isolatedLatch.countDown();

        try {
            assertTrue(allTerminal.await(5000, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed waiting on commands");
        }

        // verifies no permits in use after finishing threads
        assertEquals("after all threads have finished, no shared semaphores should be in-use", 0, sharedSemaphore.getNumberOfPermitsUsed());
        assertEquals("after all threads have finished, isolated semaphore not in-use", 0, isolatedSemaphore.getNumberOfPermitsUsed());

        // verifies that some executions failed
        int numSemaphoreRejected = 0;
        for (HystrixObservableCommand<Boolean> cmd: commands) {
            if (cmd.isResponseSemaphoreRejected()) {
                numSemaphoreRejected++;
            }
        }
        assertEquals("expected some of shared semaphore commands to get rejected", sharedSemaphore.numberOfPermits.get().longValue(), numSemaphoreRejected);
    }

    /**
     * Test that HystrixOwner can be passed in dynamically.
     */
    @Test
    public void testDynamicOwner() {
        try {
            TestHystrixObservableCommand<Boolean> command = new DynamicOwnerTestCommand(InspectableBuilder.CommandGroupForUnitTest.OWNER_ONE);
            assertEquals(true, command.observe().toBlocking().single());
            assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
            // semaphore isolated
            assertFalse(command.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    /**
     * Test a successful command execution.
     */
    @Test
    public void testDynamicOwnerFails() {
        try {
            TestHystrixObservableCommand<Boolean> command = new DynamicOwnerTestCommand(null);
            assertEquals(true, command.observe().toBlocking().single());
            fail("we should have thrown an exception as we need an owner");

            // semaphore isolated
            assertFalse(command.isExecutedInThread());
        } catch (Exception e) {
            // success if we get here
        }
    }

    /**
     * Test that HystrixCommandKey can be passed in dynamically.
     */
    @Test
    public void testDynamicKey() {
        try {
            DynamicOwnerAndKeyTestCommand command1 = new DynamicOwnerAndKeyTestCommand(InspectableBuilder.CommandGroupForUnitTest.OWNER_ONE, InspectableBuilder.CommandKeyForUnitTest.KEY_ONE);
            assertEquals(true, command1.observe().toBlocking().single());
            DynamicOwnerAndKeyTestCommand command2 = new DynamicOwnerAndKeyTestCommand(InspectableBuilder.CommandGroupForUnitTest.OWNER_ONE, InspectableBuilder.CommandKeyForUnitTest.KEY_TWO);
            assertEquals(true, command2.observe().toBlocking().single());

            // semaphore isolated
            assertFalse(command1.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    /**
     * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
     */
    @Test
    public void testRequestCache1UsingThreadIsolation() {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.observe().toBlocking().toFuture();
        Future<String> f2 = command2.observe().toBlocking().toFuture();

        try {
            assertEquals("A", f1.get());
            assertEquals("A", f2.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(command1.executed);
        // the second one should not have executed as it should have received the cached value instead
        assertFalse(command2.executed);

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command1.isResponseFromCache());
        assertNull(command1.getExecutionException());

        // the execution log for command2 should show it came from cache
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertEquals(command2.getExecutionTimeInMilliseconds(), -1);
        assertTrue(command2.isResponseFromCache());
        assertNull(command2.getExecutionException());
    }

    /**
     * Test Request scoped caching doesn't prevent different ones from executing
     */
    @Test
    public void testRequestCache2UsingThreadIsolation() {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "B");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.observe().toBlocking().toFuture();
        Future<String> f2 = command2.observe().toBlocking().toFuture();

        try {
            assertEquals("A", f1.get());
            assertEquals("B", f2.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertTrue(command2.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command2.isResponseFromCache());
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testRequestCache3UsingThreadIsolation() {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(true, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "B");
        SuccessfulCacheableCommand<String> command3 = new SuccessfulCacheableCommand<>(true, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.observe().toBlocking().toFuture();
        Future<String> f2 = command2.observe().toBlocking().toFuture();
        Future<String> f3 = command3.observe().toBlocking().toFuture();

        try {
            assertEquals("A", f1.get());
            assertEquals("B", f2.get());
            assertEquals("A", f3.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // but the 3rd should come from cache
        assertFalse(command3.executed);

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.EMIT, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertEquals(command3.getExecutionTimeInMilliseconds(), -1);
        assertTrue(command3.isResponseFromCache());
    }

    /**
     * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
     */
    @Test
    public void testRequestCacheWithSlowExecution() {
        SlowCacheableCommand command1 = new SlowCacheableCommand("A", 200);
        SlowCacheableCommand command2 = new SlowCacheableCommand("A", 100);
        SlowCacheableCommand command3 = new SlowCacheableCommand("A", 100);
        SlowCacheableCommand command4 = new SlowCacheableCommand("A", 100);

        Future<String> f1 = command1.observe().toBlocking().toFuture();
        Future<String> f2 = command2.observe().toBlocking().toFuture();
        Future<String> f3 = command3.observe().toBlocking().toFuture();
        Future<String> f4 = command4.observe().toBlocking().toFuture();

        try {
            assertEquals("A", f2.get());
            assertEquals("A", f3.get());
            assertEquals("A", f4.get());

            assertEquals("A", f1.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(command1.executed);
        // the second one should not have executed as it should have received the cached value instead
        assertFalse(command2.executed);
        assertFalse(command3.executed);
        assertFalse(command4.executed);

        // the execution log for command1 should show an EMIT and a SUCCESS
        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command1.isResponseFromCache());

        // the execution log for command2 should show it came from cache
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertEquals(command2.getExecutionTimeInMilliseconds(), -1);
        assertTrue(command2.isResponseFromCache());

        assertCommandExecutionEvents(command3, HystrixEventType.EMIT, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertTrue(command3.isResponseFromCache());
        assertEquals(command3.getExecutionTimeInMilliseconds(), -1);

        assertCommandExecutionEvents(command4, HystrixEventType.EMIT, HystrixEventType.SUCCESS, HystrixEventType.RESPONSE_FROM_CACHE);
        assertTrue(command4.isResponseFromCache());
        assertEquals(command4.getExecutionTimeInMilliseconds(), -1);

        // semaphore isolated
        assertFalse(command1.isExecutedInThread());
        assertFalse(command2.isExecutedInThread());
        assertFalse(command3.isExecutedInThread());
        assertFalse(command4.isExecutedInThread());
    }

    /**
     * Test Request scoped caching with a mixture of commands
     */
    @Test
    public void testNoRequestCache3UsingThreadIsolation() {
        SuccessfulCacheableCommand<String> command1 = new SuccessfulCacheableCommand<>(false, "A");
        SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(false, "B");
        SuccessfulCacheableCommand<String> command3 = new SuccessfulCacheableCommand<>(false, "A");

        assertTrue(command1.isCommandRunningInThread());

        Future<String> f1 = command1.observe().toBlocking().toFuture();
        Future<String> f2 = command2.observe().toBlocking().toFuture();
        Future<String> f3 = command3.observe().toBlocking().toFuture();

        try {
            assertEquals("A", f1.get());
            assertEquals("B", f2.get());
            assertEquals("A", f3.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertTrue(command1.executed);
        // both should execute as they are different
        assertTrue(command2.executed);
        // this should also execute since we disabled the cache
        assertTrue(command3.executed);

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3, HystrixEventType.EMIT, HystrixEventType.SUCCESS);

        // thread isolated
        assertTrue(command1.isExecutedInThread());
        assertTrue(command2.isExecutedInThread());
        assertTrue(command3.isExecutedInThread());
    }

    @Test
    public void testRequestCacheOnThreadRejectionThrowsException() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);
        RequestCacheThreadRejection r1 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r1: " + r1.observe().toBlocking().single());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            e.printStackTrace();
            assertTrue(r1.isResponseRejected());
            assertNotNull(r1.getExecutionException());
            // what we want
        }

        RequestCacheThreadRejection r2 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r2: " + r2.observe().toBlocking().single());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            //                e.printStackTrace();
            assertTrue(r2.isResponseRejected());
            assertNotNull(r2.getExecutionException());
            // what we want
        }

        RequestCacheThreadRejection r3 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("f3: " + r3.observe().toBlocking().toFuture().get());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (ExecutionException e) {
            assertTrue(r3.isResponseRejected());
            assertTrue(e.getCause() instanceof HystrixRuntimeException);
            assertNotNull(r3.getExecutionException());
        }

        // let the command finish (only 1 should actually be blocked on this due to the response cache)
        completionLatch.countDown();

        // then another after the command has completed
        RequestCacheThreadRejection r4 = new RequestCacheThreadRejection(completionLatch);
        try {
            System.out.println("r4: " + r4.observe().toBlocking().single());
            // we should have thrown an exception
            fail("expected a rejection");
        } catch (HystrixRuntimeException e) {
            //                e.printStackTrace();
            assertTrue(r4.isResponseRejected());
            assertNotNull(r4.getExecutionException());
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
    public void testBasicExecutionWorksWithoutRequestVariable() {
        try {
            /* force the RequestVariable to not be initialized */
            HystrixRequestContext.setContextOnCurrentThread(null);

            TestHystrixObservableCommand<Boolean> command = new SuccessfulTestCommand(ExecutionIsolationStrategy.SEMAPHORE);
            assertEquals(true, command.observe().toBlocking().single());

            TestHystrixObservableCommand<Boolean> command2 = new SuccessfulTestCommand(ExecutionIsolationStrategy.SEMAPHORE);
            assertEquals(true, command2.observe().toBlocking().toFuture().get());

            // we should be able to execute without a RequestVariable if ...
            // 1) We don't have a cacheKey
            // 2) We don't do collapsing

            // semaphore isolated
            assertFalse(command.isExecutedInThread());
            assertNull(command.getExecutionException());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception => " + e.getMessage());
        }
    }

    /**
     * Test that if we try and execute a command with a cacheKey without initializing RequestVariable that it gives an error.
     */
    @Test
    public void testCacheKeyExecutionRequiresRequestVariable() {
        try {
            /* force the RequestVariable to not be initialized */
            HystrixRequestContext.setContextOnCurrentThread(null);

            SuccessfulCacheableCommand<String> command = new SuccessfulCacheableCommand<>(true, "one");
            assertEquals("one", command.observe().toBlocking().single());

            SuccessfulCacheableCommand<String> command2 = new SuccessfulCacheableCommand<>(true, "two");
            assertEquals("two", command2.observe().toBlocking().toFuture().get());

            fail("We expect an exception because cacheKey requires RequestVariable.");

            // semaphore isolated
            assertFalse(command.isExecutedInThread());
            assertNull(command.getExecutionException());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Test that a BadRequestException can be synchronously thrown from a semaphore-isolated command and not count towards errors.
     */
    @Test
    public void testSemaphoreIsolatedBadRequestSyncExceptionObserve() {
        testBadRequestExceptionObserve(ExecutionIsolationStrategy.SEMAPHORE, KnownHystrixBadRequestFailureTestCommand.SYNC_EXCEPTION);
    }

    /**
     * Test that a BadRequestException can be asynchronously thrown from a semaphore-isolated command and not count towards errors.
     */
    @Test
    public void testSemaphoreIsolatedBadRequestAsyncExceptionObserve() {
        testBadRequestExceptionObserve(ExecutionIsolationStrategy.SEMAPHORE, KnownHystrixBadRequestFailureTestCommand.ASYNC_EXCEPTION);
    }

    /**
     * Test that a BadRequestException can be synchronously thrown from a thread-isolated command and not count towards errors.
     */
    @Test
    public void testThreadIsolatedBadRequestSyncExceptionObserve() {
        testBadRequestExceptionObserve(ExecutionIsolationStrategy.THREAD, KnownHystrixBadRequestFailureTestCommand.SYNC_EXCEPTION);
    }

    /**
     * Test that a BadRequestException can be asynchronously thrown from a thread-isolated command and not count towards errors.
     */
    @Test
    public void testThreadIsolatedBadRequestAsyncExceptionObserve() {
        testBadRequestExceptionObserve(ExecutionIsolationStrategy.THREAD, KnownHystrixBadRequestFailureTestCommand.ASYNC_EXCEPTION);
    }

    private void testBadRequestExceptionObserve(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
        KnownHystrixBadRequestFailureTestCommand command1 = new KnownHystrixBadRequestFailureTestCommand(isolationStrategy, asyncException);
        try {
            command1.observe().toBlocking().single();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (HystrixBadRequestException e) {
            // success
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
            fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
        assertNotNull(command1.getExecutionException());
    }

    /**
     * Test that synchronous BadRequestException behavior works the same on a cached response for a semaphore-isolated command.
     */
    @Test
    public void testSyncBadRequestExceptionOnResponseFromCacheInSempahore() {
        testBadRequestExceptionOnResponseFromCache(ExecutionIsolationStrategy.SEMAPHORE, KnownHystrixBadRequestFailureTestCommand.SYNC_EXCEPTION);
    }

    /**
     * Test that asynchronous BadRequestException behavior works the same on a cached response for a semaphore-isolated command.
     */
    @Test
    public void testAsyncBadRequestExceptionOnResponseFromCacheInSemaphore() {
        testBadRequestExceptionOnResponseFromCache(ExecutionIsolationStrategy.SEMAPHORE, KnownHystrixBadRequestFailureTestCommand.ASYNC_EXCEPTION);
    }

    /**
     * Test that synchronous BadRequestException behavior works the same on a cached response for a thread-isolated command.
     */
    @Test
    public void testSyncBadRequestExceptionOnResponseFromCacheInThread() {
        testBadRequestExceptionOnResponseFromCache(ExecutionIsolationStrategy.THREAD, KnownHystrixBadRequestFailureTestCommand.SYNC_EXCEPTION);
    }

    /**
     * Test that asynchronous BadRequestException behavior works the same on a cached response for a thread-isolated command.
     */
    @Test
    public void testAsyncBadRequestExceptionOnResponseFromCacheInThread() {
        testBadRequestExceptionOnResponseFromCache(ExecutionIsolationStrategy.THREAD, KnownHystrixBadRequestFailureTestCommand.ASYNC_EXCEPTION);
    }

    private void testBadRequestExceptionOnResponseFromCache(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
        KnownHystrixBadRequestFailureTestCommand command1 = new KnownHystrixBadRequestFailureTestCommand(isolationStrategy, asyncException);
        // execute once to cache the value
        try {
            command1.observe().toBlocking().single();
        } catch (Throwable e) {
            // ignore
        }

        KnownHystrixBadRequestFailureTestCommand command2 = new KnownHystrixBadRequestFailureTestCommand(isolationStrategy, asyncException);
        try {
            command2.observe().toBlocking().toFuture().get();
            fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
        } catch (ExecutionException e) {
            e.printStackTrace();
            if (!(e.getCause() instanceof HystrixBadRequestException)) {
                fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }

        assertCommandExecutionEvents(command1, HystrixEventType.BAD_REQUEST);
        assertCommandExecutionEvents(command2, HystrixEventType.BAD_REQUEST);
        assertNotNull(command1.getExecutionException());
        assertNotNull(command2.getExecutionException());
    }

    /**
     * Test a checked Exception being thrown
     */
    @Test
    public void testCheckedExceptionViaExecute() {
        CommandWithCheckedException command = new CommandWithCheckedException();
        try {
            command.observe().toBlocking().single();
            fail("we expect to receive a " + Exception.class.getSimpleName());
        } catch (Exception e) {
            assertEquals("simulated checked exception message", e.getCause().getMessage());
        }

        assertEquals("simulated checked exception message", command.getFailedExecutionException().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertNotNull(command.getExecutionException());

        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
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
        // semaphore isolated
        assertFalse(command.isExecutedInThread());
    }

    /**
     * Test a java.lang.Error being thrown
     *
     * @throws InterruptedException
     */
    @Test
    public void testErrorThrownViaObserve() throws InterruptedException {
        CommandWithErrorThrown command = new CommandWithErrorThrown(true);
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
        // the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
        // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
        assertEquals("simulated java.lang.Error message", t.get().getCause().getCause().getMessage());
        assertEquals("simulated java.lang.Error message", command.getFailedExecutionException().getCause().getMessage());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isFailedExecution());
        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        assertNotNull(command.getExecutionException());
        assertFalse(command.isExecutedInThread());
    }

    @Override
    protected void assertHooksOnSuccess(Func0<TestHystrixObservableCommand<Integer>> ctor, Action1<TestHystrixObservableCommand<Integer>> assertion) {
        assertBlockingObserve(ctor.call(), assertion, true);
        assertNonBlockingObserve(ctor.call(), assertion, true);
    }

    @Override
    protected void assertHooksOnFailure(Func0<TestHystrixObservableCommand<Integer>> ctor, Action1<TestHystrixObservableCommand<Integer>> assertion) {
        assertBlockingObserve(ctor.call(), assertion, false);
        assertNonBlockingObserve(ctor.call(), assertion, false);
    }

    @Override
    void assertHooksOnFailure(Func0<TestHystrixObservableCommand<Integer>> ctor, Action1<TestHystrixObservableCommand<Integer>> assertion, boolean failFast) {

    }

    /*
     *********************** HystrixObservableCommand-specific THREAD-ISOLATED Execution Hook Tests **************************************
     */

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: EMITx4, SUCCESS
     */
    @Test
    public void testExecutionHookThreadMultipleEmitsAndThenSuccess() {
        assertHooksOnSuccess(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.MULTIPLE_EMITS_THEN_SUCCESS),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(4, 0, 1));
                    assertTrue(hook.executionEventsMatch(4, 0, 1));
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionEmit - onEmit - onExecutionEmit - onEmit - onExecutionEmit - onEmit - onExecutionEmit - onEmit - onExecutionSuccess - onThreadComplete - onSuccess - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: EMITx4, FAILURE
     */
    @Test
    public void testExecutionHookThreadMultipleEmitsThenError() {
        assertHooksOnSuccess(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.MULTIPLE_EMITS_THEN_FAILURE, 0),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(8, 0, 1));
                    assertTrue(hook.executionEventsMatch(4, 1, 0));
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - !onRunStart - onExecutionStart - " +
                            "onExecutionEmit - !onRunSuccess - !onComplete - onEmit - " +
                            "onExecutionEmit - !onRunSuccess - !onComplete - onEmit - " +
                            "onExecutionEmit - !onRunSuccess - !onComplete - onEmit - " +
                            "onExecutionEmit - !onRunSuccess - !onComplete - onEmit - " +
                            "onExecutionError - !onRunError - onThreadComplete - !onComplete - onEmit - " +
                            "!onComplete - onEmit - " +
                            "!onComplete - onEmit - " +
                            "!onComplete - onEmit - " +
                            "onSuccess - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: asynchronous HystrixBadRequestException
     */
    @Test
    public void testExecutionHookThreadAsyncBadRequestException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.ASYNC_BAD_REQUEST),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(HystrixBadRequestException.class, hook.getCommandException().getClass());
                    assertEquals(HystrixBadRequestException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: async HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadAsyncException2() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.ASYNC_FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: async HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadAsyncException4() {
        assertHooksOnSuccess(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.ASYNC_FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(1, 0, 1));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - !onRunStart - onExecutionStart - onExecutionError - !onRunError - onThreadComplete - !onComplete - onEmit - onSuccess - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: sync HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadSyncException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: async HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadAsyncException3() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.ASYNC_FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     * Thread Pool fullInteger : NO
     * Thread Pool Queue fullInteger: NO
     * Execution Result: async HystrixRuntimeException
     */
    @Test
    public void testExecutionHookThreadAsyncException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.THREAD, AbstractTestHystrixCommand.ExecutionResult.ASYNC_FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onThreadStart - onExecutionStart - onExecutionError - onThreadComplete - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: THREAD
     */
    @Test
    public void testExecutionHookThread() {
        assertHooksOnFailure(
                () -> getCircuitOpenCommand(ExecutionIsolationStrategy.THREAD),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /*
     *********************** END HystrixObservableCommand-specific THREAD-ISOLATED Execution Hook Tests **************************************
     */

    /*
     ********************* HystrixObservableCommand-specific SEMAPHORE-ISOLATED Execution Hook Tests ***********************************
     */

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reachedInteger : NO
     * Execution Result: HystrixRuntimeException
     */
    @Test
    public void testExecutionHookSemaphoreException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.SEMAPHORE, AbstractTestHystrixCommand.ExecutionResult.FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onExecutionStart - onExecutionError - onError - ", command.getBuilder().executionHook.executionSequence.toString());
                });
    }

    /*
     ********************* END HystrixObservableCommand-specific SEMAPHORE-ISOLATED Execution Hook Tests ***********************************
     */

    /**
     * Test a command execution that fails.
     */
    @Test
    public void testExecutionFailure() {
        TestHystrixObservableCommand<Boolean> commandEnabled = new KnownFailureTestCommand2(true);
        try {
            assertEquals(false, commandEnabled.observe().toBlocking().single());
        } catch (Exception e) {
            e.printStackTrace();
        }

        TestHystrixObservableCommand<Boolean> commandDisabled = new KnownFailureTestCommand2(true);
        try {
            assertEquals(false, commandDisabled.observe().toBlocking().single());
            fail("expect exception thrown");
        } catch (Exception e) {
            // expected
        }

        assertEquals("we failed with a simulated issue", commandDisabled.getFailedExecutionException().getMessage());

        assertTrue(commandDisabled.isFailedExecution());
        assertNotNull(commandDisabled.getExecutionException());

        assertCommandExecutionEvents(commandEnabled, HystrixEventType.FAILURE);
        assertCommandExecutionEvents(commandDisabled, HystrixEventType.FAILURE);
    }

    @Test
    public void testRejectedViaSemaphoreIsolation() {
        final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<>(2);

        final TryableSemaphoreActual semaphore = new TryableSemaphoreActual(HystrixProperty.Factory.asProperty(1));

        //used to wait until all commands have started
        final CountDownLatch startLatch = new CountDownLatch(2);

        // used to signal that all command can finish
        final CountDownLatch sharedLatch = new CountDownLatch(1);

        final LatchedSemaphoreCommand command1 = new LatchedSemaphoreCommand(semaphore, startLatch, sharedLatch);
        final LatchedSemaphoreCommand command2 = new LatchedSemaphoreCommand(semaphore, startLatch, sharedLatch);

        Observable<Boolean> merged = Observable.merge(command1.toObservable(), command2.toObservable())
                .subscribeOn(Schedulers.computation());

        final CountDownLatch terminal = new CountDownLatch(1);

        merged.subscribe(new Subscriber<Boolean>() {
            @Override
            public void onCompleted() {
                System.out.println(Thread.currentThread().getName() + " OnCompleted");
                terminal.countDown();
            }

            @Override
            public void onError(Throwable e) {
                System.out.println(Thread.currentThread().getName() + " OnError : " + e);
                terminal.countDown();
            }

            @Override
            public void onNext(Boolean b) {
                System.out.println(Thread.currentThread().getName() + " OnNext : " + b);
                results.offer(b);
            }
        });

        try {
            assertTrue(startLatch.await(1000, TimeUnit.MILLISECONDS));
            sharedLatch.countDown();
            assertTrue(terminal.await(1000, TimeUnit.MILLISECONDS));
        } catch (Throwable ex) {
            ex.printStackTrace();
            fail(ex.getMessage());
        }

        // one thread should have returned values
        assertEquals(2, results.size());
        //1 should have gotten the normal value, the other
        assertTrue(results.contains(Boolean.TRUE));
        assertTrue(results.contains(Boolean.FALSE));

        assertCommandExecutionEvents(command1, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2, HystrixEventType.SEMAPHORE_REJECTED);
    }

    @Test
    public void testRejectedViaThreadIsolation() throws InterruptedException {
        final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<>(10);
        final List<Thread> executionThreads = Collections.synchronizedList(new ArrayList<>(20));
        final List<Thread> responseThreads = Collections.synchronizedList(new ArrayList<>(10));

        final AtomicBoolean exceptionReceived = new AtomicBoolean();
        final CountDownLatch scheduleLatch = new CountDownLatch(2);
        final CountDownLatch successLatch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<TestThreadIsolationWithSemaphoreSetSmallCommand> command1Ref = new AtomicReference<>();
        final AtomicReference<TestThreadIsolationWithSemaphoreSetSmallCommand> command2Ref = new AtomicReference<>();
        final AtomicReference<TestThreadIsolationWithSemaphoreSetSmallCommand> command3Ref = new AtomicReference<>();

        Runnable r1 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            final boolean shouldExecute = count.incrementAndGet() < 3;
            try {
                executionThreads.add(Thread.currentThread());
                TestThreadIsolationWithSemaphoreSetSmallCommand command1 = new TestThreadIsolationWithSemaphoreSetSmallCommand(2, () -> {
                    // make sure it's deterministic and we put 2 threads into the pool before the 3rd is submitted
                    if (shouldExecute) {
                        try {
                            scheduleLatch.countDown();
                            successLatch.await();
                        } catch (InterruptedException e) {
                            // No action needed. Simply exit this method.
                        }
                    }
                });
                command1Ref.set(command1);
                results.add(command1.toObservable().map(b -> {
                    responseThreads.add(Thread.currentThread());
                    return b;
                }).doAfterTerminate(() -> {
                    if (!shouldExecute) {
                        // the final thread that shouldn't execute releases the latch once it has run
                        // so it is deterministic that the other two fill the thread pool until this one rejects
                        successLatch.countDown();
                    }
                }).toBlocking().single());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        Runnable r2 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            final boolean shouldExecute = count.incrementAndGet() < 3;
            try {
                executionThreads.add(Thread.currentThread());
                TestThreadIsolationWithSemaphoreSetSmallCommand command2 = new TestThreadIsolationWithSemaphoreSetSmallCommand(2, () -> {
                    // make sure it's deterministic and we put 2 threads into the pool before the 3rd is submitted
                    if (shouldExecute) {
                        try {
                            scheduleLatch.countDown();
                            successLatch.await();
                        } catch (InterruptedException e) {
                            // No action needed. Simply exit this method.
                        }
                    }
                });
                command2Ref.set(command2);
                results.add(command2.toObservable().map(b -> {
                    responseThreads.add(Thread.currentThread());
                    return b;
                }).doAfterTerminate(() -> {
                    if (!shouldExecute) {
                        // the final thread that shouldn't execute releases the latch once it has run
                        // so it is deterministic that the other two fill the thread pool until this one rejects
                        successLatch.countDown();
                    }
                }).toBlocking().single());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        Runnable r3 = new HystrixContextRunnable(HystrixPlugins.getInstance().getConcurrencyStrategy(), () -> {
            final boolean shouldExecute = count.incrementAndGet() < 3;
            try {
                executionThreads.add(Thread.currentThread());
                TestThreadIsolationWithSemaphoreSetSmallCommand command3 = new TestThreadIsolationWithSemaphoreSetSmallCommand(2, () -> {
                    // make sure it's deterministic and we put 2 threads into the pool before the 3rd is submitted
                    if (shouldExecute) {
                        try {
                            scheduleLatch.countDown();
                            successLatch.await();
                        } catch (InterruptedException e) {
                            // No action needed. Simply exit this method.
                        }
                    }
                });
                command3Ref.set(command3);
                results.add(command3.toObservable().map(b -> {
                    responseThreads.add(Thread.currentThread());
                    return b;
                }).doAfterTerminate(() -> {
                    if (!shouldExecute) {
                        // the final thread that shouldn't execute releases the latch once it has run
                        // so it is deterministic that the other two fill the thread pool until this one rejects
                        successLatch.countDown();
                    }
                }).toBlocking().single());
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived.set(true);
            }
        });

        // 2 threads, the second should be rejected by the semaphore
        Thread t1 = new Thread(r1);
        Thread t2 = new Thread(r2);
        Thread t3 = new Thread(r3);

        t1.start();
        t2.start();
        // wait for the previous 2 thread to be running before starting otherwise it can race
        scheduleLatch.await(500, TimeUnit.MILLISECONDS);
        t3.start();
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (Exception e) {
            e.printStackTrace();
            fail("failed waiting on threads");
        }

        // we should have 2 of the 3 return results
        assertEquals(2, results.size());
        // the other thread should have thrown an Exception
        assertTrue(exceptionReceived.get());

        assertCommandExecutionEvents(command1Ref.get(), HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command2Ref.get(), HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        assertCommandExecutionEvents(command3Ref.get(), HystrixEventType.THREAD_POOL_REJECTED);
    }

    /* ******************************************************************************************************** */
    /* *************************************** Request Context Testing Below ********************************** */
    /* ******************************************************************************************************** */

    private RequestContextTestResults testRequestContextOnSuccess(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolation))) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> {
                    results.isContextInitialized.set(HystrixRequestContext.isCurrentThreadInitialized());
                    results.originThread.set(Thread.currentThread());
                    s.onNext(true);
                    s.onCompleted();
                }).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(1, results.ts.getOnNextEvents().size());
        assertTrue(results.ts.getOnNextEvents().get(0));

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertTrue(command.isSuccessfulExecution());

        assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
        return results;
    }

    private RequestContextTestResults testRequestContextOnGracefulFailure(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolation))) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> {
                    results.isContextInitialized.set(HystrixRequestContext.isCurrentThreadInitialized());
                    results.originThread.set(Thread.currentThread());
                    s.onError(new RuntimeException("graceful onError"));
                }).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(1, results.ts.getOnErrorEvents().size());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command.isSuccessfulExecution());
        assertTrue(command.isFailedExecution());

        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        return results;
    }

    private RequestContextTestResults testRequestContextOnBadFailure(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolation))) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> {
                    results.isContextInitialized.set(HystrixRequestContext.isCurrentThreadInitialized());
                    results.originThread.set(Thread.currentThread());
                    throw new RuntimeException("bad onError");
                }).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(1, results.ts.getOnErrorEvents().size());

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command.isSuccessfulExecution());
        assertTrue(command.isFailedExecution());

        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        return results;
    }

    private RequestContextTestResults testRequestContextOnFailure(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolation))) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> s.onError(new RuntimeException("onError"))).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(0, results.ts.getOnErrorEvents().size());
        assertEquals(1, results.ts.getOnNextEvents().size());
        assertEquals(false, results.ts.getOnNextEvents().get(0));

        assertTrue(command.getExecutionTimeInMilliseconds() > -1);
        assertFalse(command.isSuccessfulExecution());
        assertTrue(command.isFailedExecution());

        assertCommandExecutionEvents(command, HystrixEventType.FAILURE);
        return results;
    }

    private RequestContextTestResults testRequestContextOnRejection(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                        .withExecutionIsolationStrategy(isolation)
                        .withExecutionIsolationSemaphoreMaxConcurrentRequests(0))
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

                })) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> s.onError(new RuntimeException("onError"))).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(0, results.ts.getOnErrorEvents().size());
        assertEquals(1, results.ts.getOnNextEvents().size());
        assertEquals(false, results.ts.getOnNextEvents().get(0));

        assertFalse(command.isSuccessfulExecution());
        assertTrue(command.isResponseRejected());

        if (isolation == ExecutionIsolationStrategy.SEMAPHORE) {
            assertCommandExecutionEvents(command, HystrixEventType.SEMAPHORE_REJECTED);
        } else {
            assertCommandExecutionEvents(command, HystrixEventType.THREAD_POOL_REJECTED);
        }
        return results;
    }

    private RequestContextTestResults testRequestContext(ExecutionIsolationStrategy isolation, final Scheduler userScheduler) {
        final RequestContextTestResults results = new RequestContextTestResults();
        TestHystrixObservableCommand<Boolean> command = new TestHystrixObservableCommand<Boolean>(TestHystrixObservableCommand.testPropsBuilder()
                .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                        .withExecutionIsolationStrategy(isolation))) {

            @Override
            protected Observable<Boolean> construct() {
                return Observable.create((OnSubscribe<Boolean>) s -> s.onError(new RuntimeException("onError"))).subscribeOn(userScheduler);
            }

        };

        results.command = command;

        command.toObservable().doOnEach(n -> {
            results.isContextInitializedObserveOn.set(HystrixRequestContext.isCurrentThreadInitialized());
            results.observeOnThread.set(Thread.currentThread());
        }).subscribe(results.ts);
        results.ts.awaitTerminalEvent();

        System.out.println("Run => Initialized: " + results.isContextInitialized.get() + "  Thread: " + results.originThread.get());
        System.out.println("Observed => Initialized: " + results.isContextInitializedObserveOn.get() + "  Thread: " + results.observeOnThread.get());

        assertEquals(0, results.ts.getOnErrorEvents().size());
        assertEquals(1, results.ts.getOnNextEvents().size());
        assertEquals(false, results.ts.getOnNextEvents().get(0));

        assertEquals(command.getExecutionTimeInMilliseconds(), -1);
        assertFalse(command.isSuccessfulExecution());

        return results;
    }

    private final class RequestContextTestResults {
        volatile TestHystrixObservableCommand<Boolean> command;
        final AtomicReference<Thread> originThread = new AtomicReference<>();
        final AtomicBoolean isContextInitialized = new AtomicBoolean();
        TestSubscriber<Boolean> ts = new TestSubscriber<>();
        final AtomicBoolean isContextInitializedObserveOn = new AtomicBoolean();
        final AtomicReference<Thread> observeOnThread = new AtomicReference<>();
    }

    /* *************************************** testSuccessfulRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testSuccessfulRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testSuccessfulRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testSuccessfulRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testSuccessfulRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertTrue(results.originThread.get().getName().startsWith("hystrix-OWNER_ONE")); // thread isolated on a HystrixThreadPool

        assertTrue(results.isContextInitializedObserveOn.get());
        assertTrue(results.observeOnThread.get().getName().startsWith("hystrix-OWNER_ONE"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testSuccessfulRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testSuccessfulRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnSuccess(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /* *************************************** testGracefulFailureRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testGracefulFailureRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testGracefulFailureRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testGracefulFailureRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testGracefulFailureRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertTrue(results.originThread.get().getName().startsWith("hystrix-OWNER_ONE")); // thread isolated on a HystrixThreadPool

        assertTrue(results.isContextInitializedObserveOn.get());
        assertTrue(results.observeOnThread.get().getName().startsWith("hystrix-OWNER_ONE"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testGracefulFailureRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testGracefulFailureRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnGracefulFailure(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /* *************************************** testBadFailureRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testBadFailureRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testBadFailureRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testBadFailureRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testBadFailureRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertTrue(results.originThread.get().getName().startsWith("hystrix-OWNER_ONE")); // thread isolated on a HystrixThreadPool

        assertTrue(results.isContextInitializedObserveOn.get());
        assertTrue(results.observeOnThread.get().getName().startsWith("hystrix-OWNER_ONE"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testBadFailureRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testBadFailureRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnBadFailure(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /* *************************************** testFailureRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testFailureRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testFailureRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testFailureRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testFailureRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertTrue(results.originThread.get().getName().startsWith("hystrix-OWNER_ONE")); // thread isolated on a HystrixThreadPool

        assertTrue(results.isContextInitializedObserveOn.get());
        assertTrue(results.observeOnThread.get().getName().startsWith("hystrix-OWNER_ONE"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testFailureWRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testFailureRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnFailure(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated
        assertTrue(results.command.isExecutedInThread());
    }

    /* *************************************** testRejectionRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testRejectionRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testRejectionRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testRejectionRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testRejectionRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread());

        assertTrue(results.isContextInitializedObserveOn.get());
        System.out.println("results.observeOnThread.get(): " + results.observeOnThread.get() + "  " + Thread.currentThread());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // rejected so we stay on calling thread

        // thread isolated, but rejected, so this is false
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testRejectionRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread"));

        // thread isolated, but rejected, so this is false
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testRejectionRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContextOnRejection(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // thread isolated, but rejected, so this is false
        assertFalse(results.command.isExecutedInThread());
    }

    /* *************************************** testRequestContext *********************************** */

    /**
     * Synchronous Observable and semaphore isolation. Only [Main] thread is involved in this.
     */
    @Test
    public void testRequestContextWithSemaphoreIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread()); // all synchronous

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // all synchronous

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation. User provided thread [RxNewThread] does everything.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testRequestContextWithSemaphoreIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.SEMAPHORE, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testSRequestContextWithSemaphoreIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.SEMAPHORE, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // semaphore isolated
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Synchronous Observable and thread isolation. Work done on [hystrix-OWNER_ONE] thread and then observed on [RxComputation]
     */
    @Test
    public void testRequestContextWithThreadIsolatedSynchronousObservable() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.THREAD, Schedulers.immediate());

        assertTrue(results.isContextInitialized.get());
        assertEquals(results.originThread.get(), Thread.currentThread());

        assertTrue(results.isContextInitializedObserveOn.get());
        assertEquals(results.observeOnThread.get(), Thread.currentThread()); // rejected so we stay on calling thread

        // thread isolated ... but rejected so not executed in a thread
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and thread isolation. User provided thread [RxNetThread] executes Observable and then [RxComputation] observes the onNext calls.
     *
     * NOTE: RequestContext will NOT exist on that thread.
     *
     * An async Observable running on its own thread will not have access to the request context unless the user manages the context.
     */
    @Test
    public void testRequestContextWithThreadIsolatedAsynchronousObservable() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.THREAD, Schedulers.newThread());

        assertFalse(results.isContextInitialized.get()); // it won't have request context as it's on a user provided thread/scheduler
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // we capture and set the context once the user provided Observable emits
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // thread isolated ... but rejected so not executed in a thread
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Async Observable and semaphore isolation WITH functioning RequestContext
     *
     * Use HystrixContextScheduler to make the user provided scheduler capture context.
     */
    @Test
    public void testRequestContextWithThreadIsolatedAsynchronousObservableAndCapturedContextScheduler() {
        RequestContextTestResults results = testRequestContext(ExecutionIsolationStrategy.THREAD, new HystrixContextScheduler(Schedulers.newThread()));

        assertTrue(results.isContextInitialized.get()); // the user scheduler captures context
        assertTrue(results.originThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        assertTrue(results.isContextInitializedObserveOn.get()); // the user scheduler captures context
        assertTrue(results.observeOnThread.get().getName().startsWith("RxNewThread")); // the user provided thread/scheduler

        // thread isolated ... but rejected so not executed in a thread
        assertFalse(results.command.isExecutedInThread());
    }

    /**
     * Test support of multiple onNext events.
     */
    @Test
    public void testExecutionSuccessWithMultipleEvents() {
        try {
            TestCommandWithMultipleValues command = new TestCommandWithMultipleValues();
            assertEquals(Arrays.asList(true, false, true), command.observe().toList().toBlocking().single());

            assertNull(command.getFailedExecutionException());
            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isSuccessfulExecution());
            assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.SUCCESS);
            // semaphore isolated
            assertFalse(command.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    /**
     * Test behavior when some onNext are received and then a failure.
     */
    @Test
    public void testExecutionPartialSuccess2() {
        try {
            TestPartialSuccess command = new TestPartialSuccess();
            TestSubscriber<Integer> ts = new TestSubscriber<>();
            command.toObservable().subscribe(ts);
            ts.awaitTerminalEvent();
            ts.assertReceivedOnNext(Arrays.asList(1, 2, 3));
            assertEquals(1, ts.getOnErrorEvents().size());

            assertFalse(command.isSuccessfulExecution());
            assertTrue(command.isFailedExecution());

            // we will have an exception
            assertNotNull(command.getFailedExecutionException());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.FAILURE);

            // semaphore isolated
            assertFalse(command.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    /**
     * Test behavior when some onNext are received and then a failure.
     */
    @Test
    public void testExecutionPartialSuccess() {
        try {
            TestPartialSuccess2 command = new TestPartialSuccess2();
            TestSubscriber<Boolean> ts = new TestSubscriber<>();
            command.toObservable().subscribe(ts);
            ts.awaitTerminalEvent();
            ts.assertReceivedOnNext(Arrays.asList(false, true, false, true, false, true, false));
            ts.assertNoErrors();

            assertFalse(command.isSuccessfulExecution());
            assertTrue(command.isFailedExecution());

            assertNotNull(command.getFailedExecutionException());
            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertCommandExecutionEvents(command, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.EMIT, HystrixEventType.FAILURE);
            // semaphore isolated
            assertFalse(command.isExecutedInThread());
        } catch (Exception e) {
            e.printStackTrace();
            fail("We received an exception.");
        }
    }

    @Test
    public void testEarlyUnsubscribeDuringExecutionViaToObservable() {
        class AsyncCommand extends HystrixObservableCommand<Boolean> {

            public AsyncCommand() {
                super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            }

            @Override
            protected Observable<Boolean> construct() {
                return Observable.defer(() -> {
                    try {
                        Thread.sleep(100);
                        return Observable.just(true);
                    } catch (InterruptedException ex) {
                        return Observable.error(ex);
                    }
                }).subscribeOn(Schedulers.io());
            }
        }

        HystrixObservableCommand<Boolean> cmd = new AsyncCommand();

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
                        latch.countDown();
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
            s.unsubscribe();
            assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use", 0, cmd.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(cmd.isExecutionComplete());
            assertFalse(cmd.isExecutedInThread());
            System.out.println("EventCounts : " + cmd.getEventCounts());
            System.out.println("Execution Time : " + cmd.getExecutionTimeInMilliseconds());
            System.out.println("Is Successful : " + cmd.isSuccessfulExecution());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testEarlyUnsubscribeDuringExecutionViaObserve() {
        class AsyncCommand extends HystrixObservableCommand<Boolean> {

            public AsyncCommand() {
                super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            }

            @Override
            protected Observable<Boolean> construct() {
                return Observable.defer(() -> {
                    try {
                        Thread.sleep(100);
                        return Observable.just(true);
                    } catch (InterruptedException ex) {
                        return Observable.error(ex);
                    }
                }).subscribeOn(Schedulers.io());
            }
        }

        HystrixObservableCommand<Boolean> cmd = new AsyncCommand();

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
                        latch.countDown();
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
            s.unsubscribe();
            assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
            assertEquals("Number of execution semaphores in use", 0, cmd.getExecutionSemaphore().getNumberOfPermitsUsed());
            assertFalse(cmd.isExecutionComplete());
            assertFalse(cmd.isExecutedInThread());
            System.out.println("EventCounts : " + cmd.getEventCounts());
            System.out.println("Execution Time : " + cmd.getExecutionTimeInMilliseconds());
            System.out.println("Is Successful : " + cmd.isSuccessfulExecution());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }


    @Test
    public void testEarlyUnsubscribe() {
        class AsyncCommand extends HystrixObservableCommand<Boolean> {

            public AsyncCommand() {
                super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ASYNC")));
            }

            @Override
            protected Observable<Boolean> construct() {
                return Observable.error(new RuntimeException("construct failure"));
            }
        }

        HystrixObservableCommand<Boolean> cmd = new AsyncCommand();

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
                        latch.countDown();
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
            assertFalse(cmd.isExecutedInThread());
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }


    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* private HystrixCommand class implementations for unit testing */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    static AtomicInteger uniqueNameCounter = new AtomicInteger(0);

    @Override
    TestHystrixObservableCommand<Integer> getCommand(ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, AbstractCommand.TryableSemaphore executionSemaphore) {
        HystrixCommandKey commandKey = HystrixCommandKey.Factory.asKey("FlexibleObservable-" + uniqueNameCounter.getAndIncrement());
        return FlexibleTestHystrixObservableCommand.from(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    @Override
    TestHystrixObservableCommand<Integer> getCommand(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, AbstractCommand.TryableSemaphore executionSemaphore) {
        return FlexibleTestHystrixObservableCommand.from(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    private static class FlexibleTestHystrixObservableCommand  {
        public static AbstractFlexibleTestHystrixObservableCommand from(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, AbstractTestHystrixCommand.CacheEnabled cacheEnabled, Object value, AbstractCommand.TryableSemaphore executionSemaphore) {
            return new FlexibleTestHystrixObservableCommand2(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
        }
    }

    private static class AbstractFlexibleTestHystrixObservableCommand extends TestHystrixObservableCommand<Integer> {
        private final AbstractTestHystrixCommand.ExecutionResult executionResult;
        private final int executionLatency;
        private final CacheEnabled cacheEnabled;
        private final Object value;

        public AbstractFlexibleTestHystrixObservableCommand(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
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
        protected Observable<Integer> construct() {
            if (executionResult == AbstractTestHystrixCommand.ExecutionResult.FAILURE) {
                addLatency(executionLatency);
                throw new RuntimeException("Execution Sync Failure for TestHystrixObservableCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.HYSTRIX_FAILURE) {
                addLatency(executionLatency);
                throw new HystrixRuntimeException(HystrixRuntimeException.FailureType.COMMAND_EXCEPTION, AbstractFlexibleTestHystrixObservableCommand.class, "Execution Hystrix Failure for TestHystrixObservableCommand", new RuntimeException("Execution Failure for TestHystrixObservableCommand"));
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.NOT_WRAPPED_FAILURE) {
                addLatency(executionLatency);
                throw new NotWrappedByHystrixTestRuntimeException();
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.RECOVERABLE_ERROR) {
                addLatency(executionLatency);
                throw new java.lang.Error("Execution Sync Error for TestHystrixObservableCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.UNRECOVERABLE_ERROR) {
                addLatency(executionLatency);
                throw new OutOfMemoryError("Execution Sync OOME for TestHystrixObservableCommand");
            } else if (executionResult == AbstractTestHystrixCommand.ExecutionResult.BAD_REQUEST) {
                addLatency(executionLatency);
                throw new HystrixBadRequestException("Execution Bad Request Exception for TestHystrixObservableCommand");
            }
            return Observable.create(subscriber -> {
                System.out.println(System.currentTimeMillis() + " : " + Thread.currentThread().getName() + " construct() method has been subscribed to");
                addLatency(executionLatency);
                if (executionResult == ExecutionResult.SUCCESS) {
                    subscriber.onNext(1);
                    subscriber.onCompleted();
                } else if (executionResult == ExecutionResult.MULTIPLE_EMITS_THEN_SUCCESS) {
                    subscriber.onNext(2);
                    subscriber.onNext(3);
                    subscriber.onNext(4);
                    subscriber.onNext(5);
                    subscriber.onCompleted();
                } else if (executionResult == ExecutionResult.MULTIPLE_EMITS_THEN_FAILURE) {
                    subscriber.onNext(6);
                    subscriber.onNext(7);
                    subscriber.onNext(8);
                    subscriber.onNext(9);
                    subscriber.onError(new RuntimeException("Execution Async Failure For TestHystrixObservableCommand after 4 emits"));
                } else if (executionResult == ExecutionResult.ASYNC_FAILURE) {
                    subscriber.onError(new RuntimeException("Execution Async Failure for TestHystrixObservableCommand after 0 emits"));
                } else if (executionResult == ExecutionResult.ASYNC_HYSTRIX_FAILURE) {
                    subscriber.onError(new HystrixRuntimeException(HystrixRuntimeException.FailureType.COMMAND_EXCEPTION, AbstractFlexibleTestHystrixObservableCommand.class, "Execution Hystrix Failure for TestHystrixObservableCommand", new RuntimeException("Execution Failure for TestHystrixObservableCommand")));
                } else if (executionResult == ExecutionResult.ASYNC_RECOVERABLE_ERROR) {
                    subscriber.onError(new Error("Execution Async Error for TestHystrixObservableCommand"));
                } else if (executionResult == ExecutionResult.ASYNC_UNRECOVERABLE_ERROR) {
                    subscriber.onError(new OutOfMemoryError("Execution Async OOME for TestHystrixObservableCommand"));
                } else if (executionResult == ExecutionResult.ASYNC_BAD_REQUEST) {
                    subscriber.onError(new HystrixBadRequestException("Execution Async Bad Request Exception for TestHystrixObservableCommand"));
                } else {
                    subscriber.onError(new RuntimeException("You passed in a executionResult enum that can't be represented in HystrixObservableCommand: " + executionResult));
                }
            });
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

    private static class FlexibleTestHystrixObservableCommand2 extends AbstractFlexibleTestHystrixObservableCommand {
        public FlexibleTestHystrixObservableCommand2(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, AbstractTestHystrixCommand.ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, TryableSemaphore executionSemaphore) {
            super(commandKey, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
        }
    }

    /**
     * Successful execution.
     */
    private static class SuccessfulTestCommand extends TestHystrixObservableCommand<Boolean> {

        public SuccessfulTestCommand(ExecutionIsolationStrategy isolationStrategy) {
            this(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationStrategy));
        }

        public SuccessfulTestCommand(HystrixCommandProperties.Setter properties) {
            super(testPropsBuilder().setCommandPropertiesDefaults(properties));
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.just(true).subscribeOn(Schedulers.computation());
        }

    }

    /**
     * Successful execution.
     */
    private static class TestCommandWithMultipleValues extends TestHystrixObservableCommand<Boolean> {

        public TestCommandWithMultipleValues() {
            this(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE));
        }

        public TestCommandWithMultipleValues(HystrixCommandProperties.Setter properties) {
            super(testPropsBuilder().setCommandPropertiesDefaults(properties));
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.just(true, false, true).subscribeOn(Schedulers.computation());
        }

    }

    private static class TestPartialSuccess extends TestHystrixObservableCommand<Integer> {

        TestPartialSuccess() {
            super(TestHystrixObservableCommand.testPropsBuilder());
        }

        @Override
        protected Observable<Integer> construct() {
            return Observable.just(1, 2, 3)
                    .concatWith(Observable.error(new RuntimeException("forced error")))
                    .subscribeOn(Schedulers.computation());
        }

    }

    private static class TestPartialSuccess2 extends TestHystrixObservableCommand<Boolean> {

        TestPartialSuccess2() {
            super(TestHystrixObservableCommand.testPropsBuilder());
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.just(false, true, false)
                    .concatWith(Observable.error(new RuntimeException("forced error")))
                    .subscribeOn(Schedulers.computation());
        }

    }



    /**
     * Successful execution.
     */
    private static class DynamicOwnerTestCommand extends TestHystrixObservableCommand<Boolean> {

        public DynamicOwnerTestCommand(HystrixCommandGroupKey owner) {
            super(testPropsBuilder().setOwner(owner));
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("successfully executed");
            return Observable.just(true).subscribeOn(Schedulers.computation());
        }

    }

    /**
     * Successful execution.
     */
    private static class DynamicOwnerAndKeyTestCommand extends TestHystrixObservableCommand<Boolean> {

        public DynamicOwnerAndKeyTestCommand(HystrixCommandGroupKey owner, HystrixCommandKey key) {
            super(testPropsBuilder().setOwner(owner).setCommandKey(key));
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("successfully executed");
            return Observable.just(true).subscribeOn(Schedulers.computation());
        }

    }

    /**
     * Failed execution with unknown exception (not HystrixException).
     */
    private static class UnknownFailureTestCommand extends TestHystrixObservableCommand<Boolean> {

        private final boolean asyncException;

        private UnknownFailureTestCommand(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
            super(testPropsBuilder(isolationStrategy));
            this.asyncException = asyncException;
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("*** simulated failed execution ***");
            RuntimeException ex = new RuntimeException("we failed with an unknown issue");
            if (asyncException) {
                return Observable.error(ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Failed execution with known exception (HystrixException).
     */
    private static class KnownFailureTestCommand extends TestHystrixObservableCommand<Boolean> {

        final boolean asyncException;

        private KnownFailureTestCommand(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
            super(testPropsBuilder(isolationStrategy));
            this.asyncException = asyncException;
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("*** simulated failed execution ***");
            RuntimeException ex = new RuntimeException("we failed with a simulated issue");
            if (asyncException) {
                return Observable.error(ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Failed execution.
     */
    private static class KnownFailureTestCommand2 extends TestHystrixObservableCommand<Boolean> {

        private final boolean asyncException;

        public KnownFailureTestCommand2(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
            super(testPropsBuilder(isolationStrategy));
            this.asyncException = asyncException;
        }

        public KnownFailureTestCommand2(boolean asyncException) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
            this.asyncException = asyncException;
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("*** simulated failed execution ***");
            RuntimeException ex = new RuntimeException("we failed with a simulated issue");
            if (asyncException) {
                return Observable.error(ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Failed execution with {@link HystrixBadRequestException}
     */
    private static class KnownHystrixBadRequestFailureTestCommand extends TestHystrixObservableCommand<Boolean> {

        public final static boolean ASYNC_EXCEPTION = true;
        public final static boolean SYNC_EXCEPTION = false;

        private final boolean asyncException;

        public KnownHystrixBadRequestFailureTestCommand(ExecutionIsolationStrategy isolationStrategy, boolean asyncException) {
            super(testPropsBuilder(isolationStrategy));
            this.asyncException = asyncException;
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("*** simulated failed with HystrixBadRequestException  ***");
            RuntimeException ex = new HystrixBadRequestException("we failed with a simulated issue");
            if (asyncException) {
                return Observable.error(ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * Failed execution.
     */
    private static class KnownFailureTestCommand3 extends TestHystrixObservableCommand<Boolean> {

        private final boolean asyncConstructException;

        private KnownFailureTestCommand3(ExecutionIsolationStrategy isolationStrategy, boolean asyncConstructException) {
            super(testPropsBuilder(isolationStrategy));
            this.asyncConstructException = asyncConstructException;
        }

        @Override
        protected Observable<Boolean> construct() {
            RuntimeException ex = new RuntimeException("we failed with a simulated issue");
            System.out.println("*** simulated failed execution ***");
            if (asyncConstructException) {
                return Observable.error(ex);
            } else {
                throw ex;
            }
        }
    }

    /**
     * A Command implementation that supports caching.
     */
    private static class SuccessfulCacheableCommand<T> extends TestHystrixObservableCommand<T> {

        private final boolean cacheEnabled;
        private volatile boolean executed = false;
        private final T value;

        public SuccessfulCacheableCommand(boolean cacheEnabled, T value) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.THREAD)));
            this.value = value;
            this.cacheEnabled = cacheEnabled;
        }

        @Override
        protected Observable<T> construct() {
            executed = true;
            System.out.println("successfully executed");
            return Observable.just(value).subscribeOn(Schedulers.computation());
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
     * A Command implementation that supports caching and execution takes a while.
     * <p>
     * Used to test scenario where Futures are returned with a backing call still executing.
     */
    private static class SlowCacheableCommand extends TestHystrixObservableCommand<String> {

        private final String value;
        private final int duration;
        private volatile boolean executed = false;

        public SlowCacheableCommand(String value, int duration) {
            super(testPropsBuilder()
                    .setCommandKey(HystrixCommandKey.Factory.asKey("ObservableSlowCacheable")));
            this.value = value;
            this.duration = duration;
        }

        @Override
        protected Observable<String> construct() {
            executed = true;
            return Observable.just(value).delay(duration, TimeUnit.MILLISECONDS).subscribeOn(Schedulers.computation())
                    .doOnNext(t1 -> System.out.println("successfully executed"));
        }

        @Override
        public String getCacheKey() {
            return value;
        }
    }

    /**
     * Successful execution.
     */
    private static class TestCommand extends TestHystrixObservableCommand<Boolean> {

        private TestCommand() {
            super(testPropsBuilder().setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
        }

        @Override
        protected Observable<Boolean> construct() {
            System.out.println("successfully executed");
            return Observable.just(true).subscribeOn(Schedulers.computation());
        }

    }

    /**
     * The run() will take time.
     */
    private static class TestSemaphoreCommand extends TestHystrixObservableCommand<Boolean> {

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
        protected Observable<Boolean> construct() {
            return Observable.create(subscriber -> {
                try {
                    Thread.sleep(executionSleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (resultBehavior == RESULT_SUCCESS) {
                    subscriber.onNext(true);
                    subscriber.onCompleted();
                } else if (resultBehavior == RESULT_FAILURE) {
                    subscriber.onError(new RuntimeException("TestSemaphoreCommand failure"));
                } else if (resultBehavior == RESULT_BAD_REQUEST_EXCEPTION) {
                    subscriber.onError(new HystrixBadRequestException("TestSemaphoreCommand BadRequestException"));
                } else {
                    subscriber.onError(new IllegalStateException("Didn't use a proper enum for result behavior"));
                }
            });
        }
    }

    /**
     * The construct() will take time once subscribed to.
     *
     * Used for making sure Thread and Semaphore isolation are separated from each other.
     */
    private static class TestThreadIsolationWithSemaphoreSetSmallCommand extends TestHystrixObservableCommand<Boolean> {

        private final Action0 action;

        private TestThreadIsolationWithSemaphoreSetSmallCommand(int poolSize, Action0 action) {
            super(testPropsBuilder()
                    .setThreadPoolKey(HystrixThreadPoolKey.Factory.asKey(TestThreadIsolationWithSemaphoreSetSmallCommand.class.getSimpleName()))
                    .setThreadPoolPropertiesDefaults(HystrixThreadPoolPropertiesTest.getUnitTestPropertiesBuilder()
                            .withCoreSize(poolSize).withMaximumSize(poolSize).withMaxQueueSize(0))
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(ExecutionIsolationStrategy.THREAD)
                            .withExecutionIsolationSemaphoreMaxConcurrentRequests(1)));
            this.action = action;
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.create(s -> {
                action.call();
                s.onNext(true);
                s.onCompleted();
            });
        }
    }

    /**
     * Semaphore based command that allows caller to use latches to know when it has started and signal when it
     * would like the command to finish
     */
    private static class LatchedSemaphoreCommand extends TestHystrixObservableCommand<Boolean> {

        private final CountDownLatch startLatch, waitLatch;

        /**
         *
         * @param semaphore semaphore (passed in so it may be shared)
         * @param startLatch
         * this command calls {@link CountDownLatch#countDown()} immediately upon running
         * @param waitLatch
         *            this command calls {@link CountDownLatch#await()} once it starts
         *            to run. The caller can use the latch to signal the command to finish
         */
        private LatchedSemaphoreCommand(TryableSemaphoreActual semaphore, CountDownLatch startLatch, CountDownLatch waitLatch) {
            this("Latched", semaphore, startLatch, waitLatch);
        }

        private LatchedSemaphoreCommand(String commandName, TryableSemaphoreActual semaphore, CountDownLatch startLatch, CountDownLatch waitLatch) {
            super(testPropsBuilder()
                    .setCommandKey(HystrixCommandKey.Factory.asKey(commandName))
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter()
                            .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                    .setExecutionSemaphore(semaphore));
            this.startLatch = startLatch;
            this.waitLatch = waitLatch;
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.create((OnSubscribe<Boolean>) s -> {
                //signals caller that run has started
                startLatch.countDown();
                try {
                    // waits for caller to countDown latch
                    waitLatch.await();
                    s.onNext(true);
                    s.onCompleted();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    s.onNext(false);
                    s.onCompleted();
                }
            }).subscribeOn(Schedulers.computation());
        }
    }

    /**
     * The construct() will take time once subscribed to.
     */
    private static class TestSemaphoreCommand2 extends TestHystrixObservableCommand<Boolean> {

        private final long executionSleep;

        private TestSemaphoreCommand2(int executionSemaphoreCount, long executionSleep) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE).withExecutionIsolationSemaphoreMaxConcurrentRequests(executionSemaphoreCount)));
            this.executionSleep = executionSleep;
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.create((OnSubscribe<Boolean>) s -> {
                try {
                    Thread.sleep(executionSleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                s.onNext(true);
                s.onCompleted();
            }).subscribeOn(Schedulers.io());
        }

    }

    private static class RequestCacheThreadRejection extends TestHystrixObservableCommand<Boolean> {

        final CountDownLatch completionLatch;

        public RequestCacheThreadRejection(CountDownLatch completionLatch) {
            super(testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.THREAD))
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
        protected Observable<Boolean> construct() {
            try {
                if (completionLatch.await(1000, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException("timed out waiting on completionLatch");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return Observable.just(true);
        }

        @Override
        public String getCacheKey() {
            return "A";
        }
    }

    private static class CommandWithErrorThrown extends TestHystrixObservableCommand<Boolean> {

        private final boolean asyncException;

        public CommandWithErrorThrown(boolean asyncException) {
            super(testPropsBuilder());
            this.asyncException = asyncException;
        }

        @Override
        protected Observable<Boolean> construct() {
            Error error = new Error("simulated java.lang.Error message");
            if (asyncException) {
                return Observable.error(error);
            } else {
                throw error;
            }
        }
    }

    private static class CommandWithCheckedException extends TestHystrixObservableCommand<Boolean> {

        public CommandWithCheckedException() {
            super(testPropsBuilder());
        }

        @Override
        protected Observable<Boolean> construct() {
            return Observable.error(new IOException("simulated checked exception message"));
        }

    }
}
