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
package com.netflix.hystrix.contrib.javanica.test.common.collapser;

import com.google.common.collect.Sets;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCollapser;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by dmgcodevil
 */
public abstract class BasicCollapserTest extends BasicHystrixTest {

    protected abstract UserService createUserService();

    private UserService userService;

    @Before
    public void setUp() {
        userService = createUserService();

    }

    @Test
    public void testGetUserById() throws ExecutionException, InterruptedException {

        Future<User> f1 = userService.getUserById("1");
        Future<User> f2 = userService.getUserById("2");
        Future<User> f3 = userService.getUserById("3");
        Future<User> f4 = userService.getUserById("4");
        Future<User> f5 = userService.getUserById("5");

        assertEquals("name: 1", f1.get().getName());
        assertEquals("name: 2", f2.get().getName());
        assertEquals("name: 3", f3.get().getName());
        assertEquals("name: 4", f4.get().getName());
        assertEquals("name: 5", f5.get().getName());
    }

    @Test
    public void testReactive() {

        final Observable<User> u1 = userService.getUserByIdReactive("1");
        final Observable<User> u2 = userService.getUserByIdReactive("2");
        final Observable<User> u3 = userService.getUserByIdReactive("3");
        final Observable<User> u4 = userService.getUserByIdReactive("4");
        final Observable<User> u5 = userService.getUserByIdReactive("5");

        final Iterable<User> users = Observable.merge(u1, u2, u3, u4, u5).toBlocking().toIterable();

        Set<String> expectedIds = Sets.newHashSet("1", "2", "3", "4", "5");
        for (User cUser : users) {
            assertTrue(expectedIds.remove(cUser.getId()));
        }
        assertTrue(expectedIds.isEmpty());
    }

    @Test
    public void testGetUserById2() throws ExecutionException, InterruptedException {
        Future<User> f1 = userService.getUserById2("1");
        Future<User> f2 = userService.getUserById2("2");
        Future<User> f3 = userService.getUserById2("3");
        Future<User> f4 = userService.getUserById2("4");
        Future<User> f5 = userService.getUserById2("5");

        assertEquals("name: 1", f1.get().getName());
        assertEquals("name: 2", f2.get().getName());
        assertEquals("name: 3", f3.get().getName());
        assertEquals("name: 4", f4.get().getName());
        assertEquals("name: 5", f5.get().getName());
    }

    @Test
    public void testGetUserByIdWithThrowableParam() throws ExecutionException, InterruptedException {
        Future<User> f1 = userService.getUserByIdWithThrowableParam("1");
        Future<User> f2 = userService.getUserByIdWithThrowableParam("2");
        Future<User> f3 = userService.getUserByIdWithThrowableParam("3");
        Future<User> f4 = userService.getUserByIdWithThrowableParam("4");
        Future<User> f5 = userService.getUserByIdWithThrowableParam("5");

        assertEquals("name: 1", f1.get().getName());
        assertEquals("name: 2", f2.get().getName());
        assertEquals("name: 3", f3.get().getName());
        assertEquals("name: 4", f4.get().getName());
        assertEquals("name: 5", f5.get().getName());
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserByIdWrongBatchMethodArgType() {
        userService.getUserByIdWrongBatchMethodArgType("1");
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserByIdWrongBatchMethodReturnType() {
        userService.getUserByIdWrongBatchMethodArgType("1");
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserByIdWrongCollapserMethodReturnType() {
        userService.getUserByIdWrongCollapserMethodReturnType("1");
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserByIdWrongCollapserMultipleArgs() {
        userService.getUserByIdWrongCollapserMultipleArgs("1", "2");
    }

    @Test(expected = IllegalStateException.class)
    public void testGetUserByIdWrongCollapserNoArgs() {
        userService.getUserByIdWrongCollapserNoArgs();
    }

    public static class UserService {

        public static final Logger log = LoggerFactory.getLogger(UserService.class);


        @HystrixCollapser(batchMethod = "getUserByIds",
                collapserProperties = {@HystrixProperty(name = "timerDelayInMilliseconds", value = "200")})
        public Future<User> getUserById(String id) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIds",
                collapserProperties = {@HystrixProperty(name = "timerDelayInMilliseconds", value = "200")})
        public Future<User> getUserById2(String id) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIds",
                collapserProperties = {@HystrixProperty(name = "timerDelayInMilliseconds", value = "200")})
        public Observable<User> getUserByIdReactive(String id) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIdsThrowsException",
                collapserProperties = {@HystrixProperty(name = "timerDelayInMilliseconds", value = "200")})
        public Future<User> getUserByIdWithThrowableParam(String id) {
            return null;
        }

        @HystrixCommand
        public List<User> getUserByIdsThrowsException(List<String> ids) {
            throw new RuntimeException("getUserByIdsFails failed");
        }

        @HystrixCommand
        private List<User> getUserByIdsWithThrowableParam1(List<String> ids, Throwable e) {
            if (e.getMessage().equals("getUserByIdsFails failed")) {
                throw new RuntimeException("getUserByIdsWithThrowableParam1 failed");
            }
            List<User> users = new ArrayList<>();
            for (String id : ids) {
                users.add(new User(id, "name: " + id));
            }
            return users;
        }

        @HystrixCommand
        private List<User> getUserByIdsWithThrowableParam2(List<String> ids) {
            throw new RuntimeException("getUserByIdsWithThrowableParam2 failed");
        }

        @HystrixCommand
        private List<User> getUserByIdsWithThrowableParam3(List<String> ids, Throwable e) {
            if (!e.getMessage().equals("getUserByIdsWithThrowableParam2 failed")) {
                throw new RuntimeException("getUserByIdsWithThrowableParam3 failed");
            }
            List<User> users = new ArrayList<>();
            for (String id : ids) {
                users.add(new User(id, "name: " + id));
            }
            return users;
        }

        @HystrixCommand
        public List<User> getUserByIds(List<String> ids) {
            List<User> users = new ArrayList<>();
            for (String id : ids) {
                users.add(new User(id, "name: " + id));
            }
            log.debug("executing on thread id: {}", Thread.currentThread().getId());
            return users;
        }

        @HystrixCommand
        public List<User> getUserByIds2(List<String> ids) {
            throw new RuntimeException("not found");
        }


        // wrong return type, expected: Future<User> or User, because batch command getUserByIds returns List<User>
        @HystrixCollapser(batchMethod = "getUserByIds")
        public Long getUserByIdWrongCollapserMethodReturnType(String id) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIds")
        public Future<User> getUserByIdWrongCollapserMultipleArgs(String id, String name) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIds")
        public Future<User> getUserByIdWrongCollapserNoArgs() {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIdsWrongBatchMethodArgType")
        public Future<User> getUserByIdWrongBatchMethodArgType(String id) {
            return null;
        }

        // wrong arg type, expected: List<String>
        @HystrixCommand
        public List<User> getUserByIdsWrongBatchMethodArgType(List<Integer> ids) {
            return null;
        }

        @HystrixCollapser(batchMethod = "getUserByIdsWrongBatchMethodReturnType")
        public Future<User> getUserByIdWrongBatchMethodReturnType(String id) {
            return null;
        }

        // wrong return type, expected: List<User>
        @HystrixCommand
        public List<Integer> getUserByIdsWrongBatchMethodReturnType(List<String> ids) {
            return null;
        }

    }
}
