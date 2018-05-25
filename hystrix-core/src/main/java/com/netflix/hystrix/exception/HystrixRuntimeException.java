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
package com.netflix.hystrix.exception;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixInvokable;

/**
 * RuntimeException that is thrown when a {@link HystrixCommand} fails.
 */
@SuppressWarnings("rawtypes")
public class HystrixRuntimeException extends RuntimeException {

    private static final long serialVersionUID = 5219160375476046229L;

    private final Class<? extends HystrixInvokable> commandClass;
    private final FailureType failureCause;

    public enum FailureType {
        BAD_REQUEST_EXCEPTION, COMMAND_EXCEPTION, REJECTED_THREAD_EXECUTION, REJECTED_SEMAPHORE_EXECUTION
    }

    public HystrixRuntimeException(FailureType failureCause, Class<? extends HystrixInvokable> commandClass, String message, Exception cause) {
        super(message, cause);
        this.failureCause = failureCause;
        this.commandClass = commandClass;
    }

    public HystrixRuntimeException(FailureType failureCause, Class<? extends HystrixInvokable> commandClass, String message, Throwable cause) {
        super(message, cause);
        this.failureCause = failureCause;
        this.commandClass = commandClass;
    }

    /**
     * The type of failure that caused this exception to be thrown.
     * 
     * @return {@link FailureType}
     */
    public FailureType getFailureType() {
        return failureCause;
    }

    /**
     * The implementing class of the {@link HystrixCommand}.
     * 
     * @return {@code Class<? extends HystrixCommand> }
     */
    public Class<? extends HystrixInvokable> getImplementingClass() {
        return commandClass;
    }

}
