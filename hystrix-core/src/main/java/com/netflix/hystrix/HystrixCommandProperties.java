/*
 * Copyright 2012 Netflix, Inc.
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

import com.netflix.hystrix.strategy.properties.HystrixDynamicProperty;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;

import static com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedProperty.*;

/**
 * Properties for instances of {@link HystrixCommand}.
 * <p>
 * Default implementation of methods uses Archaius (https://github.com/Netflix/archaius)
 */
public abstract class HystrixCommandProperties {
    private static final Logger logger = LoggerFactory.getLogger(HystrixCommandProperties.class);

    /* defaults */
    private static final ExecutionIsolationStrategy default_executionIsolationStrategy = ExecutionIsolationStrategy.THREAD;
    private static final Boolean default_executionIsolationThreadInterruptOnFutureCancel = false;
    private static final Boolean default_requestCacheEnabled = true;
    private static final Integer default_executionIsolationSemaphoreMaxConcurrentRequests = 10;

    @SuppressWarnings("unused") private final HystrixCommandKey key;
    private final HystrixProperty<ExecutionIsolationStrategy> executionIsolationStrategy; // Whether a command should be executed in a separate thread or not.
    private final HystrixProperty<String> executionIsolationThreadPoolKeyOverride; // What thread-pool this command should run in (if running on a separate thread).
    private final HystrixProperty<Integer> executionIsolationSemaphoreMaxConcurrentRequests; // Number of permits for execution semaphore
    private final HystrixProperty<Boolean> executionIsolationThreadInterruptOnFutureCancel; // Whether canceling an underlying Future/Thread (when runInSeparateThread == true) should interrupt the execution thread
    private final HystrixProperty<Boolean> requestCacheEnabled; // Whether request caching is enabled.

    /**
     * Isolation strategy to use when executing a {@link HystrixCommand}.
     * <p>
     * <ul>
     * <li>THREAD: Execute the {@link HystrixCommand#run()} method on a separate thread and restrict concurrent executions using the thread-pool size.</li>
     * <li>SEMAPHORE: Execute the {@link HystrixCommand#run()} method on the calling thread and restrict concurrent executions using the semaphore permit count.</li>
     * </ul>
     */
    public enum ExecutionIsolationStrategy {
        THREAD, SEMAPHORE
    }

    protected HystrixCommandProperties(HystrixCommandKey key) {
        this(key, new Setter(), "hystrix");
    }

    protected HystrixCommandProperties(HystrixCommandKey key, HystrixCommandProperties.Setter builder) {
        this(key, builder, "hystrix");
    }

    // known that we're using deprecated HystrixPropertiesChainedServoProperty until ChainedDynamicProperty exists in Archaius
    protected HystrixCommandProperties(HystrixCommandKey key, HystrixCommandProperties.Setter builder, String propertyPrefix) {
        this.key = key;
        this.executionIsolationStrategy = getProperty(propertyPrefix, key, "execution.isolation.strategy", builder.getExecutionIsolationStrategy(), default_executionIsolationStrategy);
        this.executionIsolationThreadInterruptOnFutureCancel = getProperty(propertyPrefix, key, "execution.isolation.thread.interruptOnFutureCancel", builder.getExecutionIsolationThreadInterruptOnFutureCancel(), default_executionIsolationThreadInterruptOnFutureCancel);
        this.executionIsolationSemaphoreMaxConcurrentRequests = getProperty(propertyPrefix, key, "execution.isolation.semaphore.maxConcurrentRequests", builder.getExecutionIsolationSemaphoreMaxConcurrentRequests(), default_executionIsolationSemaphoreMaxConcurrentRequests);
        this.requestCacheEnabled = getProperty(propertyPrefix, key, "requestCache.enabled", builder.getRequestCacheEnabled(), default_requestCacheEnabled);

        // threadpool doesn't have a global override, only instance level makes sense
        this.executionIsolationThreadPoolKeyOverride = forString().add(propertyPrefix + ".command." + key.name() + ".threadPoolKeyOverride", null).build();
    }

