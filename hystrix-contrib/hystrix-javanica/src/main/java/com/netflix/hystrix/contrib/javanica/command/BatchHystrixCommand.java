/*
 * Copyright 2012 Netflix, Inc.
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

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This command is used in collapser.
 */
@ThreadSafe
public class BatchHystrixCommand extends AbstractHystrixCommand<List<Object>> {

    public BatchHystrixCommand(HystrixCommandBuilder builder) {
        super(builder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    protected List<Object> run() throws Exception {
        Object[] args = toArgs(getCollapsedRequests());
        return (List) process(args);
    }

    private Object process(final Object[] args) throws Exception {
        return process(new Action() {
            @Override
            Object execute() {
                return getCommandAction().executeWithArgs(getExecutionType(), args);
            }
        });
    }

    private Object[] toArgs(Collection<HystrixCollapser.CollapsedRequest<Object, Object>> requests) {
        return new Object[]{collect(requests)};
    }

    private List<Object> collect(Collection<HystrixCollapser.CollapsedRequest<Object, Object>> requests) {
        List<Object> commandArgs = new ArrayList<>();
        for (HystrixCollapser.CollapsedRequest<Object, Object> request : requests) {
            final Object[] args = (Object[]) request.getArgument();
            commandArgs.add(args[0]);
        }
        return commandArgs;
    }

}
