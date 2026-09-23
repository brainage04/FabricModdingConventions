package io.github.brainage04.fabricmoddingconventions.gradle.multiloader;

import io.github.brainage04.fabricmoddingconventions.gradle.modpublishing.ModPublishingExtension;
import io.github.brainage04.fabricmoddingconventions.gradle.production.ProductionGameTestExtension;
import io.github.brainage04.fabricmoddingconventions.gradle.recorder.ClientGameTestRecorderExtension;
import io.github.brainage04.fabricmoddingconventions.gradle.recorder.RecordClientGameTestTask;
import io.github.brainage04.fabricmoddingconventions.gradle.workspace.WorkspaceDependenciesExtension;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configures the common, Fabric, and NeoForge modules used by the mod fleet. */
public final class MultiLoaderModConventionsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.multiloader-mod-conventions";

    private static final Pattern RESOURCE_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final String ARCHITECTURY_LOOM = "dev.architectury.loom";
    private static final String ARCHITECTURY_LOOM_NO_REMAP = "dev.architectury.loom-no-remap";
    private static final String RECORDER_PLUGIN = "io.github.brainage04.client-gametest-recorder";
    private static final String PRODUCTION_GAMETESTS_PLUGIN = "io.github.brainage04.production-gametests";
    private static final String WORKSPACE_DEPENDENCIES_PLUGIN = "io.github.brainage04.workspace-dependencies";
    private static final String MOD_PUBLISHING_PLUGIN = "io.github.brainage04.mod-publishing";
    private static final String JAVA_QUALITY_PLUGIN = "io.github.brainage04.java-quality-conventions";

    @Override
    public void apply(Project root) {
        if (root != root.getRootProject()) {
            throw new GradleException(PLUGIN_ID + " must be applied to the root project.");
        }

        Project common = requiredSubproject(root, "common");
        Project fabric = requiredSubproject(root, "fabric");
        Project neoForge = requiredSubproject(root, "neoforge");
        MultiLoaderModConventionsExtension extension = root.getExtensions().create(
                "multiLoaderModConventions",
                MultiLoaderModConventionsExtension.class,
                root.getObjects()
        );

        root.getPluginManager().apply("base");
        configureIdentity(root, common, fabric, neoForge);
        root.getPluginManager().apply(JAVA_QUALITY_PLUGIN);
        configureCommon(root, common);
        configureFabric(root, common, fabric, extension);
        configureNeoForge(root, common, fabric, neoForge, extension);
        configureRootTasks(root, fabric, neoForge, extension);
    }

    private static void configureIdentity(Project root, Project... subprojects) {
        String group = requiredProperty(root, "maven_group");
        String version = requiredProperty(root, "mod_version");
        root.setGroup(group);
        root.setVersion(version);
        for (Project project : subprojects) {
            project.setGroup(group);
            project.setVersion(version);
            configureRepositories(project);
            configureJava(project, requiredIntegerProperty(root, "java_version"));
        }
    }

    private static void configureRepositories(Project project) {
        project.getRepositories().mavenLocal();
        for (String url : List.of(
                "https://maven.architectury.dev/",
                "https://maven.neoforged.net/releases/",
                "https://maven.fabricmc.net/",
                "https://maven.minecraftforge.net/",
                "https://maven.shedaniel.me/",
                "https://maven.terraformersmc.com/releases/",
                "https://api.modrinth.com/maven/",
                "https://maven.nucleoid.xyz/",
                "https://babbaj.github.io/maven/"
        )) {
            project.getRepositories().maven(repository -> repository.setUrl(url));
        }
        project.getRepositories().mavenCentral();
        configureReleaseRepositories(project);
    }

    private static void configureReleaseRepositories(Project project) {
        Project root = project.getRootProject();
        Object conventionsVersion = root.findProperty("fabricmoddingconventions_version");
        if (conventionsVersion != null && !conventionsVersion.toString().isBlank()) {
            project.getRepositories().ivy(repository -> {
                repository.setName("FabricModdingConventionsGitHubReleases");
                repository.setUrl("https://github.com/brainage04/FabricModdingConventions/releases/download");
                repository.patternLayout(layout ->
                        layout.artifact("v[revision]/[artifact]-[revision].[ext]"));
                repository.metadataSources(metadata -> metadata.artifact());
                repository.content(content -> content.includeModule("io.github.brainage04", "fabricmoddingconventions"));
            });
        }
        Object brainageLibVersion = root.findProperty("brainagelib_version");
        if (brainageLibVersion != null && !brainageLibVersion.toString().isBlank()) {
            project.getRepositories().ivy(repository -> {
                repository.setName("BrainageLibGitHubReleases");
                repository.setUrl("https://github.com/brainage04/BrainageLib/releases/download");
                repository.patternLayout(layout ->
                        layout.artifact("v[revision]/[artifact]-[revision].[ext]"));
                repository.metadataSources(metadata -> metadata.artifact());
                repository.content(content -> {
                    content.includeModule("io.github.brainage04", "brainagelib");
                    content.includeModule("io.github.brainage04", "brainagelib-neoforge");
                });
            });
        }
        Object hudRendererLibVersion = root.findProperty("hudrendererlib_version");
        if (hudRendererLibVersion != null && !hudRendererLibVersion.toString().isBlank()) {
            project.getRepositories().ivy(repository -> {
                repository.setName("HudRendererLibGitHubRelease");
                repository.setUrl(
                        "https://github.com/brainage04/HudRendererLib/releases/download/v"
                                + hudRendererLibVersion.toString().strip()
                );
                repository.patternLayout(layout -> layout.artifact("[artifact]-[revision].[ext]"));
                repository.metadataSources(metadata -> metadata.artifact());
                repository.content(content -> {
                    // The sibling maven publication uses io.github.brainage04, while the
                    // release-fallback declarations some mods still carry use github.brainage04.
                    // The ivy pattern ignores the group, so both must stay allowed or one of the
                    // two paths stops resolving.
                    content.includeModule("io.github.brainage04", "hudrendererlib");
                    content.includeModule("github.brainage04", "hudrendererlib");
                    content.includeModule("io.github.brainage04", "hudrendererlib-neoforge");
                    content.includeModule("github.brainage04", "hudrendererlib-neoforge");
                });
            });
        }
    }

    private static void configureJava(Project project, int release) {
        project.getPluginManager().apply("java");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(release));
        java.setSourceCompatibility(JavaVersion.toVersion(release));
        java.setTargetCompatibility(JavaVersion.toVersion(release));
        java.withSourcesJar();
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(release);
        });
    }

    private static void configureCommon(Project root, Project common) {
        common.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        common.getPluginManager().apply(ARCHITECTURY_LOOM);
        common.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        common.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name") + "-common");
        common.getDependencies().add("minecraft", minecraftDependency(root));
        common.getDependencies().add(
                "compileOnly",
                "net.fabricmc:fabric-loader:" + requiredProperty(root, "loader_version")
        );
        LoomGradleExtensionAPI loom = common.getExtensions().getByType(LoomGradleExtensionAPI.class);
        configureAccessWidener(root, loom);
        // Loom gives every project default client and server runs, but common has no loader
        // metadata, so its game is plain Minecraft without the mod. Removing the runs drops
        // their IDE run configurations and disables runClient/runServer, which
        // `./gradlew runClient` would otherwise launch alongside the Fabric and NeoForge
        // clients. Loom leaves runClientRenderDoc enabled after that, so every task that
        // launches a JVM is disabled as well.
        loom.getRuns().clear();
        common.getTasks().withType(JavaExec.class).configureEach(task -> task.setEnabled(false));

        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        File generatedResources = common.file("src/main/generated");
        if (generatedResources.isDirectory()) {
            commonMain.getResources().srcDir(generatedResources);
        }
        commonMain.getResources().exclude(".cache/**");
    }

    private static void configureFabric(
            Project root,
            Project common,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        fabric.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        fabric.getPluginManager().apply(ARCHITECTURY_LOOM);
        LoomGradleExtensionAPI loom = fabric.getExtensions().getByType(LoomGradleExtensionAPI.class);
        if (property(root, "mod_side", "both").equalsIgnoreCase("both")) {
            loom.splitEnvironmentSourceSets();
        }
        fabric.getPluginManager().apply(RECORDER_PLUGIN);
        fabric.getPluginManager().apply(PRODUCTION_GAMETESTS_PLUGIN);
        fabric.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        fabric.getPluginManager().apply(MOD_PUBLISHING_PLUGIN);
        fabric.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name"));

        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet fabricMain = sourceSets(fabric).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        loom.getMods().maybeCreate("main").sourceSet(fabricMain);
        loom.getMods().getByName("main").sourceSet(commonMain);
        SourceSet fabricClient = sourceSets(fabric).findByName("client");
        if (fabricClient != null) {
            loom.getMods().getByName("main").sourceSet(fabricClient);
        }
        configureDefaultRunDirectories(loom);
        configureAccessWidener(root, loom);
        SourceSet fabricGameTest = sourceSets(fabric).findByName("gametest");
        File sharedGameTests = common.file("src/gametest/java");
        if (fabricGameTest != null && sharedGameTests.isDirectory()) {
            fabricGameTest.getJava().srcDir(sharedGameTests);
        }

        Configuration commonConfiguration = resolvableConfiguration(fabric, "common");
        extendIfPresent(fabric, "compileClasspath", commonConfiguration);
        extendIfPresent(fabric, "runtimeClasspath", commonConfiguration);
        extendIfPresent(fabric, "developmentFabric", commonConfiguration);

        ProjectDependency commonDependency = projectDependency(fabric, common);
        fabric.getDependencies().add("common", commonDependency);
        fabric.getDependencies().add("testImplementation", projectDependency(fabric, common));
        fabric.getDependencies().add("minecraft", minecraftDependency(root));
        fabric.getDependencies().add("implementation", "net.fabricmc:fabric-loader:" + requiredProperty(root, "loader_version"));
        fabric.getDependencies().add(
                "implementation",
                "net.fabricmc.fabric-api:fabric-api:" + requiredProperty(root, "fabric_api_version")
        );
        fabric.getDependencies().add(
                "testImplementation",
                "net.fabricmc:fabric-loader-junit:" + requiredProperty(root, "loader_version")
        );

        configureMergedOutputs(common, fabric);
        configureFabricTests(root, fabric);
        configureFabricGameTests(root, fabric, fleetExtension);
        configureWorkspaceDependency(root, fabric);
        configureFabricPublishing(root, fabric, fleetExtension);
        configureResourceExpansion(root, fabric, "src/main/resources/fabric.mod.json", "fabric.mod.json");
    }

    private static void configureAccessWidener(Project root, LoomGradleExtensionAPI loom) {
        String modId = requiredProperty(root, "mod_id");
        File accessWidener = root.file("common/src/main/resources/" + modId + ".accesswidener");
        if (!accessWidener.isFile()) {
            accessWidener = root.file("fabric/src/main/resources/" + modId + ".accesswidener");
        }
        if (accessWidener.isFile()) {
            loom.getAccessWidenerPath().fileValue(accessWidener);
        }
    }

    private static void configureDefaultRunDirectories(LoomGradleExtensionAPI loom) {
        loom.getRuns().matching(run -> run.getName().equals("client"))
                .configureEach(run -> run.setRunDir("run/client"));
        loom.getRuns().matching(run -> run.getName().equals("server"))
                .configureEach(run -> run.setRunDir("run/server"));
    }

    private static void configureFabricTests(Project root, Project fabric) {
        String side = property(root, "mod_side", "both");
        fabric.getTasks().withType(Test.class).configureEach(task -> {
            task.useJUnitPlatform();
            task.systemProperty("fabric.side", side.equalsIgnoreCase("client") ? "client" : "server");
        });
    }

    private static void configureFabricGameTests(
            Project root,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        ClientGameTestRecorderExtension recorder = fabric.getExtensions()
                .getByType(ClientGameTestRecorderExtension.class);
        ProductionGameTestExtension production = fabric.getExtensions()
                .getByType(ProductionGameTestExtension.class);
        production.getIncludeClient().convention(fleetExtension.getFabricClientGameTests());
        production.getIncludeServer().convention(
                property(root, "mod_side", "both").equalsIgnoreCase("client")
                        ? fabric.getProviders().provider(() -> false)
                        : fleetExtension.getFabricServerGameTests()
        );
        production.getClientRunDir().set(recorder.getRunDir());
        production.getClientUseXvfb().convention(
                fabric.getProviders().environmentVariable("GTR_RECORDING_MANAGED_XVFB")
                        .map(value -> !Boolean.parseBoolean(value))
                        .orElse(true)
        );

        fabric.getTasks().named("recordClientGameTest", RecordClientGameTestTask.class)
                .configure(task -> task.getRunTaskName().set("runProductionClientGameTest"));
        // In this layout the recorder drives :fabric:runProductionClientGameTest, so loom's own
        // clientGameTest run is unused. It must not exist: the recorder points it at the recorder
        // directory, which is also the production client run's directory, and Gradle then rejects
        // the graph ("uses this output of task ':fabric:runProductionClientGameTest' without
        // declaring an explicit or implicit dependency") whenever `runClientGameTest` selects both
        // the root task and loom's task. Removing the run config before loom creates the task keeps
        // only the root task.
        LoomGradleExtensionAPI loom = fabric.getExtensions().getByType(LoomGradleExtensionAPI.class);
        // The recorder re-points loom's clientGameTest run at the recorder directory from its own
        // afterEvaluate, which runs after this apply block, so the override has to be registered
        // later as well. Otherwise loom's run task shares the production client run's directory and
        // Gradle rejects the graph when `runClientGameTest` selects both the root task and loom's.
        fabric.afterEvaluate(project -> {
            LoomGradleExtensionAPI loomApi = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
            if (loomApi.getRuns().findByName("clientGameTest") != null) {
                String loomRunDir = project.getLayout().getBuildDirectory()
                        .dir("run/loomClientGameTest").get().getAsFile().getAbsolutePath();
                loomApi.getRuns().named("clientGameTest").configure(run -> run.setRunDir(loomRunDir));
            }
        });
        fabric.getTasks().named("prepareClientGameTestRun").configure(task ->
                task.mustRunAfter("prepareProductionGameTestRuns"));
        fabric.getTasks().matching(task -> task.getName().equals("runProductionClientGameTest"))
                .configureEach(task -> task.dependsOn("prepareClientGameTestRun"));
    }

    private static void configureWorkspaceDependency(Project root, Project fabric) {
        WorkspaceDependenciesExtension workspace = fabric.getExtensions()
                .getByType(WorkspaceDependenciesExtension.class);
        workspace.siblingMaven("FabricModdingConventions", declaration -> {
            declaration.getCoordinate().set(
                    "io.github.brainage04:fabricmoddingconventions:"
                            + requiredProperty(root, "fabricmoddingconventions_version")
            );
            declaration.getSiblingDirectory().set(root.getLayout().getProjectDirectory().dir("../FabricModdingConventions"));
        });
    }

    private static void configureFabricPublishing(
            Project root,
            Project fabric,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        ModPublishingExtension publishing = fabric.getExtensions().getByType(ModPublishingExtension.class);
        configureSharedPublishing(root, publishing);
        fabric.getTasks().matching(task -> task.getName().startsWith("publish"))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getPublishing().get()));
    }

    private static void configureNeoForge(
            Project root,
            Project common,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        neoForge.getPluginManager().apply(ARCHITECTURY_LOOM_NO_REMAP);
        neoForge.getPluginManager().apply(ARCHITECTURY_LOOM);
        neoForge.getPluginManager().apply(WORKSPACE_DEPENDENCIES_PLUGIN);
        neoForge.getPluginManager().apply(MOD_PUBLISHING_PLUGIN);
        neoForge.getExtensions().getByType(BasePluginExtension.class).getArchivesName()
                .set(requiredProperty(root, "archives_base_name") + "-neoforge");

        SourceSetContainer neoSourceSets = sourceSets(neoForge);
        SourceSet main = neoSourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet gameTest = neoSourceSets.maybeCreate("gametest");
        gameTest.setCompileClasspath(gameTest.getCompileClasspath()
                .plus(main.getOutput())
                .plus(neoForge.getConfigurations().getByName("compileClasspath")));
        gameTest.setRuntimeClasspath(gameTest.getRuntimeClasspath()
                .plus(gameTest.getOutput())
                .plus(gameTest.getCompileClasspath())
                .plus(neoForge.getConfigurations().getByName("runtimeClasspath")));
        File sharedGameTests = common.file("src/gametest/java");
        if (sharedGameTests.isDirectory()) {
            gameTest.getJava().srcDir(sharedGameTests);
        }

        LoomGradleExtensionAPI loom = neoForge.getExtensions().getByType(LoomGradleExtensionAPI.class);
        File accessTransformer = neoForge.file("src/main/resources/META-INF/accesstransformer.cfg");
        loom.neoForge(extension -> {
            if (accessTransformer.isFile()) {
                extension.accessTransformer(accessTransformer);
            }
        });
        loom.getMods().maybeCreate("main").sourceSet(main);
        loom.getMods().getByName("main").sourceSet(sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME));
        loom.getMods().getByName("main").sourceSet(gameTest);
        loom.getRuns().maybeCreate("gameTestServer").server();
        loom.getRuns().named("gameTestServer").configure(run -> {
            run.forgeTemplate("gameTestServer");
            run.name("NeoForge Game Tests");
            run.source(gameTest);
            run.runDir("run/gametest");
        });
        configureDefaultRunDirectories(loom);

        Configuration commonConfiguration = resolvableConfiguration(neoForge, "common");
        extendIfPresent(neoForge, "compileClasspath", commonConfiguration);
        neoForge.getDependencies().add("minecraft", minecraftDependency(root));
        neoForge.getDependencies().add("neoForge", "net.neoforged:neoforge:" + requiredProperty(root, "neoforge_version"));
        neoForge.getDependencies().add("common", projectDependency(neoForge, common));

        configureMergedOutputs(common, neoForge);
        configureNeoForgePublishing(root, fabric, neoForge, fleetExtension);
        configureResourceExpansion(
                root,
                neoForge,
                "src/main/resources/META-INF/neoforge.mods.toml",
                "META-INF/neoforge.mods.toml"
        );
        neoForge.getTasks().matching(task -> task.getName().equals("runGameTestServer"))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getNeoForgeGameTests().get()));
    }

    private static void configureMergedOutputs(Project common, Project loader) {
        SourceSet commonMain = sourceSets(common).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        loader.getTasks().named("jar", Jar.class).configure(task -> {
            task.from(commonMain.getOutput());
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });
        loader.getTasks().named("sourcesJar", Jar.class).configure(task -> {
            task.from(commonMain.getAllSource());
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
        });
        loader.getTasks().matching(task -> task.getName().startsWith("run"))
                .configureEach(task -> task.dependsOn(common.getTasks().named("jar")));
    }

    private static void configureNeoForgePublishing(
            Project root,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension fleetExtension
    ) {
        String version = requiredProperty(root, "mod_version");
        ModPublishingExtension publishing = neoForge.getExtensions().getByType(ModPublishingExtension.class);
        configureSharedPublishing(root, publishing);
        publishing.getVersion().set(version + "-neoforge");
        publishing.getReleaseTag().set("v" + version);
        publishing.getDisplayName().set(requiredProperty(root, "mod_name") + " " + version + " (NeoForge)");
        publishing.getModLoaders().set(List.of("neoforge"));
        publishing.getFabricModJson().set(fabric.getLayout().getBuildDirectory().file("resources/main/fabric.mod.json"));
        neoForge.getTasks().named("validateModPublication")
                .configure(task -> task.dependsOn(fabric.getTasks().named("processResources")));
        neoForge.getTasks().matching(task -> task.getName().startsWith("publish"))
                .configureEach(task -> task.onlyIf(spec -> fleetExtension.getPublishing().get()));

        neoForge.getTasks().named("jar", Jar.class).configure(task -> task.from(
                root.file("LICENSE"),
                copy -> copy.rename(fileName -> fileName + "_" + requiredProperty(root, "archives_base_name") + "-neoforge")
        ));
    }

    private static void configureSharedPublishing(Project root, ModPublishingExtension publishing) {
        publishing.getGithub().getRepository().convention("brainage04/" + root.getName());
        String modId = requiredProperty(root, "mod_id");
        String side = property(root, "mod_side", "both").toLowerCase();
        publishing.getCurseforge().getClient().set(!side.equals("server"));
        publishing.getCurseforge().getServer().set(!side.equals("client"));

        File icon = root.file("common/src/main/resources/assets/" + modId + "/icon.png");
        if (!icon.isFile()) {
            icon = root.file("fabric/src/main/resources/assets/" + modId + "/icon.png");
        }
        if (icon.isFile()) {
            publishing.getModrinth().getIconFile().convention(
                    root.getLayout().getProjectDirectory().file(root.relativePath(icon))
            );
        }
        File neoForgeDependencies = root.file(".modrinth/neoforge-dependencies.json");
        if (neoForgeDependencies.isFile()) {
            publishing.getSourceFabricModJson().set(
                    root.getLayout().getProjectDirectory().file(".modrinth/neoforge-dependencies.json")
            );
        }
    }

    private static void configureResourceExpansion(
            Project root,
            Project project,
            String sourcePath,
            String targetPattern
    ) {
        File resource = project.file(sourcePath);
        if (!resource.isFile()) {
            return;
        }
        Map<String, String> expansion = resourceExpansion(root, project, resource);
        project.getTasks().withType(ProcessResources.class).configureEach(task -> {
            task.getInputs().properties(expansion);
            task.filesMatching(targetPattern, details -> details.expand(expansion));
        });
    }

    private static Map<String, String> resourceExpansion(Project root, Project project, File resource) {
        String content;
        try {
            content = Files.readString(resource.toPath());
        } catch (IOException exception) {
            throw new GradleException("Cannot read resource metadata " + resource + ".", exception);
        }
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = RESOURCE_PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = switch (name) {
                case "version", "mod_version" -> project.getVersion();
                default -> root.findProperty(name);
            };
            if (value == null || value.toString().isBlank()) {
                throw new GradleException(resource + " requires project property '" + name + "'.");
            }
            values.put(name, value.toString());
        }
        return Map.copyOf(values);
    }

    private static void configureRootTasks(
            Project root,
            Project fabric,
            Project neoForge,
            MultiLoaderModConventionsExtension extension
    ) {
        root.getTasks().register("runNeoForgeGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs the NeoForge server GameTest suite.");
            task.dependsOn(neoForge.getTasks().named("runGameTestServer"));
            task.onlyIf(spec -> extension.getNeoForgeGameTests().get());
        });
        root.getTasks().register("runAllProductionGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs Fabric and NeoForge production GameTests.");
            task.dependsOn(fabric.getTasks().named("runAllProductionGameTests"));
            task.dependsOn(neoForge.getTasks().named("runGameTestServer"));
        });
        root.getTasks().register("runFabricClient", task -> {
            task.setGroup("application");
            task.setDescription("Runs the Fabric development client.");
            task.dependsOn(":fabric:runClient");
        });
        root.getTasks().register("runNeoForgeClient", task -> {
            task.setGroup("application");
            task.setDescription("Runs the NeoForge development client.");
            task.dependsOn(":neoforge:runClient");
        });
        root.getTasks().register("runClientGameTest", task -> {
            task.setGroup("verification");
            task.setDescription("Runs the Fabric production client GameTests.");
            task.dependsOn(":fabric:runProductionClientGameTest");
        });
        root.getTasks().register("recordClientGameTest", task -> {
            task.setGroup("verification");
            task.setDescription("Records the Fabric production client GameTests.");
            task.dependsOn(":fabric:recordClientGameTest");
        });

        TaskProvider<Jar> fabricJar = fabric.getTasks().named("jar", Jar.class);
        TaskProvider<Jar> neoForgeJar = neoForge.getTasks().named("jar", Jar.class);
        TaskProvider<Sync> collect = root.getTasks().register("collectReleaseArtifacts", Sync.class, task -> {
            task.setGroup("build");
            task.dependsOn(fabricJar, neoForgeJar);
            task.from(fabricJar.flatMap(Jar::getArchiveFile));
            task.from(neoForgeJar.flatMap(Jar::getArchiveFile));
            task.into(root.getLayout().getBuildDirectory().dir("libs"));
        });
        root.getTasks().named("build").configure(task -> {
            task.setDescription("Builds and tests every loader, then collects both production JARs.");
            root.getSubprojects().forEach(project -> task.dependsOn(project.getTasks().named("build")));
            task.dependsOn(collect);
        });
    }

    private static SourceSetContainer sourceSets(Project project) {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    private static Configuration resolvableConfiguration(Project project, String name) {
        Configuration configuration = project.getConfigurations().maybeCreate(name);
        configuration.setCanBeResolved(true);
        configuration.setCanBeConsumed(false);
        return configuration;
    }

    private static void extendIfPresent(Project project, String name, Configuration parent) {
        Configuration configuration = project.getConfigurations().findByName(name);
        if (configuration != null) {
            configuration.extendsFrom(parent);
        }
    }

    private static ProjectDependency projectDependency(Project owner, Project target) {

        ProjectDependency dependency = (ProjectDependency) owner.getDependencies()
                .project(Map.of("path", target.getPath()));
        dependency.setTransitive(false);
        return dependency;
    }

    private static String minecraftDependency(Project root) {
        return "com.mojang:minecraft:" + requiredProperty(root, "minecraft_version");
    }

    private static Project requiredSubproject(Project root, String name) {
        Project project = root.findProject(":" + name);
        if (project == null) {
            throw new GradleException(PLUGIN_ID + " requires subproject ':" + name + "'.");
        }
        return project;
    }

    private static int requiredIntegerProperty(Project project, String name) {
        String value = requiredProperty(project, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new GradleException("Project property '" + name + "' must be an integer, but was '" + value + "'.", exception);
        }
    }

    private static String requiredProperty(Project project, String name) {
        Object value = project.findProperty(name);
        if (value == null || value.toString().isBlank()) {
            throw new GradleException(PLUGIN_ID + " requires project property '" + name + "'.");
        }
        return value.toString().strip();
    }

    private static String property(Project project, String name, String fallback) {
        Object value = project.findProperty(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString().strip();
    }
}
