/**
 * Copyright 2012 Netflix, Inc.
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
package com.netflix.hystrix.contrib.javanica.command;

/**
 * Wrapper for command actions combines different actions together.
 *
 * @author dmgcodevil
 */
public class CommandActions {

    private final CommandAction commandAction;

    public CommandActions(Builder builder) {
        this.commandAction = builder.commandAction;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CommandAction getCommandAction() {
        return commandAction;
    }

    public static class Builder {
        private CommandAction commandAction;

        public Builder commandAction(CommandAction pCommandAction) {
            this.commandAction = pCommandAction;
            return this;
        }

        public CommandActions build() {
            return new CommandActions(this);
        }
    }

}
