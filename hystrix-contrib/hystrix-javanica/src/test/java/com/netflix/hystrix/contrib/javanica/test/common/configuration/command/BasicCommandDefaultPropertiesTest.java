package com.netflix.hystrix.contrib.javanica.test.common.configuration.command;

import com.netflix.hystrix.contrib.javanica.annotation.DefaultProperties;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import org.junit.Before;
import org.junit.Test;

/**
 * Created by dmgcodevil.
 */
public abstract class BasicCommandDefaultPropertiesTest extends BasicHystrixTest {

    private Service service;

    protected abstract Service createService();

    @Before
    public void setUp() throws Exception {
        service = createService();
    }

    @Test
    public void testCommandInheritsDefaultGroupKey() {
        service.commandInheritsDefaultProperties();
    }

    @Test
    public void testCommandOverridesDefaultGroupKey() {
        service.commandOverridesGroupKey();
    }

    @Test
    public void testCommandInheritsDefaultThreadPoolKey() {
        service.commandInheritsDefaultProperties();
    }

    @Test
    public void testCommandOverridesDefaultThreadPoolKey() {
        service.commandOverridesThreadPoolKey();
    }

    @Test
    public void testCommandInheritsDefaultCommandProperties() {
        service.commandInheritsDefaultProperties();
    }

    @Test
    public void testCommandOverridesDefaultCommandProperties() {
        service.commandOverridesDefaultCommandProperties();
    }

    @Test
    public void testCommandInheritsThreadPollProperties() {
        service.commandInheritsDefaultProperties();
    }

    @Test
    public void testCommandOverridesDefaultThreadPollProperties() {
        service.commandOverridesDefaultThreadPoolProperties();
    }

    @DefaultProperties(groupKey = "DefaultGroupKey", threadPoolKey = "DefaultThreadPoolKey",
            threadPoolProperties = {
                    @HystrixProperty(name = "maxQueueSize", value = "123")
            }
    )
    public static class Service {

        @HystrixCommand
        public Object commandInheritsDefaultProperties() {
            return null;
        }

        @HystrixCommand(groupKey = "SpecificGroupKey")
        public Object commandOverridesGroupKey() {
            return null;
        }

        @HystrixCommand(threadPoolKey = "SpecificThreadPoolKey")
        public Object commandOverridesThreadPoolKey() {
            return null;
        }

        @HystrixCommand
        public Object commandOverridesDefaultCommandProperties() {
            return null;
        }

        @HystrixCommand(threadPoolProperties = {
                @HystrixProperty(name = "maxQueueSize", value = "321")
        })
        public Object commandOverridesDefaultThreadPoolProperties() {
            return null;
        }
    }
}
