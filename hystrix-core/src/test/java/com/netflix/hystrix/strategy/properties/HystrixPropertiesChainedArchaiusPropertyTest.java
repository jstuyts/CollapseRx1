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
package com.netflix.hystrix.strategy.properties;

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedArchaiusProperty.*;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HystrixPropertiesChainedArchaiusPropertyTest {
    @After
    public void cleanUp() {
        // Tests which use ConfigurationManager.getConfigInstance() will leave the singleton in an initialize state,
        // this will leave the singleton in a reasonable state between tests.
        ConfigurationManager.getConfigInstance().clear();
    }

    @Test
    public void testString() {

        DynamicStringProperty pString = new DynamicStringProperty("defaultString", "default-default");
        HystrixPropertiesChainedArchaiusProperty.StringProperty fString = new HystrixPropertiesChainedArchaiusProperty.StringProperty("overrideString", pString);

        assertEquals("default-default", fString.get());

        ConfigurationManager.getConfigInstance().setProperty("defaultString", "default");
        assertEquals("default", fString.get());

        ConfigurationManager.getConfigInstance().setProperty("overrideString", "override");
        assertEquals("override", fString.get());

        ConfigurationManager.getConfigInstance().clearProperty("overrideString");
        assertEquals("default", fString.get());

        ConfigurationManager.getConfigInstance().clearProperty("defaultString");
        assertEquals("default-default", fString.get());
    }

    @Test
    public void testInteger() {

        DynamicIntegerProperty pInt = new DynamicIntegerProperty("defaultInt", -1);
        HystrixPropertiesChainedArchaiusProperty.IntegerProperty fInt = new HystrixPropertiesChainedArchaiusProperty.IntegerProperty("overrideInt", pInt);

        assertEquals(-1, (int) fInt.get());

        ConfigurationManager.getConfigInstance().setProperty("defaultInt", 10);
        assertEquals(10, (int) fInt.get());

        ConfigurationManager.getConfigInstance().setProperty("overrideInt", 11);
        assertEquals(11, (int) fInt.get());

        ConfigurationManager.getConfigInstance().clearProperty("overrideInt");
        assertEquals(10, (int) fInt.get());

        ConfigurationManager.getConfigInstance().clearProperty("defaultInt");
        assertEquals(-1, (int) fInt.get());
    }

    @Test
    public void testBoolean() {

        DynamicBooleanProperty pBoolean = new DynamicBooleanProperty("defaultBoolean", true);
        HystrixPropertiesChainedArchaiusProperty.BooleanProperty fBoolean = new HystrixPropertiesChainedArchaiusProperty.BooleanProperty("overrideBoolean", pBoolean);

        System.out.println("pBoolean: " + pBoolean.get());
        System.out.println("fBoolean: " + fBoolean.get());

        assertTrue(fBoolean.get());

        ConfigurationManager.getConfigInstance().setProperty("defaultBoolean", Boolean.FALSE);

        System.out.println("pBoolean: " + pBoolean.get());
        System.out.println("fBoolean: " + fBoolean.get());

        assertFalse(fBoolean.get());

        ConfigurationManager.getConfigInstance().setProperty("overrideBoolean", Boolean.TRUE);
        assertTrue(fBoolean.get());

        ConfigurationManager.getConfigInstance().clearProperty("overrideBoolean");
        assertFalse(fBoolean.get());

        ConfigurationManager.getConfigInstance().clearProperty("defaultBoolean");
        assertTrue(fBoolean.get());
    }

    @Test
    public void testChainingString() {

        DynamicStringProperty node1 = new DynamicStringProperty("node1", "v1");
        StringProperty node2 = new HystrixPropertiesChainedArchaiusProperty.StringProperty("node2", node1);

        HystrixPropertiesChainedArchaiusProperty.StringProperty node3 = new HystrixPropertiesChainedArchaiusProperty.StringProperty("node3", node2);

        assertEquals("" + node3.get(), "v1", node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node1", "v11");
        assertEquals("v11", node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", "v22");
        assertEquals("v22", node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node1");
        assertEquals("v22", node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node3", "v33");
        assertEquals("v33", node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node2");
        assertEquals("v33", node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", "v222");
        assertEquals("v33", node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node3");
        assertEquals("v222", node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node2");
        assertEquals("v1", node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", "v2222");
        assertEquals("v2222", node3.get());
    }

    @Test
    public void testChainingInteger() {

        DynamicIntegerProperty node1 = new DynamicIntegerProperty("node1", 1);
        IntegerProperty node2 = new HystrixPropertiesChainedArchaiusProperty.IntegerProperty("node2", node1);

        HystrixPropertiesChainedArchaiusProperty.IntegerProperty node3 = new HystrixPropertiesChainedArchaiusProperty.IntegerProperty("node3", node2);

        assertEquals("" + node3.get(), 1, (int) node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node1", 11);
        assertEquals(11, (int) node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", 22);
        assertEquals(22, (int) node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node1");
        assertEquals(22, (int) node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node3", 33);
        assertEquals(33, (int) node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node2");
        assertEquals(33, (int) node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", 222);
        assertEquals(33, (int) node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node3");
        assertEquals(222, (int) node3.get());

        ConfigurationManager.getConfigInstance().clearProperty("node2");
        assertEquals(1, (int) node3.get());

        ConfigurationManager.getConfigInstance().setProperty("node2", 2222);
        assertEquals(2222, (int) node3.get());
    }

    @Test
    public void testAddCallback() {

        final DynamicStringProperty node1 = new DynamicStringProperty("n1", "n1");
        final HystrixPropertiesChainedArchaiusProperty.StringProperty node2 = new HystrixPropertiesChainedArchaiusProperty.StringProperty("n2", node1);

        final AtomicInteger callbackCount = new AtomicInteger(0);

        node2.addCallback(callbackCount::incrementAndGet);

        assertEquals(0, callbackCount.get());

        assertEquals("n1", node2.get());
        assertEquals(0, callbackCount.get());

        ConfigurationManager.getConfigInstance().setProperty("n1", "n11");
        assertEquals("n11", node2.get());
        assertEquals(0, callbackCount.get());

        ConfigurationManager.getConfigInstance().setProperty("n2", "n22");
        assertEquals("n22", node2.get());
        assertEquals(1, callbackCount.get());

        ConfigurationManager.getConfigInstance().clearProperty("n1");
        assertEquals("n22", node2.get());
        assertEquals(1, callbackCount.get());

        ConfigurationManager.getConfigInstance().setProperty("n2", "n222");
        assertEquals("n222", node2.get());
        assertEquals(2, callbackCount.get());

        ConfigurationManager.getConfigInstance().clearProperty("n2");
        assertEquals("n1", node2.get());
        assertEquals(3, callbackCount.get());
    }

}
