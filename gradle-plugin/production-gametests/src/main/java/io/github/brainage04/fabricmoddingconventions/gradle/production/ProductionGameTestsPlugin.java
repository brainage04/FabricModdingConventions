package io.github.brainage04.fabricmoddingconventions.gradle.production;

import io.github.brainage04.fabricmoddingconventions.gradle.fabric.FabricModConventionsPlugin;
import io.github.brainage04.fabricmoddingconventions.gradle.fabric.ModSide;
import net.fabricmc.loom.api.fabricapi.FabricApiExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.util.ArrayList;
import java.util.List;

/** Configures opt-in Fabric Loom production GameTest tasks. */
public final class ProductionGameTestsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.production-gametests";
    private static final String RUNTIME_LIBRARIES_CONFIGURATION = "productionGameTestRuntimeLibraries";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(FabricModConventionsPlugin.class);
        ModSide modSide = ModSide.from(project);
        configureDevelopmentGameTests(project, modSide);
        ProductionGameTestExtension extension = project.getExtensions().create(
                "productionGameTests",
                ProductionGameTestExtension.class,
                project.getObjects(),
                project.getLayout()
        );
        extension.getIncludeClient().convention(modSide != ModSide.SERVER);
        extension.getIncludeServer().convention(modSide != ModSide.CLIENT);
        project.afterEvaluate(_ -> configureProductionGameTests(project, extension));
    }

    private static void configureDevelopmentGameTests(Project project, ModSide modSide) {
        FabricApiExtension fabricApi = project.getExtensions().getByType(FabricApiExtension.class);
        Object modId = project.findProperty("mod_id");
        if (modId == null || modId.toString().isBlank()) {
            throw new GradleException(PLUGIN_ID + " requires project property 'mod_id'.");
        }
        fabricApi.configureTests(settings -> {
            settings.getCreateSourceSet().set(true);
            settings.getModId().set(modId.toString().strip() + "-gametest");
            settings.getEnableGameTests().set(modSide != ModSide.CLIENT);
            settings.getEnableClientGameTests().set(modSide != ModSide.SERVER);
            settings.getEula().set(modSide != ModSide.SERVER);
        });
    }

    private static void configureProductionGameTests(Project project, ProductionGameTestExtension extension) {
        if (!extension.getIncludeClient().get() && !extension.getIncludeServer().get()) {
            return;
        }

        addProductionFabricApiDependency(project, extension);
        Configuration runtimeLibraries = createProductionRuntimeLibraries(project, extension);
        TaskProvider<Jar> gameTestJar = registerProductionGameTestJar(project);
        TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns =
                registerPrepareProductionGameTestRuns(project, extension);
        List<TaskProvider<? extends Task>> productionTasks = new ArrayList<>();
        if (extension.getIncludeServer().get()) {
            productionTasks.add(registerServerProductionGameTest(
                    project,
                    extension,
                    gameTestJar,
                    prepareRuns,
                    runtimeLibraries
            ));
        }
        if (extension.getIncludeClient().get()) {
            productionTasks.add(registerClientProductionGameTest(
                    project,
                    extension,
                    gameTestJar,
                    prepareRuns,
                    runtimeLibraries
            ));
        }

        project.getTasks().register("runAllProductionGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs every configured production GameTest task.");
            productionTasks.forEach(task::dependsOn);
        });
    }

    private static TaskProvider<? extends Task> registerClientProductionGameTest(
            Project project,
            ProductionGameTestExtension productionExtension,
            TaskProvider<Jar> gameTestJar,
            TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns,
            Configuration runtimeLibraries
    ) {
        return project.getTasks().register(
                "runProductionClientGameTest",
                ClientGameTestProductionRunTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Runs Fabric client GameTests in Loom's production client environment.");
                    task.getRunDir().convention(productionExtension.getClientRunDir());
                    task.dependsOn(gameTestJar);
                    task.dependsOn(prepareRuns);
                    task.getMods().from(gameTestJar.flatMap(Jar::getArchiveFile));
                    task.getRuntimeLibraries().from(runtimeLibraries);
                    task.getUseXVFB().convention(productionExtension.getClientUseXvfb());
                    task.getJvmArgs().add("-Dfabric.client.gametest");
                    if (productionExtension.getDisableClientNetworkSynchronizer().get()) {
                        task.getJvmArgs().add("-Dfabric.client.gametest.disableNetworkSynchronizer=true");
                    }
                    task.getJvmArgs().add(
                            "-D" + FabricModConventionsPlugin.CLIENT_GAMETEST_ENABLED_PROPERTY + "=true"
                    );
                    task.getJvmArgs().addAll(productionExtension.getClientJvmArgs());
                    task.getProgramArgs().addAll(productionExtension.getClientProgramArgs());
                }
        );
    }

    private static TaskProvider<? extends Task> registerServerProductionGameTest(
            Project project,
            ProductionGameTestExtension extension,
            TaskProvider<Jar> gameTestJar,
            TaskProvider<PrepareProductionGameTestRunsTask> prepareRuns,
            Configuration runtimeLibraries
    ) {
        return project.getTasks().register(
                "runProductionServerGameTest",
                ServerGameTestProductionRunTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Runs Fabric server GameTests in Loom's production server environment.");
                    task.getRunDir().convention(extension.getServerRunDir());
                    task.dependsOn(gameTestJar);
                    task.dependsOn(prepareRuns);
                    task.getMods().from(gameTestJar.flatMap(Jar::getArchiveFile));
                    task.getRuntimeLibraries().from(runtimeLibraries);
                    task.getJvmArgs().add("-Dfabric-api.gametest");
                    task.getJvmArgs().addAll(extension.getServerJvmArgs());
                    task.getProgramArgs().addAll(extension.getServerProgramArgs());
                    if (extension.getServerInstallerVersion().isPresent()) {
                        task.getInstallerVersion().set(extension.getServerInstallerVersion().get());
                    }
                }
        );
    }

    private static TaskProvider<Jar> registerProductionGameTestJar(Project project) {
        SourceSet gameTestSourceSet = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .findByName("gametest");
        if (gameTestSourceSet == null) {
            throw new GradleException(
                    "productionGameTests requires the 'gametest' source set. "
                            + "Configure Fabric API GameTests before the project is evaluated."
            );
        }
        return project.getTasks().register("productionGameTestJar", Jar.class, task -> {
            task.setGroup("build");
            task.setDescription("Packages the consumer GameTest source set for production runs.");
            task.getArchiveClassifier().set("production-gametest");
            task.from(gameTestSourceSet.getOutput());
        });
    }

    private static TaskProvider<PrepareProductionGameTestRunsTask> registerPrepareProductionGameTestRuns(
            Project project,
            ProductionGameTestExtension extension
    ) {
        return project.getTasks().register(
                "prepareProductionGameTestRuns",
                PrepareProductionGameTestRunsTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Accepts the EULA for production GameTest server processes.");
                    task.getClientEulaFile().convention(extension.getClientRunDir().file("eula.txt"));
                    task.getServerEulaFile().convention(extension.getServerRunDir().file("eula.txt"));
                }
        );
    }

    private static Configuration createProductionRuntimeLibraries(
            Project project,
            ProductionGameTestExtension extension
    ) {
        Configuration runtimeLibraries = project.getConfigurations().maybeCreate(RUNTIME_LIBRARIES_CONFIGURATION);
        runtimeLibraries.setDescription("Non-mod libraries required by production GameTest runs.");
        runtimeLibraries.setCanBeConsumed(false);
        runtimeLibraries.setCanBeResolved(true);
        extension.getRuntimeLibraryDependencies().get().stream()
                .filter(dependency -> dependency != null && !dependency.isBlank())
                .map(String::strip)
                .forEach(dependency -> project.getDependencies().add(runtimeLibraries.getName(), dependency));
        return runtimeLibraries;
    }

    private static void addProductionFabricApiDependency(Project project, ProductionGameTestExtension extension) {
        if (project.getConfigurations().findByName("productionRuntimeMods") == null) {
            throw new GradleException("productionGameTests requires Fabric Loom's productionRuntimeMods configuration. "
                    + FabricModConventionsPlugin.PLUGIN_ID + " applies Loom automatically; check the configured Loom version.");
        }
        if (extension.getIncludeFabricApiDependency().get()) {
            String propertyName = extension.getFabricApiVersionProperty().get();
            Object version = project.findProperty(propertyName);
            if (version == null || version.toString().isBlank()) {
                throw new GradleException("productionGameTests requires project property '" + propertyName
                        + "' or includeFabricApiDependency=false.");
            }
            project.getDependencies().add("productionRuntimeMods", "net.fabricmc.fabric-api:fabric-api:" + version);
        }
        extension.getRuntimeModDependencies().get().stream()
                .filter(dependency -> dependency != null && !dependency.isBlank())
                .map(String::strip)
                .forEach(dependency -> project.getDependencies().add("productionRuntimeMods", dependency));
    }
}
