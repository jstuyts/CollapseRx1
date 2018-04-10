/**
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
package com.netflix.hystrix.examples.demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;

import java.net.HttpCookie;

/**
 * Sample HystrixCommand simulating one that would fetch UserAccount objects from a remote service or database.
 * <p>
 * This uses request caching.
 */
public class GetUserAccountCommand extends HystrixCommand<UserAccount> {

    private final HttpCookie httpCookie;

    /**
     * 
     * @param cookie
     * @throws IllegalArgumentException
     *             if cookie is invalid meaning the user is not authenticated
     */
    public GetUserAccountCommand(HttpCookie cookie) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("User")));
        this.httpCookie = cookie;
        /* parse or throw an IllegalArgumentException */
        UserCookie.parseCookie();
    }

    @Override
    protected UserAccount run() {
        /* simulate performing network call to retrieve user information */
        try {
            Thread.sleep((int) (Math.random() * 10) + 2);
        } catch (InterruptedException e) {
            // do nothing
        }

        /* success ... create UserAccount with data "from" the remote service response */
        return new UserAccount(86975, "John James", 2, true, false, true);
    }

    /**
     * Use the HttpCookie value as the cacheKey so multiple executions
     * in the same HystrixRequestContext will respond from cache.
     */
    @Override
    protected String getCacheKey() {
        return httpCookie.getValue();
    }

    /**
     * Represents values containing in the cookie.
     * <p>
     * A real version of this could handle decrypting a secure HTTPS cookie.
     */
    private static class UserCookie {
        /**
         * Parse an HttpCookie into a UserCookie or IllegalArgumentException if invalid cookie
         * 
         * @return UserCookie
         * @throws IllegalArgumentException
         *             if cookie is invalid
         */
        private static UserCookie parseCookie() {
            /* real code would parse the cookie here */
            if (Math.random() < 0.998) {
                /* valid cookie */
                return new UserCookie();
            } else {
                /* invalid cookie */
                throw new IllegalArgumentException();
            }
        }

        public UserCookie() {
        }
    }
}
