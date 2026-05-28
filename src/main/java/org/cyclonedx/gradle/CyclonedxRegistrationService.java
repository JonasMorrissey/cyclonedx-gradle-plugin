/*
 * This file is part of CycloneDX Gradle Plugin.
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.cyclonedx.gradle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Build service carrying the task-paths of each subproject needed by the aggregate task.
 */
public abstract class CyclonedxRegistrationService
        implements BuildService<CyclonedxRegistrationService.Parameters>, AutoCloseable {

    public interface Parameters extends BuildServiceParameters {}

    public static final class Registration {
        public final String taskPath;
        /**
         * Path of the project that registered this entry, e.g. ":" or ":app-a". We only need this if we want to allow aggregate tasks on subprojects, i.e. non-root-projects
         */
        public final String projectPath;

        public final List<Provider<RegularFile>> outputFiles;

        /**
         *
         * @param taskPath    e.g. :lib:cyclonedxDirectBom
         * @param projectPath e.g :lib
         * @param outputFiles Lazy task-output-backed providers (carry implicit task dependencies).
         */
        public Registration(String taskPath, String projectPath, List<Provider<RegularFile>> outputFiles) {
            this.taskPath = taskPath;
            this.projectPath = projectPath;
            this.outputFiles = (outputFiles == null)
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(outputFiles));
        }
    }

    /**
     * All registrations whose project path is equal to {@code rootPath} or a descendant of it.
     */
    public List<Registration> getRegistrationsInSubtree(String rootPath) {
        final String prefix = ":".equals(rootPath) ? ":" : rootPath + ":";
        synchronized (registrations) {
            return registrations.stream()
                    .filter(r ->
                            ":".equals(rootPath) || r.projectPath.equals(rootPath) || r.projectPath.startsWith(prefix))
                    .collect(Collectors.toList());
        }
    }

    private final List<Registration> registrations = Collections.synchronizedList(new ArrayList<>());

    public void register(Registration registration) {
        registrations.add(registration);
    }

    public List<String> getTaskPaths() {
        synchronized (registrations) {
            return registrations.stream().map(r -> r.taskPath).collect(Collectors.toList());
        }
    }

    public List<Provider<RegularFile>> getOutputProviders() {
        synchronized (registrations) {
            return registrations.stream().flatMap(r -> r.outputFiles.stream()).collect(Collectors.toList());
        }
    }

    @Override
    public void close() {}
}
