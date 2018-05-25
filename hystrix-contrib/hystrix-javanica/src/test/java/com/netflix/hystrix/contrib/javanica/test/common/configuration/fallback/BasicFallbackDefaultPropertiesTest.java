package com.netflix.hystrix.contrib.javanica.test.common.configuration.fallback;

import com.netflix.hystrix.contrib.javanica.annotation.DefaultProperties;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import org.junit.Before;
import org.junit.Test;

public abstract class BasicFallbackDefaultPropertiesTest extends BasicHystrixTest {

    private Service service;

    protected abstract Service createService();

    @Before
    public void setUp() {
        service = createService();
    }

    @Test
    public void testFallbackInheritsDefaultGroupKey() {
        service.commandWithFallbackInheritsDefaultProperties();
    }

    @Test
    public void testFallbackInheritsDefaultThreadPoolKey() {
        service.commandWithFallbackInheritsDefaultProperties();
    }

    @Test
    public void testFallbackInheritsDefaultCommandProperties() {
        service.commandWithFallbackInheritsDefaultProperties();
    }

    @Test
    public void testFallbackInheritsThreadPollProperties() {
        service.commandWithFallbackInheritsDefaultProperties();
    }

    @Test
    public void testFallbackOverridesDefaultGroupKey() {
        service.commandWithFallbackOverridesDefaultProperties();
    }

    @Test
    public void testFallbackOverridesDefaultThreadPoolKey() {
        service.commandWithFallbackOverridesDefaultProperties();
    }

    @Test
    public void testFallbackOverridesDefaultCommandProperties() {
        service.commandWithFallbackOverridesDefaultProperties();
    }

    @Test
    public void testFallbackOverridesThreadPollProperties() {
        service.commandWithFallbackOverridesDefaultProperties();
    }

    @Test
    public void testCommandOverridesDefaultPropertiesWithFallbackInheritsDefaultProperties(){
        service.commandOverridesDefaultPropertiesWithFallbackInheritsDefaultProperties();
    }

    @DefaultProperties(groupKey = "DefaultGroupKey",
            threadPoolKey = "DefaultThreadPoolKey",
            threadPoolProperties = {
                    @HystrixProperty(name = "maxQueueSize", value = "123")
            })
    public static class Service {

        @HystrixCommand
        public Object commandWithFallbackInheritsDefaultProperties() {
            throw new RuntimeException();
        }

        @HystrixCommand
        public Object commandWithFallbackOverridesDefaultProperties() {
            throw new RuntimeException();
        }

        @HystrixCommand(groupKey = "CommandGroupKey",
                threadPoolKey = "CommandThreadPoolKey",
                threadPoolProperties = {
                        @HystrixProperty(name = "maxQueueSize", value = "321")
                })
        public Object commandOverridesDefaultPropertiesWithFallbackInheritsDefaultProperties() {
            throw new RuntimeException();
        }

        @HystrixCommand
        private Object fallbackInheritsDefaultProperties() {
            return null;
        }

        @HystrixCommand(groupKey = "FallbackGroupKey",
                threadPoolKey = "FallbackThreadPoolKey",
                threadPoolProperties = {
                        @HystrixProperty(name = "maxQueueSize", value = "321")
                })
        private Object fallbackOverridesDefaultProperties() {
            return null;
        }
    }
}
