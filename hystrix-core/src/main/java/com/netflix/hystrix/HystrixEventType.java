/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import java.util.ArrayList;
import java.util.List;

/**
 * Various states/events that execution can result in or have tracked.
 * <p>
 * These are most often accessed via {@link HystrixCommand#getExecutionEvents()}.
 */
public enum HystrixEventType {
    EMIT(false),
    SUCCESS(true),
    FAILURE(false),
    BAD_REQUEST(true),
    THREAD_POOL_REJECTED(false),
    SEMAPHORE_REJECTED(false),
    EXCEPTION_THROWN(false),
    RESPONSE_FROM_CACHE(true),
    CANCELLED(true),
    COLLAPSED(false),
    COMMAND_MAX_ACTIVE(false);

    private final boolean isTerminal;

    HystrixEventType(boolean isTerminal) {
        this.isTerminal = isTerminal;
    }

    public boolean isTerminal() {
        return isTerminal;
    }

    /**
     * List of events that throw an Exception to the caller
     */
    public final static List<HystrixEventType> EXCEPTION_PRODUCING_EVENT_TYPES = new ArrayList<>();

    /**
     * List of events that are terminal
     */
    public final static List<HystrixEventType> TERMINAL_EVENT_TYPES = new ArrayList<>();

    static {
        EXCEPTION_PRODUCING_EVENT_TYPES.add(BAD_REQUEST);

        for (HystrixEventType eventType: HystrixEventType.values()) {
            if (eventType.isTerminal()) {
                TERMINAL_EVENT_TYPES.add(eventType);
            }
        }
    }

    public enum ThreadPool {
        EXECUTED, REJECTED;

        public static ThreadPool from(HystrixEventType eventType) {
            switch (eventType) {
                case SUCCESS: return EXECUTED;
                case FAILURE: return EXECUTED;
                case BAD_REQUEST: return EXECUTED;
                case THREAD_POOL_REJECTED: return REJECTED;
                default: return null;
            }
        }
    }

    public enum Collapser {
        BATCH_EXECUTED, ADDED_TO_BATCH, RESPONSE_FROM_CACHE
    }
}
