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
package com.netflix.hystrix.strategy.concurrency;

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import rx.functions.Action1;
import rx.functions.Func1;

import static org.junit.Assert.assertTrue;

public class HystrixConcurrencyStrategyTest {
    @Before
    public void prepareForTest() {
        /* we must call this to simulate a new request lifecycle running and clearing caches */
        HystrixRequestContext.initializeContext();
    }

    @After
    public void cleanup() {
        shutdownContextIfExists();

        // force properties to be clean as well
        ConfigurationManager.getConfigInstance().clear();
    }

    /**
     * If the RequestContext does not get transferred across threads correctly this blows up.
     * No specific assertions are necessary.
     */
    @Test
    public void testRequestContextPropagatesAcrossObserveOnPool() {
        new SimpleCommand().execute();
        new SimpleCommand().observe().map(new Func1<String, String>() {

            @Override
            public String call(String s) {
                return s;
            }
        }).toBlocking().forEach(new Action1<String>() {

            @Override
            public void call(String s) {
            }
        });
    }

    private static class SimpleCommand extends HystrixCommand<String> {

        public SimpleCommand() {
            super(HystrixCommandGroupKey.Factory.asKey("SimpleCommand"));
        }

        @Override
        protected String run() throws Exception {
            if (HystrixRequestContext.isCurrentThreadInitialized()) {
            }
            return "Hello";
        }

    }

    @Test
    public void testNoRequestContextOnSimpleConcurencyStrategyWithoutException() throws Exception {
        shutdownContextIfExists();

        new SimpleCommand().execute();

        assertTrue("We are able to run the simple command without a context initialization error.", true);
    }

    private void shutdownContextIfExists() {
        // instead of storing the reference from initialize we'll just get the current state and shutdown
        if (HystrixRequestContext.getContextForCurrentThread() != null) {
            // it could have been set NULL by the test
            HystrixRequestContext.getContextForCurrentThread().shutdown();
        }
    }
}
