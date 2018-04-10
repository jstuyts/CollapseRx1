/**
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

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.HystrixCommandProperties.Setter;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HystrixCommandPropertiesTest {

    /**
     * Utility method for creating baseline properties for unit tests.
     */
    /* package */static HystrixCommandProperties.Setter getUnitTestPropertiesSetter() {
        return new HystrixCommandProperties.Setter()
                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.THREAD) // we want thread execution by default in tests
                .withExecutionIsolationThreadInterruptOnFutureCancel(true)
                .withExecutionIsolationSemaphoreMaxConcurrentRequests(20)
                .withRequestCacheEnabled(true);
    }

    /**
     * Return a static representation of the properties with values from the Builder so that UnitTests can create properties that are not affected by the actual implementations which pick up their
     * values dynamically.
     * 
     * @param builder command properties builder
     * @return HystrixCommandProperties
     */
    /* package */static HystrixCommandProperties asMock(final Setter builder) {
        return new HystrixCommandProperties(TestKey.TEST) {

            @Override
            public HystrixProperty<Integer> executionIsolationSemaphoreMaxConcurrentRequests() {
                return HystrixProperty.Factory.asProperty(builder.getExecutionIsolationSemaphoreMaxConcurrentRequests());
            }

            @Override
            public HystrixProperty<ExecutionIsolationStrategy> executionIsolationStrategy() {
                return HystrixProperty.Factory.asProperty(builder.getExecutionIsolationStrategy());
            }

            @Override
            public HystrixProperty<Boolean> executionIsolationThreadInterruptOnFutureCancel() {
                return HystrixProperty.Factory.asProperty(builder.getExecutionIsolationThreadInterruptOnFutureCancel());
            }

            @Override
            public HystrixProperty<String> executionIsolationThreadPoolKeyOverride() {
                return HystrixProperty.Factory.nullProperty();
            }

            @Override
            public HystrixProperty<Boolean> requestCacheEnabled() {
                return HystrixProperty.Factory.asProperty(builder.getRequestCacheEnabled());
            }

        };
    }

    // NOTE: We use "unitTestPrefix" as a prefix so we can't end up pulling in external properties that change unit test behavior

    public enum TestKey implements HystrixCommandKey {
        TEST
    }

    private static class TestPropertiesCommand extends HystrixCommandProperties {

        protected TestPropertiesCommand(HystrixCommandKey key, Setter builder, String propertyPrefix) {
            super(key, builder, propertyPrefix);
        }

    }

    @After
    public void cleanup() {
        ConfigurationManager.getConfigInstance().clear();
    }

    @Test
    public void testThreadPoolOnlyHasInstanceOverride() throws Exception {
        HystrixCommandProperties properties = new TestPropertiesCommand(TestKey.TEST, new HystrixCommandProperties.Setter(), "unitTestPrefix");
        ConfigurationManager.getConfigInstance().setProperty("unitTestPrefix.command.default.threadPoolKeyOverride", 1234);
        // it should be null
        assertEquals(null, properties.executionIsolationThreadPoolKeyOverride().get());
        ConfigurationManager.getConfigInstance().setProperty("unitTestPrefix.command.TEST.threadPoolKeyOverride", "testPool");
        // now it should have a value
        assertEquals("testPool", properties.executionIsolationThreadPoolKeyOverride().get());

        // cleanup 
        ConfigurationManager.getConfigInstance().clearProperty("unitTestPrefix.command.default.threadPoolKeyOverride");
        ConfigurationManager.getConfigInstance().clearProperty("unitTestPrefix.command.TEST.threadPoolKeyOverride");
    }

}
