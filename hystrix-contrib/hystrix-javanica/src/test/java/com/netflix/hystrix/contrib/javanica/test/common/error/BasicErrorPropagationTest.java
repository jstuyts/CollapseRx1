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
package com.netflix.hystrix.contrib.javanica.test.common.error;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import com.netflix.hystrix.exception.ExceptionNotWrappedByHystrix;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Created by dmgcodevil
 */
public abstract class BasicErrorPropagationTest extends BasicHystrixTest {

    private static final String COMMAND_KEY = "getUserById";

    private static final Map<String, User> USERS;

    static {
        USERS = new HashMap<String, User>();
        USERS.put("1", new User("1", "user_1"));
        USERS.put("2", new User("2", "user_2"));
        USERS.put("3", new User("3", "user_3"));
    }

    private UserService userService;

    @Mock
    private FailoverService failoverService;

    protected abstract UserService createUserService();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        userService = createUserService();
        userService.setFailoverService(failoverService);
    }

    @Test(expected = BadRequestException.class)
    public void testGetUserByBadId() throws NotFoundException {
        try {
            String badId = "";
            userService.getUserById(badId);
        } finally {
            verify(failoverService, never()).getDefUser();
        }
    }

    @Test(expected = NotFoundException.class)
    public void testGetNonExistentUser() throws NotFoundException {
        try {
            userService.getUserById("4"); // user with id 4 doesn't exist
        } finally {
            verify(failoverService, never()).getDefUser();
        }
    }

    @Test
    public void testActivateUser() throws NotFoundException, ActivationException {
        try {
            userService.activateUser("1"); // this method always throws ActivationException
        } finally {
            verify(failoverService, atLeastOnce()).activate();
        }
    }

    @Test(expected = RuntimeOperationException.class)
    public void testBlockUser() throws NotFoundException, ActivationException, OperationException {
        try {
            userService.blockUser("1"); // this method always throws ActivationException
        } finally {
        }
    }

    @Test(expected = NotFoundException.class)
    public void testPropagateCauseException() throws NotFoundException {
        userService.deleteUser("");
    }

    @Test(expected = UserException.class)
    public void testUserExceptionThrownFromCommand() {
        userService.userFailure();
    }

    @Test
    public void testUser2() {
        try {
            userService.userFailure2();
        } catch (UserException e) {
            assertEquals(1, e.level);
        } catch (Throwable e) {
            assertTrue("'UserException' is expected exception.", false);
        }
    }

    @Test
    public void testUser() {
        try {
            userService.userFailure3();
        } catch (UserException e) {
            assertEquals(2, e.level);
        } catch (Throwable e) {
            assertTrue("'UserException' is expected exception.", false);
        }
    }

    @Test
    public void testCommand() {
        try {
            userService.command();
        } catch (HystrixFlowException e) {
            assertEquals(UserException.class, e.commandException.getClass());

            UserException commandException = (UserException) e.commandException;
            assertEquals(0, commandException.level);


        } catch (Throwable e) {
            assertTrue("'HystrixFlowException' is expected exception.", false);
        }
    }

    @Test
    public void testCommand2() {
        try {
            userService.command2();
        } catch (HystrixRuntimeException e) {
            assertEquals(TimeoutException.class, e.getCause().getClass());
        } catch (Throwable e) {
            assertTrue("'HystrixRuntimeException' is expected exception.", false);
        }
    }

    @Test
    public void testCommandWithNotWrappedException2() {
        try {
            userService.throwNotWrappedCheckedException();
            fail();
        } catch (NotWrappedCheckedException e) {
            // pass
        } catch (Throwable e) {
            fail("'NotWrappedCheckedException' is expected exception.");
        }finally {
            verify(failoverService, never()).activate();
        }
    }

    @Test
    public void testCommandWithNotWrappedException() {
        try {
            userService.throwNotWrappedCheckedException2();
        } catch (NotWrappedCheckedException e) {
            fail();
        } finally {
            verify(failoverService).activate();
        }
    }

    public static class UserService {

        private FailoverService failoverService;

        public void setFailoverService(FailoverService failoverService) {
            this.failoverService = failoverService;
        }

        @HystrixCommand
        public Object deleteUser(String id) throws NotFoundException {
            throw new NotFoundException("");
        }

        @HystrixCommand(
                commandKey = COMMAND_KEY,
                ignoreExceptions = {
                        BadRequestException.class,
                        NotFoundException.class
                })
        public User getUserById(String id) throws NotFoundException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            return USERS.get(id);
        }


        @HystrixCommand(
                ignoreExceptions = {BadRequestException.class, NotFoundException.class})
        public void activateUser(String id) throws NotFoundException, ActivationException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            // always throw this exception
            throw new ActivationException("user cannot be activate");
        }

        @HystrixCommand(
                ignoreExceptions = {BadRequestException.class, NotFoundException.class})
        public void blockUser(String id) throws NotFoundException, OperationException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            // always throw this exception
            throw new OperationException("user cannot be blocked");
        }

        private void validate(String val) throws BadRequestException {
            if (val == null || val.length() == 0) {
                throw new BadRequestException("parameter cannot be null ot empty");
            }
        }

        @HystrixCommand
        void throwNotWrappedCheckedException() throws NotWrappedCheckedException {
            throw new NotWrappedCheckedException();
        }

        @HystrixCommand
        void throwNotWrappedCheckedException2() throws NotWrappedCheckedException {
            throw new NotWrappedCheckedException();
        }

        /*********************************************************************************/

        @HystrixCommand
        String userFailure() throws UserException {
            throw new UserException();
        }

        /*********************************************************************************/

        @HystrixCommand
        String userFailure2() {
            throw new UserException();
        }

        /*********************************************************************************/

        @HystrixCommand
        String userFailure3() {
            throw new UserException(0);
        }

        /*********************************************************************************/

        @HystrixCommand
        String command() {
            throw new UserException();
        }

        /*********************************************************************************/

        @HystrixCommand
        String command2() {
            throw new UserException(0);
        }
    }

    private class FailoverService {
        public User getDefUser() {
            return new User("def", "def");
        }

        public void activate() {
        }
    }

    // exceptions
    private static class NotFoundException extends Exception {
        private NotFoundException(String message) {
            super(message);
        }
    }

    private static class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }

    private static class ActivationException extends Exception {
        private ActivationException(String message) {
            super(message);
        }
    }

    private static class OperationException extends Throwable {
        private OperationException(String message) {
            super(message);
        }
    }

    private static class RuntimeOperationException extends RuntimeException {
        private RuntimeOperationException(String message) {
            super(message);
        }
    }

    private static class NotWrappedCheckedException extends Exception implements ExceptionNotWrappedByHystrix {
    }

    static class UserException extends RuntimeException {
        final int level;

        public UserException() {
            this(0);
        }

        public UserException(int level) {
            this.level = level;
        }
    }

    static class HystrixFlowException extends RuntimeException {
        final Throwable commandException;

        public HystrixFlowException(Throwable commandException) {
            this.commandException = commandException;
        }
    }

}
