package io.github.brainage04.fabricmoddingconventions.gradle;

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
    private static final String VERSION = System.getProperty("pluginTestVersion");
    private static final List<PluginCoordinate> PLUGINS = List.of(
            new PluginCoordinate("io.github.brainage04.fabric-mod-conventions", "fabric-mod-conventions-gradle"),
            new PluginCoordinate("io.github.brainage04.client-gametest-recorder", "client-gametest-recorder-gradle"),
            new PluginCoordinate("io.github.brainage04.production-gametests", "production-gametests-gradle"),
            new PluginCoordinate("io.github.brainage04.workspace-dependencies", "workspace-dependencies-gradle"),
            new PluginCoordinate("io.github.brainage04.maven-central-publishing", "maven-central-publishing-gradle"),
            new PluginCoordinate("io.github.brainage04.mod-publishing", "mod-publishing-gradle")
    );

    @TempDir
    Path projectDirectory;

    @Test
    void resolvesEveryMarkerFromThePublishedMavenRepository() throws IOException {
        Path repository = publishedRepository();
        Files.writeString(projectDirectory.resolve("settings.gradle"), settingsFile(repository));
        Files.writeString(projectDirectory.resolve("gradle.properties"), """
                mod_side=both
                mod_id=publishedmarkerfixture
                mod_name=Published Marker Fixture
                mod_version=1.0.0
                maven_group=io.github.brainage04.fixture
                archives_base_name=publishedmarkerfixture
                minecraft_version=26.1.2
                loader_version=0.19.2
                fabric_api_version=0.146.1+26.1.2
                fabricmoddingconventions_version=%s
                java_version=25
                """.formatted(VERSION));
        Files.writeString(projectDirectory.resolve("build.gradle"), consumerBuildFile());

        var result = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withArguments("assertPublishedMarkers", "--stacktrace")
                .build();

        assertTrue(result.getOutput().contains("Resolved all published component markers"));
    }

    @Test
    void publishedMarkersUseTheirDedicatedImplementationCoordinates() throws Exception {
        Path repository = publishedRepository();
        for (PluginCoordinate plugin : PLUGINS) {
            String markerArtifact = plugin.id() + ".gradle.plugin";
            Path markerPom = repository
                    .resolve(plugin.id().replace('.', '/'))
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
            assertEquals(plugin.implementationArtifact(), childText(dependency, "artifactId"));
            assertEquals(VERSION, childText(dependency, "version"));
        }
    }

    private static Path publishedRepository() {
        return Path.of(System.getProperty("pluginTestRepository"));
    }

    private static String childText(Element element, String name) {
        return element.getElementsByTagName(name).item(0).getTextContent();
    }

    private static String settingsFile(Path repository) {
        String exclusiveGroups = PLUGINS.stream()
                .map(PluginCoordinate::id)
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
        String pluginDeclarations = PLUGINS.stream()
                .map(PluginCoordinate::id)
                .map(id -> "    id '" + id + "' version '" + VERSION + "'")
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String pluginAssertions = PLUGINS.stream()
                .map(PluginCoordinate::id)
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


                tasks.register('assertPublishedMarkers') {
                    doLast {
                %s
                        println 'Resolved all published component markers'
                    }
                }
                """.formatted(pluginDeclarations, pluginAssertions);
    }
    private record PluginCoordinate(String id, String implementationArtifact) {
    }
}
