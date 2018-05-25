/*
 * Copyright 2014 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.perf;

import com.netflix.hystrix.*;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
  * Note that the hystrixExecute test must be run on a forked JVM.  Otherwise, the initial properties that get
  * set for the command apply to all runs.  This would leave the command as THREAD-isolated in all test, for example.
  */
public class CommandExecutionPerfTest {

    private static HystrixCommandProperties.Setter threadIsolatedCommandDefaults = HystrixCommandProperties.Setter()
            .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.THREAD)
            .withRequestCacheEnabled(true);

    private static HystrixCommandProperties.Setter threadIsolatedFailFastCommandDefaults = HystrixCommandProperties.Setter()
            .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.THREAD)
            .withRequestCacheEnabled(true);

    private static HystrixCommandProperties.Setter semaphoreIsolatedCommandDefaults = HystrixCommandProperties.Setter()
            .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
            .withRequestCacheEnabled(true);

    private static HystrixCommandProperties.Setter semaphoreIsolatedFailFastCommandDefaults = HystrixCommandProperties.Setter()
            .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
            .withRequestCacheEnabled(true);

    private static HystrixThreadPoolProperties.Setter threadPoolDefaults = HystrixThreadPoolProperties.Setter()
            .withCoreSize(100);

    private static HystrixCommandGroupKey groupKey = HystrixCommandGroupKey.Factory.asKey("PERF");

    private static HystrixCommandProperties.Setter getCommandSetter(HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy, boolean forceOpen) {
        switch (isolationStrategy) {
            case THREAD:
                if (forceOpen) {
                    return threadIsolatedFailFastCommandDefaults;
                } else {
                    return threadIsolatedCommandDefaults;
                }
            default:
                if (forceOpen) {
                    return semaphoreIsolatedFailFastCommandDefaults;
                } else {
                    return semaphoreIsolatedCommandDefaults;
                }
        }
    }

    @State(Scope.Thread)
    public static class BlackholeState {
        //amount of "work" to give to CPU
        @Param({"1", "100", "10000"})
        public int blackholeConsumption;
    }

    @State(Scope.Thread)
    public static class CommandState {
        HystrixCommand<Integer> command;
        HystrixRequestContext requestContext;

        @Param({"true", "false"})
        public boolean forceOpen;

        @Param({"true", "false"})
        public boolean setUpRequestContext;

        @Param({"THREAD", "SEMAPHORE"})
        public HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy;

        //amount of "work" to give to CPU
        @Param({"1", "100", "10000"})
        public int blackholeConsumption;

        @Setup(Level.Invocation)
        public void setUp() {
            if (setUpRequestContext) {
                requestContext = HystrixRequestContext.initializeContext();
            }

            command = new HystrixCommand<Integer>(
                    HystrixCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("PERF"))
                            .andCommandPropertiesDefaults(getCommandSetter(isolationStrategy, forceOpen))
                            .andThreadPoolPropertiesDefaults(threadPoolDefaults)
            ) {
                @Override
                protected Integer run() {
                    Blackhole.consumeCPU(blackholeConsumption);
                    return 1;
                }
            };
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            if (setUpRequestContext) {
                requestContext.shutdown();
            }
        }
    }

    @State(Scope.Thread)
    public static class ObservableCommandState {
        HystrixObservableCommand<Integer> command;
        HystrixRequestContext requestContext;

        @Param({"true", "false"})
        public boolean forceOpen;

        @Param({"true", "false"})
        public boolean setUpRequestContext;

        //amount of "work" to give to CPU
        @Param({"1", "100", "10000"})
        public int blackholeConsumption;

        @Setup(Level.Invocation)
        public void setUp() {
            if (setUpRequestContext) {
                requestContext = HystrixRequestContext.initializeContext();
            }

            command = new HystrixObservableCommand<Integer>(
                    HystrixObservableCommand.Setter.withGroupKey(groupKey)
                    .andCommandPropertiesDefaults(getCommandSetter(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE, forceOpen))
            ) {
                @Override
                protected Observable<Integer> construct() {
                    return Observable.defer(() -> {
                        Blackhole.consumeCPU(blackholeConsumption);
                        return Observable.just(1);
                    }).subscribeOn(Schedulers.computation());
                }
            };
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            if (setUpRequestContext) {
                requestContext.shutdown();
            }
        }
    }

    @State(Scope.Benchmark)
    public static class ExecutorState {
        ExecutorService executorService;

        @Setup
        public void setUp() {
            executorService = Executors.newFixedThreadPool(100);
        }

        @TearDown
        public void tearDown() {
            executorService.shutdownNow();
        }
    }

    @State(Scope.Benchmark)
    public static class ThreadPoolState {
        HystrixThreadPool hystrixThreadPool;

        @Setup
        public void setUp() {
            hystrixThreadPool = new HystrixThreadPool.HystrixThreadPoolDefault(
                    HystrixThreadPoolKey.Factory.asKey("PERF")
                    , HystrixThreadPoolProperties.Setter().withCoreSize(100));
        }

        @TearDown
        public void tearDown() {
            hystrixThreadPool.getExecutor().shutdownNow();
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineExecute(BlackholeState bhState) {
        Blackhole.consumeCPU(bhState.blackholeConsumption);
        return 1;
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineQueue(ExecutorState state, final BlackholeState bhState) {
        try {
            return state.executorService.submit(() -> {
                Blackhole.consumeCPU(bhState.blackholeConsumption);
                return 1;
            }).get();
        } catch (Throwable t) {
            return 2;
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineSyncObserve(final BlackholeState bhState) {
        Observable<Integer> syncObservable = Observable.defer(() -> {
            Blackhole.consumeCPU(bhState.blackholeConsumption);
            return Observable.just(1);
        });

        try {
            return syncObservable.toBlocking().first();
        } catch (Throwable t) {
            return 2;
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineAsyncComputationObserve(final BlackholeState bhState) {
        Observable<Integer> asyncObservable = Observable.defer(() -> {
            Blackhole.consumeCPU(bhState.blackholeConsumption);
            return Observable.just(1);
        }).subscribeOn(Schedulers.computation());

        try {
            return asyncObservable.toBlocking().first();
        } catch (Throwable t) {
            return 2;
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer baselineAsyncCustomThreadPoolObserve(ThreadPoolState state, final BlackholeState bhState) {
        Observable<Integer> asyncObservable = Observable.defer(() -> {
            Blackhole.consumeCPU(bhState.blackholeConsumption);
            return Observable.just(1);
        }).subscribeOn(state.hystrixThreadPool.getScheduler());
        try {
            return asyncObservable.toBlocking().first();
        } catch (Throwable t) {
            return 2;
        }
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer hystrixExecute(CommandState state) {
        return state.command.execute();
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Integer hystrixObserve(ObservableCommandState state) {
        return state.command.observe().toBlocking().first();
    }
}
