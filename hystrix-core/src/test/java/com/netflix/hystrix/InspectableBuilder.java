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

public interface InspectableBuilder {
    TestCommandBuilder getBuilder();

    enum CommandKeyForUnitTest implements HystrixCommandKey {
        KEY_ONE, KEY_TWO
    }

    enum CommandGroupForUnitTest implements HystrixCommandGroupKey {
        OWNER_ONE
    }

    class TestCommandBuilder {
        HystrixCommandGroupKey owner = CommandGroupForUnitTest.OWNER_ONE;
        HystrixCommandKey dependencyKey = null;
        HystrixThreadPoolKey threadPoolKey = null;
        HystrixThreadPool threadPool = null;
        HystrixCommandProperties.Setter commandPropertiesDefaults;
        HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults = HystrixThreadPoolPropertiesTest.getUnitTestPropertiesBuilder();
        AbstractCommand.TryableSemaphore executionSemaphore = null;
        TestableExecutionHook executionHook = new TestableExecutionHook();

        TestCommandBuilder(HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy) {
            this.commandPropertiesDefaults = HystrixCommandPropertiesTest.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationStrategy);
        }

        TestCommandBuilder setOwner(HystrixCommandGroupKey owner) {
            this.owner = owner;
            return this;
        }

        TestCommandBuilder setCommandKey(HystrixCommandKey dependencyKey) {
            this.dependencyKey = dependencyKey;
            return this;
        }

        TestCommandBuilder setThreadPoolKey(HystrixThreadPoolKey threadPoolKey) {
            this.threadPoolKey = threadPoolKey;
            return this;
        }

        TestCommandBuilder setThreadPool(HystrixThreadPool threadPool) {
            this.threadPool = threadPool;
            return this;
        }

        TestCommandBuilder setCommandPropertiesDefaults(HystrixCommandProperties.Setter commandPropertiesDefaults) {
            this.commandPropertiesDefaults = commandPropertiesDefaults;
            return this;
        }

        TestCommandBuilder setThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
            this.threadPoolPropertiesDefaults = threadPoolPropertiesDefaults;
            return this;
        }

        TestCommandBuilder setExecutionSemaphore(AbstractCommand.TryableSemaphore executionSemaphore) {
            this.executionSemaphore = executionSemaphore;
            return this;
        }
    }
}
