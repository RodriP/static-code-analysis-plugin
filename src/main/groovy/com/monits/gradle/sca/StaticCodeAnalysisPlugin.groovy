/*
 * Copyright 2010-2017 Monits S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.monits.gradle.sca

import com.monits.gradle.sca.config.AnalysisConfigurator
import com.monits.gradle.sca.config.AndroidLintConfigurator
import com.monits.gradle.sca.config.CheckstyleConfigurator
import com.monits.gradle.sca.config.CpdConfigurator
import com.monits.gradle.sca.config.FindbugsConfigurator
import com.monits.gradle.sca.config.PmdConfigurator
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.util.GradleVersion

/**
 * Static code analysis plugin for Android and Java projects
*/
@CompileStatic
class StaticCodeAnalysisPlugin implements Plugin<Project> {
    private final static String EXTENSION_NAME = 'staticCodeAnalysis'
    private final static String DEFAULTS_LOCATION =
        'https://raw.githubusercontent.com/Monits/static-code-analysis-plugin/staging/defaults/'
    private final static String CHECKSTYLE_DEFAULT_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle.xml'
    private final static String CHECKSTYLE_CACHE_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle-cache.xml'
    private final static String CHECKSTYLE_BACKWARDS_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle-6.7.xml'
    private final static String PMD_DEFAULT_RULES = DEFAULTS_LOCATION + 'pmd/pmd.xml'
    private final static String PMD_DEFAULT_ANDROID_RULES = DEFAULTS_LOCATION + 'pmd/pmd-android.xml'
    private final static String PMD_BACKWARDS_RULES = DEFAULTS_LOCATION + 'pmd/pmd-5.1.3.xml'
    private final static String FINDBUGS_DEFAULT_SUPPRESSION_FILTER =
        DEFAULTS_LOCATION + 'findbugs/findbugs-exclusions.xml'
    private final static String FINDBUGS_DEFAULT_ANDROID_SUPPRESSION_FILTER =
        DEFAULTS_LOCATION + 'findbugs/findbugs-exclusions-android.xml'
    private final static String ANDROID_DEFAULT_RULES = DEFAULTS_LOCATION + 'android/android-lint.xml'
    private final static String PROVIDED = 'provided'

    private StaticCodeAnalysisExtension extension
    private Project project