    /**
     * Number of concurrent requests permitted to {@link HystrixCommand#run()}. Requests beyond the concurrent limit will be rejected.
     * <p>
     * Applicable only when {@link #executionIsolationStrategy()} == SEMAPHORE.
     * 
     * @return {@code HystrixProperty<Integer>}
     */
    public HystrixProperty<Integer> executionIsolationSemaphoreMaxConcurrentRequests() {
        return executionIsolationSemaphoreMaxConcurrentRequests;
    }

    /**
     * What isolation strategy {@link HystrixCommand#run()} will be executed with.
     * <p>
     * If {@link ExecutionIsolationStrategy#THREAD} then it will be executed on a separate thread and concurrent requests limited by the number of threads in the thread-pool.
     * <p>
     * If {@link ExecutionIsolationStrategy#SEMAPHORE} then it will be executed on the calling thread and concurrent requests limited by the semaphore count.
     * 
     * @return {@code HystrixProperty<Boolean>}
     */
    public HystrixProperty<ExecutionIsolationStrategy> executionIsolationStrategy() {
        return executionIsolationStrategy;
    }

    /**
     * Whether the execution thread should be interrupted if the execution observable is unsubscribed or the future is cancelled via {@link Future#cancel(boolean)} with <code>true</code> passed in).
     * <p>
     * Applicable only when {@link #executionIsolationStrategy()} == THREAD.
     * 
     * @return {@code HystrixProperty<Boolean>}
     */
    public HystrixProperty<Boolean> executionIsolationThreadInterruptOnFutureCancel() {
        return executionIsolationThreadInterruptOnFutureCancel;
    }

    /**
     * Allow a dynamic override of the {@link HystrixThreadPoolKey} that will dynamically change which {@link HystrixThreadPool} a {@link HystrixCommand} executes on.
     * <p>
     * Typically this should return NULL which will cause it to use the {@link HystrixThreadPoolKey} injected into a {@link HystrixCommand} or derived from the {@link HystrixCommandGroupKey}.
     * <p>
     * When set the injected or derived values will be ignored and a new {@link HystrixThreadPool} created (if necessary) and the {@link HystrixCommand} will begin using the newly defined pool.
     * 
     * @return {@code HystrixProperty<String>}
     */
    public HystrixProperty<String> executionIsolationThreadPoolKeyOverride() {
        return executionIsolationThreadPoolKeyOverride;
    }

    /**
     * Whether {@link HystrixCommand#getCacheKey()} should be used with {@link HystrixRequestCache} to provide de-duplication functionality via request-scoped caching.
     * 
     * @return {@code HystrixProperty<Boolean>}
     */
    public HystrixProperty<Boolean> requestCacheEnabled() {
        return requestCacheEnabled;
    }

    private static HystrixProperty<Boolean> getProperty(String propertyPrefix, HystrixCommandKey key, String instanceProperty, Boolean builderOverrideValue, Boolean defaultValue) {
        return forBoolean()
                .add(propertyPrefix + ".command." + key.name() + "." + instanceProperty, builderOverrideValue)
                .add(propertyPrefix + ".command.default." + instanceProperty, defaultValue)
                .build();
    }

    private static HystrixProperty<Integer> getProperty(String propertyPrefix, HystrixCommandKey key, String instanceProperty, Integer builderOverrideValue, Integer defaultValue) {
        return forInteger()
                .add(propertyPrefix + ".command." + key.name() + "." + instanceProperty, builderOverrideValue)
                .add(propertyPrefix + ".command.default." + instanceProperty, defaultValue)
                .build();
    }

    @SuppressWarnings("unused")
    private static HystrixProperty<String> getProperty(String propertyPrefix, HystrixCommandKey key, String instanceProperty, String builderOverrideValue, String defaultValue) {
        return forString()
                .add(propertyPrefix + ".command." + key.name() + "." + instanceProperty, builderOverrideValue)
                .add(propertyPrefix + ".command.default." + instanceProperty, defaultValue)
                .build();
    }

    private static HystrixProperty<ExecutionIsolationStrategy> getProperty(final String propertyPrefix, final HystrixCommandKey key, final String instanceProperty, final ExecutionIsolationStrategy builderOverrideValue, final ExecutionIsolationStrategy defaultValue) {
        return new ExecutionIsolationStrategyHystrixProperty(builderOverrideValue, key, propertyPrefix, defaultValue, instanceProperty);

    }

