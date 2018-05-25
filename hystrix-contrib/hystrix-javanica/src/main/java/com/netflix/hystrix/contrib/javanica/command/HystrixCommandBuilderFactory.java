/*
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.hystrix.contrib.javanica.command;

import com.netflix.hystrix.HystrixCollapser;
import org.apache.commons.lang3.Validate;

import java.util.Collection;
import java.util.Collections;

import static com.netflix.hystrix.contrib.javanica.cache.CacheInvocationContextFactory.createCacheRemoveInvocationContext;
import static com.netflix.hystrix.contrib.javanica.cache.CacheInvocationContextFactory.createCacheResultInvocationContext;

/**
 * Created by dmgcodevil.
 */
public class HystrixCommandBuilderFactory {

    // todo Add Cache

    private static final HystrixCommandBuilderFactory INSTANCE = new HystrixCommandBuilderFactory();

    public static HystrixCommandBuilderFactory getInstance() {
        return INSTANCE;
    }

    private HystrixCommandBuilderFactory() {

    }

    public HystrixCommandBuilder create(MetaHolder metaHolder) {
        return create(metaHolder, Collections.emptyList());
    }

    public <ResponseType> HystrixCommandBuilder create(MetaHolder metaHolder, Collection<HystrixCollapser.CollapsedRequest<ResponseType, Object>> collapsedRequests) {
        validateMetaHolder(metaHolder);

        return HystrixCommandBuilder.builder()
                .setterBuilder(createGenericSetterBuilder(metaHolder))
                .commandActions(createCommandActions(metaHolder))
                .collapsedRequests(collapsedRequests)
                .cacheResultInvocationContext(createCacheResultInvocationContext(metaHolder))
                .cacheRemoveInvocationContext(createCacheRemoveInvocationContext(metaHolder))
                .ignoreExceptions(metaHolder.getCommandIgnoreExceptions())
                .executionType(metaHolder.getExecutionType())
                .build();
    }

    private void validateMetaHolder(MetaHolder metaHolder) {
        Validate.notNull(metaHolder, "metaHolder is required parameter and cannot be null");
        Validate.isTrue(metaHolder.isCommandAnnotationPresent(), "hystrixCommand annotation is absent");
    }

    private GenericSetterBuilder createGenericSetterBuilder(MetaHolder metaHolder) {
        GenericSetterBuilder.Builder setterBuilder = GenericSetterBuilder.builder()
                .groupKey(metaHolder.getCommandGroupKey())
                .threadPoolKey(metaHolder.getThreadPoolKey())
                .commandKey(metaHolder.getCommandKey())
                .collapserKey(metaHolder.getCollapserKey())
                .commandProperties(metaHolder.getCommandProperties())
                .threadPoolProperties(metaHolder.getThreadPoolProperties())
                .collapserProperties(metaHolder.getCollapserProperties());
        if (metaHolder.isCollapserAnnotationPresent()) {
            setterBuilder.scope(metaHolder.getHystrixCollapser().scope());
        }
        return setterBuilder.build();
    }

    private CommandActions createCommandActions(MetaHolder metaHolder) {
        CommandAction commandAction = createCommandAction(metaHolder);
        return CommandActions.builder().commandAction(commandAction)
                .build();
    }

    private CommandAction createCommandAction(MetaHolder metaHolder) {
        return new MethodExecutionAction(metaHolder.getObj(), metaHolder.getMethod(), metaHolder.getArgs(), metaHolder);
    }

}
