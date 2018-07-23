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

import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.AbstractTestHystrixCommand.CacheEnabled;
import com.netflix.hystrix.AbstractTestHystrixCommand.ExecutionResult;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import org.junit.Test;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func0;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Place to share code and tests between {@link HystrixCommandTest} and {@link HystrixObservableCommandTest}.
 * @param <C>
 */
public abstract class CommonHystrixCommandTests<C extends AbstractTestHystrixCommand<Integer>> {

    /**
     * Run the command in multiple modes and check that the hook assertions hold in each and that the command succeeds
     * @param ctor {@link AbstractTestHystrixCommand} constructor
     * @param assertion sequence of assertions to check after the command has completed
     */
    abstract void assertHooksOnSuccess(Func0<C> ctor, Action1<C> assertion);

    /**
     * Run the command in multiple modes and check that the hook assertions hold in each and that the command fails
     * @param ctor {@link AbstractTestHystrixCommand} constructor
     * @param assertion sequence of assertions to check after the command has completed
     */
    abstract void assertHooksOnFailure(Func0<C> ctor, Action1<C> assertion);

    /**
     * Run the command in multiple modes and check that the hook assertions hold in each and that the command fails
     * @param ctor {@link AbstractTestHystrixCommand} constructor
     * @param assertion sequence of assertions to check after the command has completed
     */
    abstract void assertHooksOnFailure(Func0<C> ctor, Action1<C> assertion, boolean failFast);

    /**
     * Run the command in multiple modes and check that the hook assertions hold in each and that the command fails as soon as possible
     * @param ctor {@link AbstractTestHystrixCommand} constructor
     * @param assertion sequence of assertions to check after the command has completed
     */
    protected void assertHooksOnFailFast(Func0<C> ctor, Action1<C> assertion) {
        assertHooksOnFailure(ctor, assertion, true);
    }

    /**
     * Run the command via {@link com.netflix.hystrix.HystrixCommand#observe()}, immediately block and then assert
     * @param command command to run
     * @param assertion assertions to check
     * @param isSuccess should the command succeed?
     */
    protected void assertBlockingObserve(C command, Action1<C> assertion, boolean isSuccess) {
        System.out.println("Running command.observe(), immediately blocking and then running assertions...");
        if (isSuccess) {
            try {
                command.observe().toList().toBlocking().single();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            try {
                command.observe().toList().toBlocking().single();
                fail("Expected a command failure!");
            } catch (Exception ex) {
                System.out.println("Received expected ex : " + ex);
                ex.printStackTrace();
            }
        }

        assertion.call(command);
    }

    /**
     * Run the command via {@link com.netflix.hystrix.HystrixCommand#observe()}, let the {@link rx.Observable} terminal
     * states unblock a {@link java.util.concurrent.CountDownLatch} and then assert
     * @param command command to run
     * @param assertion assertions to check
     * @param isSuccess should the command succeed?
     */
    protected void assertNonBlockingObserve(C command, Action1<C> assertion, boolean isSuccess) {
        System.out.println("Running command.observe(), awaiting terminal state of Observable, then running assertions...");
        final CountDownLatch latch = new CountDownLatch(1);

        Observable<Integer> o = command.observe();

        o.subscribe(new Subscriber<Integer>() {
            @Override
            public void onCompleted() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                latch.countDown();
            }

            @Override
            public void onNext(Integer i) {
                //do nothing
            }
        });

        try {
            latch.await(3, TimeUnit.SECONDS);
            assertion.call(command);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (isSuccess) {
            try {
                o.toList().toBlocking().single();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } else {
            try {
                o.toList().toBlocking().single();
                fail("Expected a command failure!");
            } catch (Exception ex) {
                System.out.println("Received expected ex : " + ex);
                ex.printStackTrace();
            }
        }
    }

    protected void assertCommandExecutionEvents(HystrixInvokableInfo command, HystrixEventType... expectedEventTypes) {
        boolean emitExpected = false;
        int expectedEmitCount = 0;

        List<HystrixEventType> condensedEmitExpectedEventTypes = new ArrayList<>();

        for (HystrixEventType expectedEventType: expectedEventTypes) {
            if (expectedEventType.equals(HystrixEventType.EMIT)) {
                if (!emitExpected) {
                    //first EMIT encountered, add it to condensedEmitExpectedEventTypes
                    condensedEmitExpectedEventTypes.add(HystrixEventType.EMIT);
                }
                emitExpected = true;
                expectedEmitCount++;
            } else {
                condensedEmitExpectedEventTypes.add(expectedEventType);
            }
        }
        List<HystrixEventType> actualEventTypes = command.getExecutionEvents();
        assertEquals(expectedEmitCount, command.getNumberEmissions());
        assertEquals(condensedEmitExpectedEventTypes, actualEventTypes);
    }

    /**
     * Thread pool with 1 thread, queue of size 1
     */
    protected static class SingleThreadedPoolWithQueue implements HystrixThreadPool {

        final LinkedBlockingQueue<Runnable> queue;
        final ThreadPoolExecutor pool;
        private final int rejectionQueueSizeThreshold;

        public SingleThreadedPoolWithQueue(int queueSize) {
            this(queueSize, 100);
        }

        public SingleThreadedPoolWithQueue(int queueSize, int rejectionQueueSizeThreshold) {
            queue = new LinkedBlockingQueue<>(queueSize);
            pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, queue);
            this.rejectionQueueSizeThreshold = rejectionQueueSizeThreshold;
        }

        @Override
        public ThreadPoolExecutor getExecutor() {
            return pool;
        }

        @Override
        public Scheduler getScheduler() {
            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this);
        }

        @Override
        public Scheduler getScheduler(Func0<Boolean> shouldInterruptThread) {
            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this, shouldInterruptThread);
        }

