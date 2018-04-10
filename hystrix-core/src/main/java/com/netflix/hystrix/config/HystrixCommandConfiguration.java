/**
 * Copyright 2016 Netflix, Inc.
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
package com.netflix.hystrix.config;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolKey;

public class HystrixCommandConfiguration {
    private final HystrixThreadPoolKey threadPoolKey;
    private final HystrixCommandGroupKey groupKey;
    private final HystrixCommandExecutionConfig executionConfig;

    public HystrixCommandConfiguration(HystrixThreadPoolKey threadPoolKey, HystrixCommandGroupKey groupKey,
                                       HystrixCommandExecutionConfig executionConfig) {
        this.threadPoolKey = threadPoolKey;
        this.groupKey = groupKey;
        this.executionConfig = executionConfig;
    }

    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    public HystrixCommandGroupKey getGroupKey() {
        return groupKey;
    }

    public HystrixCommandExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    public static class HystrixCommandExecutionConfig {
        private final int semaphoreMaxConcurrentRequests;
        private final HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy;
        private final String threadPoolKeyOverride;
        private final boolean requestCacheEnabled;

        public HystrixCommandExecutionConfig(int semaphoreMaxConcurrentRequests, HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy,
                                             String threadPoolKeyOverride, boolean requestCacheEnabled) {
            this.semaphoreMaxConcurrentRequests = semaphoreMaxConcurrentRequests;
            this.isolationStrategy = isolationStrategy;
            this.threadPoolKeyOverride = threadPoolKeyOverride;
            this.requestCacheEnabled = requestCacheEnabled;

        }

        public int getSemaphoreMaxConcurrentRequests() {
            return semaphoreMaxConcurrentRequests;
        }

        public HystrixCommandProperties.ExecutionIsolationStrategy getIsolationStrategy() {
            return isolationStrategy;
        }

        public String getThreadPoolKeyOverride() {
            return threadPoolKeyOverride;
        }

        public boolean isRequestCacheEnabled() {
            return requestCacheEnabled;
        }
    }
}
