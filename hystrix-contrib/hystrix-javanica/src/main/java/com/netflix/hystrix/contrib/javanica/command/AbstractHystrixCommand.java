/**
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
package com.netflix.hystrix.contrib.javanica.command;


import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.contrib.javanica.cache.CacheInvocationContext;
import com.netflix.hystrix.contrib.javanica.cache.HystrixCacheKeyGenerator;
import com.netflix.hystrix.contrib.javanica.cache.HystrixGeneratedCacheKey;
import com.netflix.hystrix.contrib.javanica.cache.HystrixRequestCacheManager;
import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheRemove;
import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheResult;
import com.netflix.hystrix.contrib.javanica.exception.CommandActionExecutionException;
import com.netflix.hystrix.exception.HystrixBadRequestException;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.List;

/**
 * Base class for hystrix commands.
 *
 * @param <T> the return type
 */
@ThreadSafe
public abstract class AbstractHystrixCommand<T> extends com.netflix.hystrix.HystrixCommand<T> {

    private final CommandActions commandActions;
    private final CacheInvocationContext<CacheResult> cacheResultInvocationContext;
    private final CacheInvocationContext<CacheRemove> cacheRemoveInvocationContext;
    private final Collection<HystrixCollapser.CollapsedRequest<Object, Object>> collapsedRequests;
    private final List<Class<? extends Throwable>> ignoreExceptions;
    private final ExecutionType executionType;
    private final HystrixCacheKeyGenerator defaultCacheKeyGenerator = HystrixCacheKeyGenerator.getInstance();

    protected AbstractHystrixCommand(HystrixCommandBuilder builder) {
        super(builder.getSetterBuilder().build());
        this.commandActions = builder.getCommandActions();
        this.collapsedRequests = builder.getCollapsedRequests();
        this.cacheResultInvocationContext = builder.getCacheResultInvocationContext();
        this.cacheRemoveInvocationContext = builder.getCacheRemoveInvocationContext();
        this.ignoreExceptions = builder.getIgnoreExceptions();
        this.executionType = builder.getExecutionType();
    }

    /**
     * Gets command action.
     *
     * @return command action
     */
    protected CommandAction getCommandAction() {
        return commandActions.getCommandAction();
    }

    /**
     * Gets collapsed requests.
     *
     * @return collapsed requests
     */
    protected Collection<HystrixCollapser.CollapsedRequest<Object, Object>> getCollapsedRequests() {
        return collapsedRequests;
    }

    protected ExecutionType getExecutionType() {
        return executionType;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    protected String getCacheKey() {
        String key = null;
        if (cacheResultInvocationContext != null) {
            HystrixGeneratedCacheKey hystrixGeneratedCacheKey =
                    defaultCacheKeyGenerator.generateCacheKey(cacheResultInvocationContext);
            key = hystrixGeneratedCacheKey.getCacheKey();
        }
        return key;
    }

    /**
     * Check whether triggered exception is ignorable.
     *
     * @param throwable the exception occurred during a command execution
     * @return true if exception is ignorable, otherwise - false
     */
    boolean isIgnorable(Throwable throwable) {
        if (ignoreExceptions == null || ignoreExceptions.isEmpty()) {
            return false;
        }
        for (Class<? extends Throwable> ignoreException : ignoreExceptions) {
            if (ignoreException.isAssignableFrom(throwable.getClass())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes an action. If an action has failed and an exception is ignorable then propagate it as HystrixBadRequestException
     * otherwise propagate original exception.
     * Note: If an exception occurred in a command directly extends {@link java.lang.Throwable} then this exception cannot be re-thrown
     * as original exception because HystrixCommand.run() allows throw subclasses of {@link java.lang.Exception}.
     * Thus we need to wrap cause in RuntimeException.
     *
     * @param action the action
     * @return result of command action execution
     */
    Object process(Action action) throws Exception {
        Object result;
        try {
            result = action.execute();
            flushCache();
        } catch (CommandActionExecutionException throwable) {
            Throwable cause = throwable.getCause();
            if (isIgnorable(cause)) {
                throw new HystrixBadRequestException(cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                // instance of Throwable
                throw new CommandActionExecutionException(cause);
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    protected abstract T run() throws Exception;

    /**
     * Clears cache for the specified hystrix command.
     */
    protected void flushCache() {
        if (cacheRemoveInvocationContext != null) {
            HystrixRequestCacheManager.getInstance().clearCache(cacheRemoveInvocationContext);
        }
    }

    /**
     * Common action.
     */
    abstract class Action {
        /**
         * Each implementation of this method should wrap any exceptions in CommandActionExecutionException.
         *
         * @return execution result
         * @throws CommandActionExecutionException
         */
        abstract Object execute() throws CommandActionExecutionException;
    }

}
