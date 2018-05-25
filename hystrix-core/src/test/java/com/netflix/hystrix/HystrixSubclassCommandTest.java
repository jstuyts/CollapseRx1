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

import com.hystrix.junit.HystrixRequestContextRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HystrixSubclassCommandTest {

    private final static HystrixCommandGroupKey groupKey = HystrixCommandGroupKey.Factory.asKey("GROUP");
    @Rule
    public HystrixRequestContextRule ctx = new HystrixRequestContextRule();

    @Test
    public void testRequestCacheSuperClass() {
        HystrixCommand<Integer> superCmd1 = new SuperCommand("cache", true);
        assertEquals(1, superCmd1.execute().intValue());
        HystrixCommand<Integer> superCmd2 = new SuperCommand("cache", true);
        assertEquals(1, superCmd2.execute().intValue());
        HystrixCommand<Integer> superCmd3 = new SuperCommand("no-cache", true);
        assertEquals(1, superCmd3.execute().intValue());
    }

    @Test
    public void testRequestCacheSubclassNoOverrides() {
        HystrixCommand<Integer> subCmd1 = new SubCommandNoOverride("cache", true);
        assertEquals(1, subCmd1.execute().intValue());
        HystrixCommand<Integer> subCmd2 = new SubCommandNoOverride("cache", true);
        assertEquals(1, subCmd2.execute().intValue());
        HystrixCommand<Integer> subCmd3 = new SubCommandNoOverride("no-cache", true);
        assertEquals(1, subCmd3.execute().intValue());
    }

    public static class SuperCommand extends HystrixCommand<Integer> {
        private final String uniqueArg;
        private final boolean shouldSucceed;

        SuperCommand(String uniqueArg, boolean shouldSucceed) {
            super(Setter.withGroupKey(groupKey));
            this.uniqueArg = uniqueArg;
            this.shouldSucceed = shouldSucceed;
        }

        @Override
        protected Integer run() {
            if (shouldSucceed) {
                return 1;
            } else {
                throw new RuntimeException("unit test failure");
            }
        }

        @Override
        protected String getCacheKey() {
            return uniqueArg;
        }
    }

    public static class SubCommandNoOverride extends SuperCommand {
        SubCommandNoOverride(String uniqueArg, boolean shouldSucceed) {
            super(uniqueArg, shouldSucceed);
        }
    }

}
