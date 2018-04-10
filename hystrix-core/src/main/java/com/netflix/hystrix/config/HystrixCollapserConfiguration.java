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

import com.netflix.hystrix.HystrixCollapserKey;

public class HystrixCollapserConfiguration {
    private final HystrixCollapserKey collapserKey;
    private final int maxRequestsInBatch;
    private final int timerDelayInMilliseconds;
    private final boolean requestCacheEnabled;

    public HystrixCollapserConfiguration(HystrixCollapserKey collapserKey, int maxRequestsInBatch, int timerDelayInMilliseconds,
                                         boolean requestCacheEnabled) {
        this.collapserKey = collapserKey;
        this.maxRequestsInBatch = maxRequestsInBatch;
        this.timerDelayInMilliseconds = timerDelayInMilliseconds;
        this.requestCacheEnabled = requestCacheEnabled;
    }

    public HystrixCollapserKey getCollapserKey() {
        return collapserKey;
    }

    public int getMaxRequestsInBatch() {
        return maxRequestsInBatch;
    }

    public int getTimerDelayInMilliseconds() {
        return timerDelayInMilliseconds;
    }

    public boolean isRequestCacheEnabled() {
        return requestCacheEnabled;
    }
}
