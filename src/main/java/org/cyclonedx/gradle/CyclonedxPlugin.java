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
import java.util.List;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * Entrypoint of the plugin. MUST be applied to every project that should contribute a BOM
 * (root + each subproject).
 */
public class CyclonedxPlugin implements Plugin<Project> {

    private static final Logger LOGGER = Logging.getLogger(CyclonedxPlugin.class);

    public static final String LOG_PREFIX = "[CycloneDX]";
    public static final String REGISTRATION_SERVICE_NAME = "cyclonedxRegistration";

    protected final String cyclonedxDirectTaskName;
    protected final String cyclonedxAggregateTaskName;
    protected final String cyclonedxDirectReportDir;
    protected final String cyclonedxAggregateReportDir;

    @Inject
    public CyclonedxPlugin() {
        this("cyclonedxDirectBom", "cyclonedxBom", "reports/cyclonedx-direct", "reports/cyclonedx");
    }

    protected CyclonedxPlugin(
            final String cyclonedxDirectTaskName,
            final String cyclonedxAggregateTaskName,
            final String cyclonedxDirectReportDir,
            final String cyclonedxAggregateReportDir) {
        this.cyclonedxDirectTaskName = cyclonedxDirectTaskName;
        this.cyclonedxAggregateTaskName = cyclonedxAggregateTaskName;
        this.cyclonedxDirectReportDir = cyclonedxDirectReportDir;
        this.cyclonedxAggregateReportDir = cyclonedxAggregateReportDir;
    }

    @Override
    public void apply(final Project project) {
        if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
            LOGGER.warn(
                    "warning: {} Support of Java versions prior to 17 is deprecated "
                            + "and will be removed in a future release.",
                    LOG_PREFIX);
        }

        final Provider<CyclonedxRegistrationService> serviceProvider = project.getGradle()
                .getSharedServices()
                .registerIfAbsent(REGISTRATION_SERVICE_NAME, CyclonedxRegistrationService.class, spec -> {});

        // Direct-BOM task for THIS project and self-registration into the service.
        registerCyclonedxDirectBomTask(project, serviceProvider);

        // Aggregate task
        registerCyclonedxAggregateBomTask(project, serviceProvider);
    }

    private void registerCyclonedxDirectBomTask(
            final Project project, final Provider<CyclonedxRegistrationService> serviceProvider) {

        if (project.getTasks().getNames().contains(cyclonedxDirectTaskName)) {
            LOGGER.info(
                    "{} Task [{}] already exists in project [{}], skipping creation",
                    LOG_PREFIX,
                    cyclonedxDirectTaskName,
                    project.getDisplayName());
            return;
        }

        final TaskProvider<CyclonedxDirectTask> directTask = project.getTasks()
                .register(cyclonedxDirectTaskName, CyclonedxDirectTask.class, task -> {
                    final Provider<Directory> dir =
                            project.getLayout().getBuildDirectory().dir(cyclonedxDirectReportDir);
                    task.getXmlOutput().convention(dir.map(d -> d.file("bom.xml")));
                    task.getJsonOutput().convention(dir.map(d -> d.file("bom.json")));
                    task.usesService(serviceProvider);
                });

        registerTaskWithService(project, serviceProvider, directTask);
    }

    private void registerTaskWithService(
            Project project,
            Provider<CyclonedxRegistrationService> serviceProvider,
            TaskProvider<CyclonedxDirectTask> directTask) {
        final String projectPath = project.getPath();
        final String taskPath = (":".equals(projectPath) ? "" : projectPath) + ":" + cyclonedxDirectTaskName;

        final List<Provider<RegularFile>> outputProviders = new ArrayList<>();
        outputProviders.add(directTask.flatMap(CyclonedxDirectTask::getJsonOutput));
        outputProviders.add(directTask.flatMap(CyclonedxDirectTask::getXmlOutput));

        serviceProvider
                .get()
                .register(new CyclonedxRegistrationService.Registration(taskPath, project.getPath(), outputProviders));
    }

    private void registerCyclonedxAggregateBomTask(
            final Project project, final Provider<CyclonedxRegistrationService> serviceProvider) {

        project.getTasks().register(cyclonedxAggregateTaskName, CyclonedxAggregateTask.class, task -> {
            task.usesService(serviceProvider);

            final Provider<Directory> dir =
                    project.getLayout().getBuildDirectory().dir(cyclonedxAggregateReportDir);
            task.getXmlOutput().convention(dir.map(d -> d.file("bom.xml")));
            task.getJsonOutput().convention(dir.map(d -> d.file("bom.json")));

            final String aggregateProjectPath = project.getPath();
            task.getProjectPath().convention(aggregateProjectPath);

            task.dependsOn((Callable<List<String>>)
                    () -> serviceProvider.get().getRegistrationsInSubtree(aggregateProjectPath).stream()
                            .map(r -> r.taskPath)
                            .collect(java.util.stream.Collectors.toList()));

            task.getInputSboms().from((Callable<List<RegularFile>>)
                    () -> serviceProvider.get().getRegistrationsInSubtree(aggregateProjectPath).stream()
                            .flatMap(r -> r.outputFiles.stream())
                            .map(Provider::getOrNull)
                            .filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toList()));
        });
    }
}
