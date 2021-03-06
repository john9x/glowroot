/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.storage.config;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class RoleConfig {

    public abstract String name();
    public abstract ImmutableSet<String> permissions();

    @Value.Derived
    @JsonIgnore
    public ImmutableSet<SimplePermission> simplePermissions() {
        Set<SimplePermission> simplePermissions = Sets.newHashSet();
        for (String permission : permissions()) {
            simplePermissions.add(SimplePermission.create(permission));
        }
        return ImmutableSet.copyOf(simplePermissions);
    }

    public boolean isPermitted(SimplePermission permission) {
        for (SimplePermission simplePermission : simplePermissions()) {
            if (simplePermission.implies(permission)) {
                return true;
            }
        }
        return false;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }

    @Value.Immutable
    public abstract static class SimplePermission {

        public static SimplePermission create(String permission) {
            return ImmutableSimplePermission.builder()
                    .addAllParts(Splitter.on(':').splitToList(permission))
                    .build();
        }

        public abstract List<String> parts();

        public boolean implies(SimplePermission other) {
            List<String> otherParts = other.parts();
            if (otherParts.size() < parts().size()) {
                return false;
            }
            for (int i = 0; i < parts().size(); i++) {
                String part = parts().get(i);
                String otherPart = otherParts.get(i);
                if (!implies(part, otherPart)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean implies(String part, String otherPart) {
            return part.equals(otherPart) || part.equals("*");
        }
    }
}