    @CompileStatic(TypeCheckingMode.SKIP)
    @SuppressWarnings('UnnecessaryGetter')
    @Override
    void apply(Project project) {
        this.project = project
        extension = project.extensions.create(EXTENSION_NAME, StaticCodeAnalysisExtension)

        defineConfigurations()
        addFindbugsAnnotationDependencies()
        configureExtensionRule()

        project.afterEvaluate {
            addDepsButModulesToScaconfig project.configurations.compile
            addDepsButModulesToScaconfig project.configurations.testCompile

            // Apply Android Lint configuration
            // must be done in `afterEvaluate` for compatibility with android plugin [1.0, 1.3)
            withAndroidPlugins AndroidLintConfigurator

            if (extension.getFindbugs()) {
                withAndroidPlugins FindbugsConfigurator
                withPlugin(JavaBasePlugin, FindbugsConfigurator)
            }

            if (extension.getCheckstyle()) {
                withAndroidPlugins CheckstyleConfigurator
                withPlugin(JavaBasePlugin, CheckstyleConfigurator)
            }

            if (extension.getPmd()) {
                withAndroidPlugins PmdConfigurator
                withPlugin(JavaBasePlugin, PmdConfigurator)
            }

            if (extension.getCpd()) {
                withAndroidPlugins CpdConfigurator
                withPlugin(JavaBasePlugin, CpdConfigurator)
            }
        }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void defineConfigurations() {
        // Wait until the default configuration is available
        project.configurations.matching { Configuration config -> config.name == Dependency.DEFAULT_CONFIGURATION }
            .all { Configuration config ->
                project.configurations {
                    archives {
                        extendsFrom project.configurations.default
                    }
                }

                if (project.configurations.findByName(PROVIDED) == null) {
                    project.configurations {
                        provided {
                            description = 'Compile only dependencies'
                            dependencies.all { Dependency dep ->
                                project.configurations.default.exclude group:dep.group, module:dep.name
                            }
                        }
                        compile.extendsFrom provided
                    }
                }
            }

        project.configurations {
            scaconfig { // Custom configuration for static code analysis
                description = 'Configuration used for Static Code Analysis'
            }
            scaconfigModules { // Custom configuration for static code analysis
                description = 'Configuration used for Static Code Analysis containing only module dependencies'
            }
            androidLint { // Configuration used for android linters
                transitive = false
                description = 'Extra Android lint rules to be used'
            }
        }
    }

    // This should be done when actually configuring Findbugs, but can't be inside an afterEvaluate
    // See: https://code.google.com/p/android/issues/detail?id=208474
    @CompileStatic(TypeCheckingMode.SKIP)
    private void addFindbugsAnnotationDependencies() {
        // Wait until the provided configuration is available
        project.configurations.matching { Configuration config -> config.name == PROVIDED }
            .all { Configuration config ->
                project.dependencies {
                    provided('com.google.code.findbugs:annotations:' + ToolVersions.findbugsVersion) {
                        /*
                             * This jar both includes and depends on jcip and jsr-305. One is enough
                             * See https://github.com/findbugsproject/findbugs/issues/94
                             */
                        transitive = false
                    }
                }
            }
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    private void configureExtensionRule() {
        extension.conventionMapping.with {
            ignoreErrors = { true }
            findbugs = { true }
            pmd = { true }
            checkstyle = { true }
            cpd = { true }
            androidLint = { true }
            checkstyleRules = {
                if (ToolVersions.isLatestCheckstyleVersion()) {
                    return CHECKSTYLE_DEFAULT_RULES
                }

                if (ToolVersions.isCheckstyleCacheSupported()) {
                    return CHECKSTYLE_CACHE_RULES
                }

                CHECKSTYLE_BACKWARDS_RULES
            }
            pmdRules = {
                if (ToolVersions.isLatestPmdVersion()) {
                    return [PMD_DEFAULT_RULES, PMD_DEFAULT_ANDROID_RULES]
                }

                [PMD_BACKWARDS_RULES, PMD_DEFAULT_ANDROID_RULES]
            }
            androidLintConfig = { ANDROID_DEFAULT_RULES }
        }

        // default suppression filter for findbugs for Java - order is important, Android plugin applies Java
        withPlugin(JavaBasePlugin) {
            extension.conventionMapping.with {
                findbugsExclude = { FINDBUGS_DEFAULT_SUPPRESSION_FILTER }
            }
        }

        // default suppression filter for findbugs for Android
        withAndroidPlugins {
            extension.conventionMapping.with {
                findbugsExclude = { FINDBUGS_DEFAULT_ANDROID_SUPPRESSION_FILTER }
            }
        }

        extension.sourceSetConfig = project.container(RulesConfig) { String name ->
            new RulesConfig(name, extension)
        }
    }

    /**
     * Adds all dependencies except modules from given config to scaconfig.
     *
     * Modules are added to scaconfigModules, but transient dependencies are added.
     *
     * @param config The config whose dependencies are to be added to scaconfig / scaconfigModules
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    private void addDepsButModulesToScaconfig(final Configuration config) {
        // support lazy dependency configuration
        config.allDependencies.all {
            if (it in ProjectDependency) {
                project.dependencies.scaconfigModules it

                // support lazy configuration creation
                it.dependencyProject.configurations.all { c ->
                    // Deal with changing APIs from Gradle...
                    String targetConfiguration
                    if (GradleVersion.current() >= GradleVersion.version('3.2')) {
                        targetConfiguration = it.targetConfiguration ?: Dependency.DEFAULT_CONFIGURATION
                    } else {
                        targetConfiguration = it.configuration
                    }

                    // take transitive dependencies
                    if (c.name == targetConfiguration) {
                        addDepsButModulesToScaconfig(c)
                    }
                }
            } else {
                // TODO : This includes @aar packages that aren't understood by our tools. Filter them?
                project.dependencies.scaconfig it
            }
        }
    }

    private void withAndroidPlugins(final Class<? extends AnalysisConfigurator> configClass) {
        AnalysisConfigurator configurator = configClass.newInstance(new Object[0])
        Action<? extends Plugin> configureAction = { configurator.applyAndroidConfig(project, extension) }

        withAndroidPlugins configureAction
    }

    private void withPlugin(final Class<? extends Plugin> pluginClass,
                            final Class<? extends AnalysisConfigurator> configClass) {
        AnalysisConfigurator  configurator = configClass.newInstance(new Object[0])
        Action<? extends Plugin> configureAction = { configurator.applyConfig(project, extension) }

        withPlugin(pluginClass, configureAction)
    }

    private void withPlugin(final Class<? extends Plugin> pluginClass, final Action<? extends Plugin> configureAction) {
        project.plugins.withType(pluginClass, configureAction)
    }

    private void withAndroidPlugins(final Action<? extends Plugin> configureAction) {
        withOptionalPlugin('com.android.build.gradle.AppPlugin', configureAction)
        withOptionalPlugin('com.android.build.gradle.LibraryPlugin', configureAction)
    }

    @SuppressWarnings(['ClassForName', 'EmptyCatchBlock'])
    private void withOptionalPlugin(final String pluginClassName, final Action<? extends Plugin> configureAction) {
        try {
            // Will most likely throw a ClassNotFoundException
            Class<?> pluginClass = Class.forName(pluginClassName)
            if (Plugin.isAssignableFrom(pluginClass)) {
                withPlugin(pluginClass as Class<? extends Plugin>, configureAction)
            }
        } catch (ClassNotFoundException ignored) {
            // do nothing
        }
    }
}
