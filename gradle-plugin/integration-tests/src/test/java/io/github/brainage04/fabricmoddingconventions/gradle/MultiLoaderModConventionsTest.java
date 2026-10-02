package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLoaderModConventionsTest {
    private static final String STRUCTURE = "data/fabricmoddingconventions/structure/empty.nbt";
    private static final String VERSION = System.getProperty("pluginTestVersion");

    @TempDir
    Path projectDir;

    @Test
    void clientModGetsDevAuthOnlyInTheDevRuntimeAndTheSharedStructureInBothGameTestOutputs() throws IOException {
        writeFixture("both", """
                subprojects {
                    if (path != ':common') {
                        apply plugin: 'maven-publish'
                        publishing { publications { create('mavenJava', MavenPublication) { from components.java } } }
                    }
                }

                tasks.register('verifyDevRuntime') {
                    doLast {
                        ['fabric': 'DevAuth-fabric', 'neoforge': 'DevAuth-neoforge'].each { path, artifact ->
                            def loader = project(":$path")
                            def isDevAuth = { it.group == 'me.djtheredstoner' && it.name == artifact && it.version == '1.2.2' }
                            assert loader.configurations.runtimeClasspath.allDependencies.any(isDevAuth)
                            assert !loader.configurations.runtimeElements.allDependencies.any(isDevAuth)
                            assert !loader.configurations.apiElements.allDependencies.any(isDevAuth)
                        }
                        assert !project(':fabric').configurations.productionRuntimeMods.allDependencies
                                .any { it.group == 'me.djtheredstoner' }
                        assert project(':fabric').tasks.findByName('runClientGameTest') == null
                        assert project(':fabric').tasks.findByName('runAllProductionGameTests') == null
                        assert project(':neoforge').tasks.findByName('runGameTest') != null
                        assert project(':neoforge').tasks.findByName('runGameTestServer') == null
                        assert tasks.findByName('runAllGameTests').taskDependencies.getDependencies(null)*.path.toSet() ==
                                [':fabric:runProductionServerGameTest', ':fabric:runProductionClientGameTest',
                                 ':neoforge:runGameTest'].toSet()
                        ['runFabricClient', 'runNeoForgeClient', 'runClientGameTest', 'recordClientGameTest',
                         'runNeoForgeGameTests', 'runAllProductionGameTests'].each { assert tasks.findByName(it) == null }
                    }
                }
                """);

        BuildResult result = runGradle(
                "verifyDevRuntime",
                ":fabric:generatePomFileForMavenJavaPublication",
                ":fabric:generateMetadataFileForMavenJavaPublication",
                ":neoforge:generatePomFileForMavenJavaPublication",
                ":neoforge:generateMetadataFileForMavenJavaPublication",
                ":fabric:processGametestResources",
                ":neoforge:processGametestResources"
        );

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyDevRuntime").getOutcome());
        for (String loader : new String[] {"fabric", "neoforge"}) {
            Path publication = projectDir.resolve(loader + "/build/publications/mavenJava");
            String pom = Files.readString(publication.resolve("pom-default.xml"));
            String module = Files.readString(publication.resolve("module.json"));
            assertFalse(pom.toLowerCase().contains("devauth"), pom);
            assertFalse(module.toLowerCase().contains("devauth"), module);

            Path structure = projectDir.resolve(loader + "/build/resources/gametest/" + STRUCTURE);
            assertTrue(Files.isRegularFile(structure), structure + " is missing");
            assertArrayEquals(EMPTY_8X8X8_SIZE_TAG, sizeTag(structure));
            assertFalse(Files.exists(projectDir.resolve(loader + "/build/resources/main/" + STRUCTURE)));
        }
    }

    @Test
    void serverModWithoutClientGameTestsHasNoDevAuthAndANoOpProductionClientGameTestTask() throws IOException {
        writeFixture("server", """
                multiLoaderModConventions {
                    fabricClientGameTests = false
                }

                tasks.register('verifyNoDevAuth') {
                    doLast {
                        ['fabric', 'neoforge'].each { path ->
                            def loader = project(":$path")
                            assert !loader.configurations.collectMany { it.dependencies }.any { it.group == 'me.djtheredstoner' }
                            assert loader.repositories.findByName('DevAuth') == null
                        }
                    }
                }
                """);

        BuildResult result = runGradle(
                ":fabric:runProductionClientGameTest",
                ":fabric:recordClientGameTest",
                "verifyNoDevAuth"
        );

        // Only the placeholder and the skipped recorder run: no GameTest JAR, run preparation or client launch.
        assertEquals(
                Set.of(":fabric:runProductionClientGameTest", ":fabric:recordClientGameTest"),
                result.getTasks().stream()
                        .map(BuildTask::getPath)
                        .filter(path -> path.contains("GameTest"))
                        .collect(Collectors.toSet())
        );
        assertEquals(TaskOutcome.SKIPPED, result.task(":fabric:recordClientGameTest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyNoDevAuth").getOutcome());
    }

    @Test
    void commonRunTasksStopTheBuildAndPointAtTheLoaders() throws IOException {
        writeFixture("both", "");

        BuildResult result = runner(":common:runClient").buildAndFail();

        assertTrue(result.getOutput().contains(
                ":common:runClient does nothing: common has no loader. Run :fabric:runClient or :neoforge:runClient instead."
        ), result.getOutput());
        assertNull(result.task(":common:runClient"));
    }

    /** NBT bytes of the structure's {@code size} int list: [8, 8, 8]. */
    private static final byte[] EMPTY_8X8X8_SIZE_TAG = {
            9, 0, 4, 's', 'i', 'z', 'e', 3, 0, 0, 0, 3, 0, 0, 0, 8, 0, 0, 0, 8, 0, 0, 0, 8
    };

    private static byte[] sizeTag(Path structure) throws IOException {
        byte[] nbt;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(structure))) {
            nbt = input.readAllBytes();
        }
        for (int index = 0; index + EMPTY_8X8X8_SIZE_TAG.length <= nbt.length; index++) {
            if (nbt[index] == 9 && nbt[index + 1] == 0 && nbt[index + 2] == 4 && nbt[index + 3] == 's'
                    && nbt[index + 4] == 'i' && nbt[index + 5] == 'z' && nbt[index + 6] == 'e') {
                byte[] tag = new byte[EMPTY_8X8X8_SIZE_TAG.length];
                System.arraycopy(nbt, index, tag, 0, tag.length);
                return tag;
            }
        }
        return new byte[0];
    }

    private BuildResult runGradle(String... arguments) {
        return runner(arguments).build();
    }

    private GradleRunner runner(String... arguments) {
        // The published plugin is used rather than withPluginClasspath(): the test classpath also carries
        // fabric-mod-conventions' Fabric Loom, which shadows the Architectury Loom this plugin needs.
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments);
    }

    private void writeFixture(String modSide, String configuration) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        exclusiveContent {
                            forRepository { maven { url = uri('%s') } }
                            filter {
                                includeGroup('io.github.brainage04')
                                includeGroup('io.github.brainage04.multiloader-mod-conventions')
                            }
                        }
                        mavenCentral()
                        gradlePluginPortal()
                        maven { url = 'https://maven.fabricmc.net/' }
                        maven { url = 'https://maven.architectury.dev/' }
                        maven { url = 'https://maven.neoforged.net/releases/' }
                    }
                }
                rootProject.name = 'multiloader-fixture'
                include 'common', 'fabric', 'neoforge'
                """.formatted(Path.of(System.getProperty("pluginTestRepository")).toUri()));
        Files.writeString(projectDir.resolve("gradle.properties"), """
                mod_side=%s
                mod_id=fixturemod
                mod_name=Fixture Mod
                mod_version=1.2.3
                maven_group=io.github.brainage04.fixture
                archives_base_name=fixturemod
                minecraft_version=26.2
                loader_version=0.19.3
                fabric_api_version=0.156.0+26.2
                neoforge_version=26.2.0.88
                fabricmoddingconventions_version=%s
                java_version=25
                """.formatted(modSide, VERSION));
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.github.brainage04.multiloader-mod-conventions' version '%s'
                }

                %s
                """.formatted(VERSION, configuration));
        for (String module : new String[] {"common", "fabric", "neoforge"}) {
            Files.createDirectories(projectDir.resolve(module));
        }
        Files.writeString(projectDir.resolve("neoforge/gradle.properties"), "loom.platform=neoforge\n");
    }
}
