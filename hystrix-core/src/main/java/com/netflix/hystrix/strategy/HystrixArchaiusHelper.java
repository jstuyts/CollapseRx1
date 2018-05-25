/*
 * Copyright 2016 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.strategy;

import com.netflix.hystrix.strategy.properties.HystrixDynamicProperties;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @ExcludeFromJavadoc
 * @author agentgt
 */
class HystrixArchaiusHelper {

    /**
     * To keep class loading minimal for those that have archaius in the classpath but choose not to use it.
     * @ExcludeFromJavadoc
     * @author agent
     */
    private static class LazyHolder {
        private final static Method loadCascadedPropertiesFromResources;
        private final static String CONFIG_MANAGER_CLASS = "com.netflix.config.ConfigurationManager";
    
        static {
            Method load = null;
            try {
                Class<?> configManager = Class.forName(CONFIG_MANAGER_CLASS);
                load = configManager.getMethod("loadCascadedPropertiesFromResources", String.class);
            } catch (Exception e) {
                // No action needed. Continue without Archaius.
            }
    
            loadCascadedPropertiesFromResources = load;
        }
    }

    /**
     * @ExcludeFromJavadoc
     */
    static boolean isArchaiusV1Available() {
        return LazyHolder.loadCascadedPropertiesFromResources != null;
    }

    static void loadCascadedPropertiesFromResources(String name) {
        if (isArchaiusV1Available()) {
            try {
                LazyHolder.loadCascadedPropertiesFromResources.invoke(null, name);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                // No action needed. Ignore the properties from resources if a failure occurs during loading.
            }
        }
    }

    /**
     * @ExcludeFromJavadoc
     */
    static HystrixDynamicProperties createArchaiusDynamicProperties() {
        if (isArchaiusV1Available()) {
            loadCascadedPropertiesFromResources("hystrix-plugins");
            try {
                Class<?> defaultProperties = Class.forName(
                        "com.netflix.hystrix.strategy.properties.archaius" + ".HystrixDynamicPropertiesArchaius");
                return (HystrixDynamicProperties) defaultProperties.newInstance();
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        // Fallback to System properties.
        return null;
    }

}
