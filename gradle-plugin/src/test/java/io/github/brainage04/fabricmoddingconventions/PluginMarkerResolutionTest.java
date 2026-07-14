package io.github.brainage04.fabricmoddingconventions;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMarkerResolutionTest {
    private static final String GROUP = "io.github.brainage04";
    private static final String VERSION = "2.1.0";
    private static final String IMPLEMENTATION_ARTIFACT = "fabricmoddingconventions-gradle";
    private static final List<String> PLUGIN_IDS = List.of(
            "io.github.brainage04.fabric-mod-conventions",
            "io.github.brainage04.client-gametest-recorder",
            "io.github.brainage04.production-gametests",
            "io.github.brainage04.workspace-dependencies",
            "io.github.brainage04.maven-central-publishing",
            "io.github.brainage04.mod-publishing"
    );

    @TempDir
    Path projectDirectory;

    @Test
    void resolvesEveryMarkerFromThePublishedMavenRepository() throws IOException {
        Path repository = publishedRepository();
        Files.writeString(projectDirectory.resolve("settings.gradle"), settingsFile(repository));
        Files.writeString(projectDirectory.resolve("gradle.properties"), "java_version=25\n");
        Files.writeString(projectDirectory.resolve("build.gradle"), consumerBuildFile());

        var result = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withArguments("assertPublishedMarkers", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("Resolved all published component markers"));
    }

    @Test
    void publishedMarkersUseCentralCoordinatesForDedicatedImplementation() throws Exception {
        Path repository = publishedRepository();
        for (String pluginId : PLUGIN_IDS) {
            String markerArtifact = pluginId + ".gradle.plugin";
            Path markerPom = repository
                    .resolve(pluginId.replace('.', '/'))
                    .resolve(markerArtifact)
                    .resolve(VERSION)
                    .resolve(markerArtifact + "-" + VERSION + ".pom");
            assertTrue(Files.isRegularFile(markerPom), () -> "Missing marker POM: " + markerPom);

            var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(markerPom.toFile());
            var dependencies = document.getElementsByTagName("dependency");
            assertEquals(1, dependencies.getLength(), () -> "Unexpected marker dependencies: " + markerPom);
            var dependency = (Element) dependencies.item(0);
            assertEquals(GROUP, childText(dependency, "groupId"));
            assertEquals(IMPLEMENTATION_ARTIFACT, childText(dependency, "artifactId"));
            assertEquals(VERSION, childText(dependency, "version"));
        }
    }

    private static Path publishedRepository() {
        return Path.of(System.getProperty("fabricConventionsTestRepository"));
    }

    private static String childText(Element element, String name) {
        return element.getElementsByTagName(name).item(0).getTextContent();
    }

    private static String settingsFile(Path repository) {
        String exclusiveGroups = PLUGIN_IDS.stream()
                .map(id -> "                    includeGroup('" + id + "')")
                .reduce("                    includeGroup('" + GROUP + "')", (left, right) -> left + "\n" + right);
        return """
                pluginManagement {
                    repositories {
                        exclusiveContent {
                            forRepository {
                                maven { url = uri('%s') }
                            }
                            filter {
                %s
                            }
                        }
                        mavenCentral()
                        gradlePluginPortal()
                        maven { url = 'https://maven.fabricmc.net/' }
                    }
                }
                rootProject.name = 'published-marker-consumer'
                """.formatted(repository.toUri(), exclusiveGroups);
    }

    private static String consumerBuildFile() {
        String pluginDeclarations = PLUGIN_IDS.stream()
                .map(id -> "    id '" + id + "' version '" + VERSION + "'")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String pluginAssertions = PLUGIN_IDS.stream()
                .map(id -> "        assert pluginManager.hasPlugin('" + id + "')")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        return """
                plugins {
                %s
                }

                repositories {
                    mavenCentral()
                    maven { url = 'https://libraries.minecraft.net' }
                    maven { url = 'https://maven.fabricmc.net/' }
                }

                dependencies {
                    minecraft 'com.mojang:minecraft:26.1.2'
                    implementation 'net.fabricmc:fabric-loader:0.19.2'
                }

                tasks.register('assertPublishedMarkers') {
                    doLast {
                %s
                        println 'Resolved all published component markers'
                    }
                }
                """.formatted(pluginDeclarations, pluginAssertions);
    }
}
