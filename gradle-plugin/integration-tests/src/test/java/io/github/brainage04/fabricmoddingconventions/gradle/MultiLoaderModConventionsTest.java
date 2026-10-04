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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        ['fabric', 'neoforge'].each { path ->
                            assert !project(":$path").configurations.productionRuntimeMods.allDependencies
                                    .any { it.group == 'me.djtheredstoner' }
                        }
                        assert project(':fabric').tasks.findByName('runClientGameTest') == null
                        assert project(':fabric').tasks.findByName('runAllProductionGameTests') == null
                        assert project(':neoforge').tasks.findByName('runGameTest') != null
                        assert project(':neoforge').tasks.findByName('runGameTestServer') == null
                        assert tasks.findByName('runAllGameTests').taskDependencies.getDependencies(null)*.path.toSet() ==
                                [':fabric:runGameTest', ':neoforge:runGameTest', ':fabric:runProductionServerGameTest',
                                 ':fabric:runProductionClientGameTest', ':neoforge:runProductionServerGameTest'].toSet()
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

        assertEquals(
                List.of(":fabric:runGameTest", ":neoforge:runGameTest", ":fabric:runProductionServerGameTest",
                        ":fabric:runProductionClientGameTest", ":neoforge:runProductionServerGameTest"),
                plannedGameTestRuns()
        );
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
        assertEquals(
                List.of(":fabric:runGameTest", ":neoforge:runGameTest", ":fabric:runProductionServerGameTest",
                        ":neoforge:runProductionServerGameTest"),
                plannedGameTestRuns()
        );
    }

    @Test
    void fabricServerGameTestsDeleteOnlyTheirConfiguredWorldBeforeLaunch() throws IOException {
        writeFixture("server", """
                gradle.projectsEvaluated {
                    def fabric = project(':fabric')
                    fabric.loom.runs.gameTest.runDir = 'custom/development'
                    fabric.tasks.named('runProductionServerGameTest').get().runDir
                            .set(fabric.layout.projectDirectory.dir('custom/production'))
                    tasks.register('verifyFreshFabricGameTestWorlds') {
                        doLast {
                            ['runGameTest': 'development', 'runProductionServerGameTest': 'production'].each { name, dir ->
                                def runTask = fabric.tasks.named(name).get()
                                def world = fabric.file("custom/$dir/world")
                                assert new File(world, 'entities/stale.marker').isFile()
                                2.times {
                                    // Exercise the named reset only; inherited task actions can launch Minecraft.
                                    runTask.actions.findAll { it.displayName.endsWith('resetGameTestWorld') }.each { action ->
                                        assert action == runTask.actions.first() : 'World reset must precede server launch'
                                        action.execute(runTask)
                                    }
                                    assert !world.exists() : "Saved world survived ${runTask.path}"
                                    if (it == 0) {
                                        new File(world, 'entities').mkdirs()
                                        new File(world, 'entities/stale.marker').text = 'interrupted rerun'
                                    }
                                }
                            }
                        }
                    }
                }
                """);
        for (String dir : new String[] {"development", "production"}) {
            write("fabric/custom/" + dir + "/world/entities/stale.marker", "interrupted run");
            for (String path : new String[] {"logs/keep.log", "options.txt", "eula.txt", "server.properties"}) {
                write("fabric/custom/" + dir + "/" + path, "keep " + path);
            }
        }
        write("fabric/run/world/keep.marker", "ordinary server world");

        BuildResult result = runGradle("verifyFreshFabricGameTestWorlds");

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyFreshFabricGameTestWorlds").getOutcome());
        for (String dir : new String[] {"development", "production"}) {
            Path runDir = projectDir.resolve("fabric/custom/" + dir);
            assertFalse(Files.exists(runDir.resolve("world")));
            for (String path : new String[] {"logs/keep.log", "options.txt", "eula.txt", "server.properties"}) {
                assertEquals("keep " + path, Files.readString(runDir.resolve(path)));
            }
        }
        assertEquals("ordinary server world", Files.readString(projectDir.resolve("fabric/run/world/keep.marker")));
    }

    @Test
    void neoForgeProductionServerRunsTheGameTestsAgainstTheReleaseJarAndFailsOnAFailedTest() throws IOException {
        writeFixture("server", "");
        writeNeoForgeMod();

        BuildResult result = runner(":neoforge:runProductionServerGameTest").buildAndFail();

        assertEquals(TaskOutcome.SUCCESS, result.task(":neoforge:installProductionServer").getOutcome());
        assertEquals(TaskOutcome.FAILED, result.task(":neoforge:runProductionServerGameTest").getOutcome());
        String output = result.getOutput();
        assertTrue(output.contains("1 required tests failed"), output);
        assertTrue(output.contains("fixturemod:fails: deliberately broken"), output);
        assertTrue(output.contains("NeoForge production server GameTests failed with exit code 1"), output);

        // The server loaded the release JAR and the GameTest JAR as one mod and ran both of its tests.
        String report = Files.readString(projectDir.resolve(
                "neoforge/build/test-results/runProductionServerGameTest/TEST-gametest.xml"));
        assertTrue(report.matches("(?s).*name=\"fixturemod:passes\" time=\"[0-9.]+\"/>.*"), report);
        assertTrue(report.matches("(?s).*name=\"fixturemod:fails\" time=\"[0-9.]+\"><failure message=\"[^\"]*deliberately broken.*"),
                report);
        assertTrue(Files.isRegularFile(projectDir.resolve(
                "neoforge/build/run/productionServerGameTest/mod-under-test/fixturemod-neoforge-1.2.3.jar")));
        assertTrue(Files.isRegularFile(projectDir.resolve(
                "neoforge/build/run/productionServerGameTest/logs/latest.log")));
    }

    @Test
    void clientModWithoutNeoForgeGameTestsSkipsTheProductionServerRunWithoutInstallingAServer() throws IOException {
        writeFixture("client", """
                multiLoaderModConventions {
                    neoForgeGameTests = false
                }
                """);

        BuildResult result = runGradle(":neoforge:runProductionServerGameTest");

        assertEquals(TaskOutcome.SKIPPED, result.task(":neoforge:installProductionServer").getOutcome());
        assertEquals(TaskOutcome.SKIPPED, result.task(":neoforge:runProductionServerGameTest").getOutcome());
        assertFalse(Files.exists(projectDir.resolve("neoforge/build/fabricmoddingconventions/neoforge-server")));
        // No Fabric server GameTests for a client mod; the NeoForge runs stay in the plan and are skipped.
        assertEquals(
                List.of(":neoforge:runGameTest", ":fabric:runProductionClientGameTest",
                        ":neoforge:runProductionServerGameTest"),
                plannedGameTestRuns()
        );
    }

    @Test
    void runAllGameTestsLeavesOutTheFabricDevelopmentRunWithFabricServerGameTestsOff() throws IOException {
        writeFixture("both", """
                multiLoaderModConventions {
                    fabricServerGameTests = false
                }
                """);

        assertEquals(
                List.of(":neoforge:runGameTest", ":fabric:runProductionClientGameTest",
                        ":neoforge:runProductionServerGameTest"),
                plannedGameTestRuns()
        );
    }

    /** A NeoForge mod whose GameTest source set registers one passing and one failing test. */
    private void writeNeoForgeMod() throws IOException {
        write("neoforge/src/main/resources/META-INF/neoforge.mods.toml", """
                modLoader = "javafml"
                loaderVersion = "[1,)"
                license = "MIT"

                [[mods]]
                modId = "fixturemod"
                version = "${version}"
                displayName = "Fixture Mod"
                """);
        write("neoforge/src/main/java/fixture/FixtureMod.java", """
                package fixture;

                import net.neoforged.fml.common.Mod;

                @Mod("fixturemod")
                public final class FixtureMod {
                }
                """);
        write("neoforge/src/gametest/java/fixture/FixtureGameTests.java", """
                package fixture;

                import java.util.function.Consumer;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.gametest.framework.GameTestHelper;
                import net.minecraft.resources.Identifier;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.fml.common.EventBusSubscriber;
                import net.neoforged.neoforge.registries.RegisterEvent;

                @EventBusSubscriber(modid = "fixturemod")
                public final class FixtureGameTests {
                    @SubscribeEvent
                    public static void register(RegisterEvent event) {
                        register(event, "passes", GameTestHelper::succeed);
                        register(event, "fails", helper -> helper.fail("deliberately broken"));
                    }

                    private static void register(RegisterEvent event, String name, Consumer<GameTestHelper> test) {
                        event.register(BuiltInRegistries.TEST_FUNCTION.key(),
                                Identifier.fromNamespaceAndPath("fixturemod", name), () -> test);
                    }
                }
                """);
        for (String name : new String[] {"passes", "fails"}) {
            write("neoforge/src/gametest/resources/data/fixturemod/test_instance/" + name + ".json", """
                    {
                      "type": "minecraft:function",
                      "environment": "minecraft:default",
                      "structure": "fabricmoddingconventions:empty",
                      "max_ticks": 20,
                      "function": "fixturemod:%s"
                    }
                    """.formatted(name));
        }
    }

    private void write(String path, String content) throws IOException {
        Path file = projectDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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

    /**
     * The GameTest runs {@code runAllGameTests} plans, in execution order. With {@code --parallel} the runs of
     * both loaders could start together; the plan has to keep them one at a time, development runs first.
     */
    private List<String> plannedGameTestRuns() {
        return runGradle("runAllGameTests", "--dry-run", "--parallel").getOutput().lines()
                .map(line -> line.split(" ")[0])
                .filter(path -> path.matches(":(fabric|neoforge):run\\w*GameTest"))
                .toList();
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
