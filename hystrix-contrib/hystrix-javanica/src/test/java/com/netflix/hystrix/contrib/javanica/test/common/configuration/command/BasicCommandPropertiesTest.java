/*
 * Copyright 2016 Netflix, Inc.
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
package com.netflix.hystrix.contrib.javanica.test.common.configuration.command;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import org.junit.Before;
import org.junit.Test;

import static com.netflix.hystrix.contrib.javanica.conf.HystrixPropertiesManager.*;
import static org.junit.Assert.assertEquals;

/**
 * Created by dmgcodevil
 */
public abstract class BasicCommandPropertiesTest extends BasicHystrixTest {

    private UserService userService;

    protected abstract UserService createUserService();

    @Before
    public void setUp() {
        userService = createUserService();
    }

    @Test
    public void testGetUser() {
        User u1 = userService.getUser("1", "name: ");
        assertEquals("name: 1", u1.getName());
    }

    @Test
    public void testGetUserDefaultPropertiesValues() {
        User u1 = userService.getUserDefProperties("1", "name: ");
        assertEquals("name: 1", u1.getName());
    }

    @Test
    public void testGetUserDefGroupKeyWithSpecificThreadPoolKey() {
        User u1 = userService.getUserDefGroupKeyWithSpecificThreadPoolKey("1", "name: ");
        assertEquals("name: 1", u1.getName());
    }

    @Test
    public void testHystrixCommandProperties() {
        User u1 = userService.getUsingAllCommandProperties("1", "name: ");
        assertEquals("name: 1", u1.getName());
    }

    public static class UserService {

        @HystrixCommand(commandKey = "GetUserCommand", groupKey = "UserGroupKey", threadPoolKey = "Test",
                threadPoolProperties = {
                        @HystrixProperty(name = "coreSize", value = "30"),
                        @HystrixProperty(name = "maximumSize", value = "35"),
                        @HystrixProperty(name = "allowMaximumSizeToDivergeFromCoreSize", value = "true"),
                        @HystrixProperty(name = "maxQueueSize", value = "101"),
                        @HystrixProperty(name = "keepAliveTimeMinutes", value = "2"),
                        @HystrixProperty(name = "metrics.rollingStats.numBuckets", value = "12"),
                        @HystrixProperty(name = "queueSizeRejectionThreshold", value = "15"),
                        @HystrixProperty(name = "metrics.rollingStats.timeInMilliseconds", value = "1440")
                })
        public User getUser(String id, String name) {
            return new User(id, name + id); // it should be network call
        }

        @HystrixCommand
        public User getUserDefProperties(String id, String name) {
            return new User(id, name + id); // it should be network call
        }

        @HystrixCommand(threadPoolKey = "CustomThreadPool")
        public User getUserDefGroupKeyWithSpecificThreadPoolKey(String id, String name) {
            return new User(id, name + id); // it should be network call
        }

        @HystrixCommand(
                commandProperties = {
                        @HystrixProperty(name = EXECUTION_ISOLATION_STRATEGY, value = "SEMAPHORE"),
                        @HystrixProperty(name = EXECUTION_ISOLATION_SEMAPHORE_MAX_CONCURRENT_REQUESTS, value = "10"),
                        @HystrixProperty(name = REQUEST_CACHE_ENABLED, value = "false")
                }
        )
        public User getUsingAllCommandProperties(String id, String name) {
            return new User(id, name + id); // it should be network call
        }

    }
}