        @Override
        public void markThreadExecution() {
            // not used for this test
        }

        @Override
        public void markThreadCompletion() {
            // not used for this test
        }

        @Override
        public void markThreadRejection() {
            // not used for this test
        }

        @Override
        public boolean isQueueSpaceAvailable() {
            return queue.size() < rejectionQueueSizeThreshold;
        }

    }

    /**
     * Thread pool with 1 thread, queue of size 1
     */
    protected static class SingleThreadedPoolWithNoQueue implements HystrixThreadPool {

        final SynchronousQueue<Runnable> queue;
        final ThreadPoolExecutor pool;

        public SingleThreadedPoolWithNoQueue() {
            queue = new SynchronousQueue<>();
            pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, queue);
        }

        @Override
        public ThreadPoolExecutor getExecutor() {
            return pool;
        }

        @Override
        public Scheduler getScheduler() {
            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this);
        }

        @Override
        public Scheduler getScheduler(Func0<Boolean> shouldInterruptThread) {
            return new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), this, shouldInterruptThread);
        }

        @Override
        public void markThreadExecution() {
            // not used for this test
        }

        @Override
        public void markThreadCompletion() {
            // not used for this test
        }

        @Override
        public void markThreadRejection() {
            // not used for this test
        }

        @Override
        public boolean isQueueSpaceAvailable() {
            return true; //let the thread pool reject
        }

    }



    /*
     ********************* SEMAPHORE-ISOLATED Execution Hook Tests ***********************************
     */

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reached? : NO
     * Execution Result: SUCCESS
     */
    @Test
    public void testExecutionHookSemaphoreSuccess() {
        assertHooksOnSuccess(
                () -> getCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(1, 0, 1));
                    assertTrue(hook.executionEventsMatch(1, 0, 1));
                    assertEquals("onStart - onExecutionStart - onExecutionEmit - onEmit - onExecutionSuccess - onSuccess - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reached? : NO
     * Execution Result: synchronous HystrixBadRequestException
     */
    @Test
    public void testExecutionHookSemaphoreBadRequestException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.BAD_REQUEST),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(HystrixBadRequestException.class, hook.getCommandException().getClass());
                    assertEquals(HystrixBadRequestException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onExecutionStart - onExecutionError - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reached? : NO
     * Execution Result: synchronous HystrixRuntimeException
     */
    @Test
    public void testExecutionHookSemaphoreException() {
        assertHooksOnFailure(
                () -> getCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.FAILURE),
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 1, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals(RuntimeException.class, hook.getExecutionException().getClass());
                    assertEquals("onStart - onExecutionStart - onExecutionError - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reached? : YES
     */
    @Test
    public void testExecutionHookSemaphoreRejected() {
        assertHooksOnFailFast(
                () -> {
                    AbstractCommand.TryableSemaphore semaphore = new AbstractCommand.TryableSemaphoreActual(HystrixProperty.Factory.asProperty(2));

                    final C cmd1 = getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);
                    final C cmd2 = getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);

                    //saturate the semaphore
                    new Thread(cmd1::observe).start();
                    new Thread(cmd2::observe).start();

                    try {
                        //give the saturating threads a chance to run before we run the command we want to get rejected
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }

                    return getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);
                },
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", hook.executionSequence.toString());
                });
    }

    /**
     * Thread/semaphore: SEMAPHORE
     * Semaphore Permit reached? : YES
     */
    @Test
    public void testExecutionHookSemaphoreRejected3() {
        assertHooksOnFailFast(
                () -> {
                    AbstractCommand.TryableSemaphore semaphore = new AbstractCommand.TryableSemaphoreActual(HystrixProperty.Factory.asProperty(2));

                    final C cmd1 = getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);
                    final C cmd2 = getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);

                    //saturate the semaphore
                    new Thread(cmd1::observe).start();
                    new Thread(cmd2::observe).start();

                    try {
                        //give the saturating threads a chance to run before we run the command we want to get rejected
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(ie);
                    }

                    return getLatentCommand(ExecutionIsolationStrategy.SEMAPHORE, ExecutionResult.SUCCESS, 500, semaphore);
                },
                command -> {
                    TestableExecutionHook hook = command.getBuilder().executionHook;
                    assertTrue(hook.commandEmissionsMatch(0, 1, 0));
                    assertTrue(hook.executionEventsMatch(0, 0, 0));
                    assertEquals(RuntimeException.class, hook.getCommandException().getClass());
                    assertEquals("onStart - onError - ", hook.executionSequence.toString());
                });
    }

    /*
     ********************* END SEMAPHORE-ISOLATED Execution Hook Tests ***********************************
     */

    /**
     * Abstract methods defining a way to instantiate each of the described commands.
     * {@link HystrixCommandTest} and {@link HystrixObservableCommandTest} should each provide concrete impls for
     * {@link HystrixCommand}s and {@link} HystrixObservableCommand}s, respectively.
     */
    C getCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult) {
        return getCommand(isolationStrategy, executionResult, 0);
    }

    C getCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency) {
        return getCommand(isolationStrategy, executionResult, executionLatency, null, CacheEnabled.NO, "foo", 10);
    }

    C getCommand(HystrixCommandKey key, ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, int executionSemaphoreCount) {
        AbstractCommand.TryableSemaphoreActual executionSemaphore = new AbstractCommand.TryableSemaphoreActual(HystrixProperty.Factory.asProperty(executionSemaphoreCount));

        return getCommand(key, isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    C getCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, int executionSemaphoreCount) {
        AbstractCommand.TryableSemaphoreActual executionSemaphore = new AbstractCommand.TryableSemaphoreActual(HystrixProperty.Factory.asProperty(executionSemaphoreCount));
        return getCommand(isolationStrategy, executionResult, executionLatency, threadPool, cacheEnabled, value, executionSemaphore);
    }

    abstract C getCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, AbstractCommand.TryableSemaphore executionSemaphore);

    abstract C getCommand(HystrixCommandKey commandKey, ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool, CacheEnabled cacheEnabled, Object value, AbstractCommand.TryableSemaphore executionSemaphore);

    C getLatentCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency) {
        return getCommand(isolationStrategy, executionResult, executionLatency, null, CacheEnabled.NO, "foo", 10);
    }

    C getLatentCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, HystrixThreadPool threadPool) {
        return getCommand(isolationStrategy, executionResult, executionLatency, threadPool, CacheEnabled.NO, "foo", 10);
    }

    C getLatentCommand(ExecutionIsolationStrategy isolationStrategy, ExecutionResult executionResult, int executionLatency, AbstractCommand.TryableSemaphore executionSemaphore) {
        return getCommand(isolationStrategy, executionResult, executionLatency, null, CacheEnabled.NO, "foo", executionSemaphore);
    }

    C getRecoverableErrorCommand(ExecutionIsolationStrategy isolationStrategy) {
        return getCommand(isolationStrategy, ExecutionResult.RECOVERABLE_ERROR, 0);
    }

    C getUnrecoverableErrorCommand(ExecutionIsolationStrategy isolationStrategy) {
        return getCommand(isolationStrategy, ExecutionResult.UNRECOVERABLE_ERROR, 0);
    }
}