    /**
     * HystrixProperty that converts a String to ExecutionIsolationStrategy so we remain TypeSafe.
     */
    private static final class ExecutionIsolationStrategyHystrixProperty implements HystrixProperty<ExecutionIsolationStrategy> {
        private final HystrixDynamicProperty<String> property;
        private volatile ExecutionIsolationStrategy value;
        private final ExecutionIsolationStrategy defaultValue;

        private ExecutionIsolationStrategyHystrixProperty(ExecutionIsolationStrategy builderOverrideValue, HystrixCommandKey key, String propertyPrefix, ExecutionIsolationStrategy defaultValue, String instanceProperty) {
            this.defaultValue = defaultValue;
            String overrideValue = null;
            if (builderOverrideValue != null) {
                overrideValue = builderOverrideValue.name();
            }
            property = forString()
                    .add(propertyPrefix + ".command." + key.name() + "." + instanceProperty, overrideValue)
                    .add(propertyPrefix + ".command.default." + instanceProperty, defaultValue.name())
                    .build();

            // initialize the enum value from the property
            parseProperty();

            // use a callback to handle changes so we only handle the parse cost on updates rather than every fetch
            // when the property value changes we'll update the value
            property.addCallback(this::parseProperty);
        }

        @Override
        public ExecutionIsolationStrategy get() {
            return value;
        }

        private void parseProperty() {
            try {
                value = ExecutionIsolationStrategy.valueOf(property.get());
            } catch (Exception e) {
                logger.error("Unable to derive ExecutionIsolationStrategy from property value: " + property.get(), e);
                // use the default value
                value = defaultValue;
            }
        }
    }

    /**
     * Factory method to retrieve the default Setter.
     */
    public static Setter Setter() {
        return new Setter();
    }

    /**
     * Factory method to retrieve the default Setter.
     * Groovy has a bug (GROOVY-6286) which does not allow method names and inner classes to have the same name
     * This method fixes Issue #967 and allows Groovy consumers to choose this method and not trigger the bug
     */
    public static Setter defaultSetter() {
        return Setter();
    }

    /**
     * Fluent interface that allows chained setting of properties that can be passed into a {@link HystrixCommand} constructor to inject instance specific property overrides.
     * <p>
     * See {@link HystrixPropertiesStrategy} for more information on order of precedence.
     * <p>
     * Example:
     * <p>
     * <pre> {@code
     * HystrixCommandProperties.Setter()
     *           .withExecuteCommandOnSeparateThread(true);
     * } </pre>
     * 
     * @NotThreadSafe
     */
    public static class Setter {

        private Integer executionIsolationSemaphoreMaxConcurrentRequests = null;
        private ExecutionIsolationStrategy executionIsolationStrategy = null;
        private Boolean executionIsolationThreadInterruptOnFutureCancel = null;
        private Boolean requestCacheEnabled = null;

        /* package */ Setter() {
        }

        public Integer getExecutionIsolationSemaphoreMaxConcurrentRequests() {
            return executionIsolationSemaphoreMaxConcurrentRequests;
        }

        public ExecutionIsolationStrategy getExecutionIsolationStrategy() {
            return executionIsolationStrategy;
        }

        public Boolean getExecutionIsolationThreadInterruptOnFutureCancel() {
			return executionIsolationThreadInterruptOnFutureCancel;
		}

        public Boolean getRequestCacheEnabled() {
            return requestCacheEnabled;
        }

        public Setter withExecutionIsolationSemaphoreMaxConcurrentRequests(int value) {
            this.executionIsolationSemaphoreMaxConcurrentRequests = value;
            return this;
        }

        public Setter withExecutionIsolationStrategy(ExecutionIsolationStrategy value) {
            this.executionIsolationStrategy = value;
            return this;
        }

        public Setter withExecutionIsolationThreadInterruptOnFutureCancel(boolean value) {
            this.executionIsolationThreadInterruptOnFutureCancel = value;
            return this;
        }

        public Setter withRequestCacheEnabled(boolean value) {
            this.requestCacheEnabled = value;
            return this;
        }
    }
}
