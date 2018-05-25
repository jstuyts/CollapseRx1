/*
 * Copyright 2013 Netflix, Inc.
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
package com.netflix.hystrix.strategy.executionhook;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.HystrixInvokable;
import com.netflix.hystrix.HystrixObservableCommand;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.strategy.HystrixPlugins;

/**
 * Abstract ExecutionHook with invocations at different lifecycle points of {@link HystrixCommand}
 * and {@link HystrixObservableCommand} execution with default no-op implementations.
 * <p>
 * See {@link HystrixPlugins} or the Hystrix GitHub Wiki for information on configuring plugins: <a
 * href="https://github.com/Netflix/Hystrix/wiki/Plugins">https://github.com/Netflix/Hystrix/wiki/Plugins</a>.
 * <p>
 * <b>Note on thread-safety and performance</b>
 * <p>
 * A single implementation of this class will be used globally so methods on this class will be invoked concurrently from multiple threads so all functionality must be thread-safe.
 * <p>
 * Methods are also invoked synchronously and will add to execution time of the commands so all behavior should be fast. If anything time-consuming is to be done it should be spawned asynchronously
 * onto separate worker threads.
 * 
 * @since 1.2
 * */
public abstract class HystrixCommandExecutionHook {

    /**
     * Invoked before {@link HystrixInvokable} begins executing.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     *
     * @since 1.2
     */
    public void onStart(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked when {@link HystrixInvokable} emits a value.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     * @param value value emitted
     *
     * @since 1.4
     */
    public <T> T onEmit(HystrixInvokable commandInstance, T value) {
        return value; //by default, just pass through
    }

    /**
     * Invoked when {@link HystrixInvokable} fails with an Exception.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     * @param failureType {@link FailureType} enum representing which type of error
     * @param e exception object
     *
     * @since 1.2
     */
    public Exception onError(HystrixInvokable commandInstance, FailureType failureType, Exception e) {
        return e; //by default, just pass through
    }

    /**
     * Invoked when {@link HystrixInvokable} finishes a successful execution.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     *
     * @since 1.4
     */
    public void onSuccess(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked at start of thread execution when {@link HystrixCommand} is executed using {@link ExecutionIsolationStrategy#THREAD}.
     *
     * @param commandInstance The executing HystrixCommand instance.
     *
     * @since 1.2
     */
    public void onThreadStart(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked at completion of thread execution when {@link HystrixCommand} is executed using {@link ExecutionIsolationStrategy#THREAD}.
     * This will get invoked whenever the Hystrix thread is done executing, regardless of whether the thread finished
     * naturally, or was unsubscribed externally
     *
     * @param commandInstance The executing HystrixCommand instance.
     *
     * @since 1.2
     */
    public void onThreadComplete(HystrixInvokable commandInstance) {
        // do nothing by default
    }

    /**
     * Invoked when the user-defined execution method in {@link HystrixInvokable} starts.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     *
     * @since 1.4
     */
    public void onExecutionStart(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked when the user-defined execution method in {@link HystrixInvokable} emits a value.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     * @param value value emitted
     *
     * @since 1.4
     */
    public <T> T onExecutionEmit(HystrixInvokable commandInstance, T value) {
        return value; //by default, just pass through
    }

    /**
     * Invoked when the user-defined execution method in {@link HystrixInvokable} fails with an Exception.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     * @param e exception object
     *
     * @since 1.4
     */
    public Exception onExecutionError(HystrixInvokable commandInstance, Exception e) {
        return e; //by default, just pass through
    }

    /**
     * Invoked when the user-defined execution method in {@link HystrixInvokable} completes successfully.
     *
     * @param commandInstance The executing HystrixInvokable instance.
     *
     * @since 1.4
     */
    public void onExecutionSuccess(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked when the command response is found in the {@link com.netflix.hystrix.HystrixRequestCache}.
     *
     * @param commandInstance The executing HystrixCommand
     *
     * @since 1.4
     */
    public void onCacheHit(HystrixInvokable commandInstance) {
        //do nothing by default
    }

    /**
     * Invoked with the command is unsubscribed before a terminal state
     *
     * @param commandInstance The executing HystrixInvokable instance.
     *
     * @since 1.5.9
     */
    public void onUnsubscribe(HystrixInvokable commandInstance) {
        //do nothing by default
    }
}
