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

import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;

import static com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedProperty.forBoolean;
import static com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedProperty.forInteger;

/**
 * Properties for instances of {@link HystrixCollapser}.
 * <p>
 * Default implementation of methods uses Archaius (https://github.com/Netflix/archaius)
 */
public abstract class HystrixCollapserProperties {

    /* defaults */
    private static final Integer default_maxRequestsInBatch = Integer.MAX_VALUE;
    private static final Integer default_timerDelayInMilliseconds = 10;
    private static final Boolean default_requestCacheEnabled = true;

    private final HystrixProperty<Integer> maxRequestsInBatch;
    private final HystrixProperty<Integer> timerDelayInMilliseconds;
    private final HystrixProperty<Boolean> requestCacheEnabled;

    protected HystrixCollapserProperties(HystrixCollapserKey collapserKey) {
        this(collapserKey, new Setter(), "hystrix");
    }

    protected HystrixCollapserProperties(HystrixCollapserKey collapserKey, Setter builder) {
        this(collapserKey, builder, "hystrix");
    }

    protected HystrixCollapserProperties(HystrixCollapserKey key, Setter builder, String propertyPrefix) {
        this.maxRequestsInBatch = getProperty(propertyPrefix, key, "maxRequestsInBatch", builder.getMaxRequestsInBatch(), default_maxRequestsInBatch);
        this.timerDelayInMilliseconds = getProperty(propertyPrefix, key, "timerDelayInMilliseconds", builder.getTimerDelayInMilliseconds(), default_timerDelayInMilliseconds);
        this.requestCacheEnabled = getProperty(propertyPrefix, key, "requestCache.enabled", builder.getRequestCacheEnabled(), default_requestCacheEnabled);
    }

    private static HystrixProperty<Integer> getProperty(String propertyPrefix, HystrixCollapserKey key, String instanceProperty, Integer builderOverrideValue, Integer defaultValue) {
        return forInteger()
              .add(propertyPrefix + ".collapser." + key.name() + "." + instanceProperty, builderOverrideValue)
              .add(propertyPrefix + ".collapser.default." + instanceProperty, defaultValue)
              .build();
              

    }

    private static HystrixProperty<Boolean> getProperty(String propertyPrefix, HystrixCollapserKey key, String instanceProperty, Boolean builderOverrideValue, Boolean defaultValue) {
        return forBoolean()
                .add(propertyPrefix + ".collapser." + key.name() + "." + instanceProperty, builderOverrideValue)
                .add(propertyPrefix + ".collapser.default." + instanceProperty, defaultValue)
                .build();
    }

    /**
     * Whether request caching is enabled for {@link HystrixCollapser#execute} and {@link HystrixCollapser#queue} invocations.
     *
     * @return {@code HystrixProperty<Boolean>}
     */
    public HystrixProperty<Boolean> requestCacheEnabled() {
        return requestCacheEnabled;
    }

    /**
     * The maximum number of requests allowed in a batch before triggering a batch execution.
     * 
     * @return {@code HystrixProperty<Integer>}
     */
    public HystrixProperty<Integer> maxRequestsInBatch() {
        return maxRequestsInBatch;
    }

    /**
     * The number of milliseconds between batch executions (unless {@link #maxRequestsInBatch} is hit which will cause a batch to execute early.
     * 
     * @return {@code HystrixProperty<Integer>}
     */
    public HystrixProperty<Integer> timerDelayInMilliseconds() {
        return timerDelayInMilliseconds;
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
     * Fluent interface that allows chained setting of properties that can be passed into a {@link HystrixCollapser} constructor to inject instance specific property overrides.
     * <p>
     * See {@link HystrixPropertiesStrategy} for more information on order of precedence.
     * <p>
     * Example:
     * <p>
     * <pre> {@code
     * HystrixCollapserProperties.Setter()
     *           .setMaxRequestsInBatch(100)
     *           .setTimerDelayInMilliseconds(10);
     * } </pre>
     * 
     * @NotThreadSafe
     */
    public static class Setter {
        private Integer maxRequestsInBatch = null;
        private Integer timerDelayInMilliseconds = null;
        private Boolean requestCacheEnabled = null;

        private Setter() {
        }

        public Integer getMaxRequestsInBatch() {
            return maxRequestsInBatch;
        }

        public Integer getTimerDelayInMilliseconds() {
            return timerDelayInMilliseconds;
        }

        public Boolean getRequestCacheEnabled() {
            return requestCacheEnabled;
        }

        public Setter withMaxRequestsInBatch(int value) {
            this.maxRequestsInBatch = value;
            return this;
        }

        public Setter withTimerDelayInMilliseconds(int value) {
            this.timerDelayInMilliseconds = value;
            return this;
        }

        public Setter withRequestCacheEnabled(boolean value) {
            this.requestCacheEnabled = value;
            return this;
        }
    }
}
