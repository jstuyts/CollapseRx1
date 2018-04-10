/**
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
package com.netflix.hystrix.contrib.javanica.test.common.observable;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.ObservableExecutionMode;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import rx.*;
import rx.functions.Action1;

import static org.junit.Assert.assertEquals;

/**
 * Created by dmgcodevil
 */
public abstract class BasicObservableTest extends BasicHystrixTest {


    private UserService userService;

    protected abstract UserService createUserService();

    @Before
    public void setUp() throws Exception {
        userService = createUserService();
    }

    @Test
    public void testGetUserByIdSuccess() {
        // blocking
        Observable<User> observable = userService.getUser("1", "name: ");
        assertEquals("name: 1", observable.toBlocking().single().getName());

        // non-blocking
        // - this is a verbose anonymous inner-class approach and doesn't do assertions
        Observable<User> fUser = userService.getUser("1", "name: ");
        fUser.subscribe(new Observer<User>() {

            @Override
            public void onCompleted() {
                // nothing needed here
            }

            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }

            @Override
            public void onNext(User v) {
                System.out.println("onNext: " + v);
            }

        });

        Observable<User> fs = userService.getUser("1", "name: ");
        fs.subscribe(new Action1<User>() {

            @Override
            public void call(User user) {
                assertEquals("name: 1", user.getName());
            }
        });
    }

    @Test
    public void testGetCompletableUser(){
        userService.getCompletableUser("1", "name: ");
    }

    @Test
    public void testGetCompletableUser2() {
        Completable completable = userService.getCompletableUser2(null, "name: ");
        completable.<User>toObservable().subscribe(new Action1<User>() {
            @Override
            public void call(User user) {
                assertEquals("default_id", user.getId());
            }
        });
    }

    @Test
    public void testGetCompletableUser3() {
        Completable completable = userService.getCompletableUser3(null, "name: ");
        completable.<User>toObservable().subscribe(new Action1<User>() {
            @Override
            public void call(User user) {
                assertEquals("default_id", user.getId());
            }
        });
    }

    @Test
    public void testGetSingleUser() {
        final String id = "1";
        Single<User> user = userService.getSingleUser(id, "name: ");
        user.subscribe(new Action1<User>() {
            @Override
            public void call(User user) {
                assertEquals(id, user.getId());
            }
        });
    }

    @Test
    public void testGetSingleUser2(){
        Single<User> user = userService.getSingleUser2(null, "name: ");
        user.subscribe(new Action1<User>() {
            @Override
            public void call(User user) {
                assertEquals("default_id", user.getId());
            }
        });
    }

    @Test
    public void testGetSingleUser3(){
        Single<User> user = userService.getSingleUser3(null, "name: ");
        user.subscribe(new Action1<User>() {
            @Override
            public void call(User user) {
                assertEquals("default_id", user.getId());
            }
        });
    }

    @Test
    public void testGetUser() {
        final User exUser = new User("def", "def");
        Observable<User> userObservable = userService.getUser4(" ", "");
        // blocking
        assertEquals(exUser, userObservable.toBlocking().single());
    }

    @Test
    public void testGetUser2() {
        final User exUser = new User("def", "def");

        // blocking
        assertEquals(exUser, userService.getUser5(" ", "").toBlocking().single());
    }

    @Test
    public void testGetUser3() {
        final User exUser = new User("def", "def");

        // blocking
        Observable<User> userObservable = userService.getUser6(" ", "");
        assertEquals(exUser, userObservable.toBlocking().single());
    }


    public static class UserService {

        @HystrixCommand
        public Observable<User> getUser(final String id, final String name) {
            validate(id, name, "getUser has failed");
            return createObservable(id, name);
        }

        @HystrixCommand
        public Completable getCompletableUser(final String id, final String name) {
            validate(id, name, "getCompletableUser has failed");
            return createObservable(id, name).toCompletable();
        }

        @HystrixCommand
        public Completable getCompletableUser2(final String id, final String name) {
            return getCompletableUser(id, name);
        }

        @HystrixCommand
        public Completable getCompletableUser3(final String id, final String name) {
            return getCompletableUser(id, name);
        }

        @HystrixCommand
        public Single<User> getSingleUser(final String id, final String name) {
            validate(id, name, "getSingleUser has failed");
            return createObservable(id, name).toSingle();
        }

        @HystrixCommand
        public Single<User> getSingleUser2(final String id, final String name) {
            return getSingleUser(id, name);
        }

        @HystrixCommand
        public Single<User> getSingleUser3(final String id, final String name) {
            return getSingleUser(id, name);
        }

        @HystrixCommand(observableExecutionMode = ObservableExecutionMode.LAZY)
        public Observable<User> getUser4(final String id, final String name) {
            validate(id, name, "getUser has failed");
            return createObservable(id, name);
        }

        @HystrixCommand
        public Observable<User> getUser5(final String id, final String name) {
            validate(id, name, "getUser has failed");
            return createObservable(id, name);
        }

        @HystrixCommand(observableExecutionMode = ObservableExecutionMode.LAZY)
        public Observable<User> getUser6(final String id, final String name) {
            validate(id, name, "getUser has failed");
            return createObservable(id, name);
        }


        private Observable<User> createObservable(final String id, final String name) {
            return Observable.create(new Observable.OnSubscribe<User>() {
                @Override
                public void call(Subscriber<? super User> observer) {
                    try {
                        if (!observer.isUnsubscribed()) {
                            observer.onNext(new User(id, name + id));
                            observer.onCompleted();
                        }
                    } catch (Exception e) {
                        observer.onError(e);
                    }
                }
            });
        }

        private void validate(String id, String name, String errorMsg) {
            if (StringUtils.isBlank(id) || StringUtils.isBlank(name)) {
                throw new GetUserException(errorMsg);
            }
        }

        private static final class GetUserException extends RuntimeException {
            public GetUserException(String message) {
                super(message);
            }
        }
    }
}
