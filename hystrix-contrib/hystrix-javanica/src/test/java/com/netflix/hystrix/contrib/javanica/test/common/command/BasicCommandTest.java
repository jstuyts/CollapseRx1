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
package com.netflix.hystrix.contrib.javanica.test.common.command;


import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.command.AsyncResult;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

public abstract class BasicCommandTest extends BasicHystrixTest {

    private UserService userService;
    private AdvancedUserService advancedUserService;
    private GenericService<String, Long, User> genericUserService;

    @Before
    public void setUp() {
        userService = createUserService();
        advancedUserService = createAdvancedUserServiceService();
        genericUserService = createGenericUserService();
    }

    @Test
    public void testGetUserAsync() throws ExecutionException, InterruptedException {
        Future<User> f1 = userService.getUserAsync("1", "name: ");

        assertEquals("name: 1", f1.get().getName());
    }

    @Test
    public void testGetUserSync() {
        User u1 = userService.getUserSync("1", "name: ");
        assertGetUserSyncCommandExecuted(u1);
    }

    @Test
    public void shouldWorkWithInheritedMethod() {
        User u1 = advancedUserService.getUserSync("1", "name: ");
        assertGetUserSyncCommandExecuted(u1);
    }

    @Test
    public void should_work_with_parameterized_method() {
        assertEquals(Integer.valueOf(1), userService.echo(1));
    }

    @Test
    public void should_work_with_parameterized_asyncMethod() throws Exception {
        assertEquals(Integer.valueOf(1), userService.echoAsync(1).get());
    }

    @Test
    public void should_work_with_genericClass() {
        User user = genericUserService.getByKeyForceFail("1", 2L);
        assertEquals("name: 2", user.getName());
    }


    private void assertGetUserSyncCommandExecuted(User u1) {
        assertEquals("name: 1", u1.getName());
    }

    protected abstract UserService createUserService();
    protected abstract AdvancedUserService createAdvancedUserServiceService();
    protected abstract GenericService<String, Long, User> createGenericUserService();

    public interface GenericService<K1, K2, V> {
        V getByKeyForceFail(K1 key, K2 key2);
    }

    public static class GenericUserService implements GenericService<String, Long, User> {

        @HystrixCommand
        @Override
        public User getByKeyForceFail(String sKey, Long lKey) {
            throw new RuntimeException("force fail");
        }

    }

    public static class UserService {

        @HystrixCommand(commandKey = "GetUserCommand", threadPoolKey = "CommandTestAsync")
        public Future<User> getUserAsync(final String id, final String name) {
            return new AsyncResult<User>() {
                @Override
                public User invoke() {
                    return new User(id, name + id); // it should be network call
                }
            };
        }

        @HystrixCommand(groupKey = "UserGroup")
        public User getUserSync(String id, String name) {
            return new User(id, name + id); // it should be network call
        }

        @HystrixCommand
        public <T> T echo(T value) {
            return value;
        }

        @HystrixCommand
        public <T> Future<T> echoAsync(final T value) {
            return new AsyncResult<T>() {
                @Override
                public T invoke() {
                    return value;
                }
            };
        }

    }

    public static class AdvancedUserService extends UserService {

    }

}
