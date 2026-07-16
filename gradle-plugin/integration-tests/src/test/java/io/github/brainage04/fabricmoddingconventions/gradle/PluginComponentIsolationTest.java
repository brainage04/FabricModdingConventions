package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginComponentIsolationTest {
    private static final String BASE_PLUGIN_ID = "io.github.brainage04.fabric-mod-conventions";
    private static final String RECORDER_PLUGIN_ID = "io.github.brainage04.client-gametest-recorder";
    private static final String PRODUCTION_PLUGIN_ID = "io.github.brainage04.production-gametests";
    private static final String WORKSPACE_PLUGIN_ID = "io.github.brainage04.workspace-dependencies";

    @TempDir
    Path projectDir;

    @Test
    void basePluginAppliesOnlyLoom() throws IOException {
        writeLoomFixture(BASE_PLUGIN_ID, """
                tasks.register('verifyBaseIsolation') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') == null
                        assert project.extensions.findByName('productionGameTests') == null
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyBaseIsolation");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyBaseIsolation").getOutcome());
    }

    @Test
    void recorderPluginOwnsOnlyRecorderFeatures() throws IOException {
        writeLoomFixture(RECORDER_PLUGIN_ID, """
                tasks.register('verifyRecorderIsolation') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') != null
                        assert project.extensions.findByName('productionGameTests') == null
                        assert project.tasks.findByName('prepareClientGameTestRun') != null
                        assert project.tasks.findByName('recordClientGameTest') != null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyRecorderIsolation");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyRecorderIsolation").getOutcome());
    }

    @Test
    void productionPluginIsStandaloneAndRegistersEnabledTasks() throws IOException {
        writeLoomFixture(PRODUCTION_PLUGIN_ID, """

                productionGameTests {
                    includeFabricApiDependency = false
                    runtimeLibraryDependencies.add("example:runtime-library:1.0")
                }

                tasks.register('verifyProductionIsolation') {
                    doLast {
                        assert project.extensions.findByName('clientGameTestRecorder') == null
                        assert project.extensions.findByName('productionGameTests') != null
                        assert project.sourceSets.findByName('gametest') != null
                        assert project.loom.mods.findByName('fixturemod-gametest') != null
                        assert project.tasks.findByName('runGameTest') != null
                        assert project.tasks.findByName('runClientGameTest') != null
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') != null
                        assert project.tasks.findByName('runProductionServerGameTest') != null
                        assert project.tasks.findByName('runAllProductionGameTests') != null
                        assert project.tasks.findByName('productionGameTestJar') instanceof org.gradle.jvm.tasks.Jar
                        assert project.tasks.findByName('prepareProductionGameTestRuns') instanceof io.github.brainage04.fabricmoddingconventions.gradle.production.PrepareProductionGameTestRunsTask
                        assert project.configurations.productionGameTestRuntimeLibraries.dependencies.any {
                            it.group == 'example' && it.name == 'runtime-library' && it.version == '1.0'
                        }
                        assert project.tasks.named('runProductionClientGameTest').get().mods.files.any {
                            it.name.endsWith('-production-gametest.jar')
                        }
                        assert project.tasks.named('runProductionServerGameTest').get().mods.files.any {
                            it.name.endsWith('-production-gametest.jar')
                        }
                        assert project.tasks.named('runProductionClientGameTest').get().jvmArgs.get()
                                .contains('-Dfabricmoddingconventions.clientGameTest=true')
                    }
                }
                """);

        Path gameTestDescriptor = projectDir.resolve("src/gametest/resources/fabric.mod.json");
        Files.createDirectories(gameTestDescriptor.getParent());
        Files.writeString(gameTestDescriptor, "{\"id\":\"${mod_id}-gametest\",\"version\":\"${mod_version}\"}");

        var result = runGradle(
                "prepareProductionGameTestRuns",
                "productionGameTestJar",
                "verifyProductionIsolation"
        );

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyProductionIsolation").getOutcome());
        assertEquals(
                "eula=true\n",
                Files.readString(projectDir.resolve("build/run/productionClientGameTest/eula.txt"))
        );
        assertEquals(
                "eula=true\n",
                Files.readString(projectDir.resolve("build/run/productionServerGameTest/eula.txt"))
        );
        try (var files = Files.list(projectDir.resolve("build/libs"))) {
            Path gameTestJar = files
                    .filter(path -> path.getFileName().toString().endsWith("-production-gametest.jar"))
                    .findFirst()
                    .orElseThrow();
            try (var jar = new JarFile(gameTestJar.toFile())) {
                assertEquals(
                        "{\"id\":\"fixturemod-gametest\",\"version\":\"1.2.3\"}",
                        new String(
                                jar.getInputStream(jar.getJarEntry("fabric.mod.json")).readAllBytes(),
                                StandardCharsets.UTF_8
                        )
                );
            }
        }
    }


    @Test
    void workspacePluginAddsCentralWithoutLoomOrFeatureTasks() throws IOException {
        writeFixture(WORKSPACE_PLUGIN_ID, false, """
                tasks.register('verifyWorkspaceRepository') {
                    doLast {
                        assert project.repositories.any { it.hasProperty('url') && it.url.toString().contains('repo.maven.apache.org/maven2') }
                        assert !project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.tasks.findByName('prepareClientGameTestRun') == null
                        assert project.tasks.findByName('runProductionClientGameTest') == null
                    }
                }
                """);

        var result = runGradle("verifyWorkspaceRepository");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyWorkspaceRepository").getOutcome());
    }



    @Test
    void leafPluginsApplySharedBaseWithoutDuplicates() throws IOException {
        writeLoomFixture(RECORDER_PLUGIN_ID + "'\n    id '" + PRODUCTION_PLUGIN_ID, """
                tasks.register('verifyIdempotentComponents') {
                    doLast {
                        assert project.plugins.hasPlugin('net.fabricmc.fabric-loom')
                        assert project.extensions.findByName('clientGameTestRecorder') != null
                        assert project.extensions.findByName('productionGameTests') != null
                        assert project.tasks.findAll { it.name == 'prepareClientGameTestRun' }.size() == 1
                        assert project.tasks.findAll { it.name == 'recordClientGameTest' }.size() == 1
                        assert project.configurations.gametestImplementation.dependencies.any {
                            it.group == 'io.github.brainage04'
                                    && it.name == 'fabricmoddingconventions'
                                    && it.version == 'fixture-version'
                        }
                        assert project.configurations.productionRuntimeMods.dependencies.any {
                            it.group == 'io.github.brainage04'
                                    && it.name == 'fabricmoddingconventions'
                                    && it.version == 'fixture-version'
                        }
                    }
                }
                """);

        var result = runGradle("verifyIdempotentComponents");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyIdempotentComponents").getOutcome());
    }

    @Test
    void preparationTaskWritesDeterministicOptionsFromExtensionSettings() throws IOException {
        writeLoomFixture(RECORDER_PLUGIN_ID, """
                clientGameTestRecorder {
                    recordingAudioDeviceProperty = 'fixtureRecordingAudioDevice'
                    minecraftOptionsVersion = '9999'
                    maxFps = '144'
                    renderDistance = '12'
                    simulationDistance = '8'
                    guiScale = '3'
                    fullscreen = 'false'
                }
                """);

        var result = runGradle("prepareClientGameTestRun", "-PfixtureRecordingAudioDevice=pipewire.monitor");

        assertEquals(TaskOutcome.SUCCESS, result.task(":prepareClientGameTestRun").getOutcome());
        assertEquals(expectedOptions(), Files.readString(projectDir.resolve("build/run/clientGameTest/options.txt")));
    }

    private BuildResult runGradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    private void writeLoomFixture(String pluginIds, String configuration) throws IOException {
        writeFixture(pluginIds, true, configuration);
    }

    private void writeFixture(String pluginIds, boolean includeLoomDependencies, String configuration) throws IOException {
        String repositories = "";
        String dependencies = "";
        String properties = includeLoomDependencies
                ? """
                mod_side=both
                mod_id=fixturemod
                mod_name=Fixture Mod
                mod_version=1.2.3
                maven_group=io.github.brainage04.fixture
                archives_base_name=fixturemod
                minecraft_version=26.1.2
                loader_version=0.19.2
                fabric_api_version=0.146.1+26.1.2
                fabricmoddingconventions_version=fixture-version
                java_version=25
                """
                : "java_version=25\n";
        Files.writeString(projectDir.resolve("gradle.properties"), properties);
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'recorder-convention-fixture'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id '%s'
                }

                %s
                %s
                %s
                """.formatted(pluginIds, repositories, dependencies, configuration));
    }

    private static String expectedOptions() {
        return String.join(System.lineSeparator(),
                "version:9999",
                "ao:false",
                "autoJump:false",
                "biomeBlendRadius:0",
                "chunkSectionFadeInTime:0.0",
                "enableVsync:false",
                "entityDistanceScaling:0.5",
                "entityShadows:false",
                "fullscreen:false",
                "graphicsPreset:\"fast\"",
                "guiScale:3",
                "improvedTransparency:false",
                "maxAnisotropyBit:1",
                "maxFps:144",
                "menuBackgroundBlurriness:0",
                "mipmapLevels:0",
                "narrator:0",
                "narratorHotkey:false",
                "particles:2",
                "prioritizeChunkUpdates:0",
                "renderClouds:\"false\"",
                "renderDistance:12",
                "simulationDistance:8",
                "soundDevice:\"pipewire.monitor\"",
                "soundCategory_master:1.0",
                "soundCategory_music:0.0",
                "toggleSprint:false",
                "weatherRadius:5",
                ""
        );
    }
}
