package io.github.brainage04.fabricmoddingconventions.gradle.production;

import io.github.brainage04.fabricmoddingconventions.gradle.fabric.FabricModConventionsPlugin;
import net.fabricmc.loom.task.prod.ClientProductionRunTask;
import net.fabricmc.loom.task.prod.ServerProductionRunTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

import java.util.ArrayList;
import java.util.List;

/** Configures opt-in Fabric Loom production GameTest tasks. */
public final class ProductionGameTestsPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.production-gametests";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(FabricModConventionsPlugin.class);
        ProductionGameTestExtension extension = project.getExtensions().create(
                "productionGameTests",
                ProductionGameTestExtension.class,
                project.getObjects(),
                project.getLayout()
        );
        project.afterEvaluate(_ -> configureProductionGameTests(project, extension));
    }

    private static void configureProductionGameTests(Project project, ProductionGameTestExtension extension) {
        if (!extension.getEnabled().get() || (!extension.getIncludeClient().get() && !extension.getIncludeServer().get())) {
            return;
        }

        addProductionFabricApiDependency(project, extension);
        List<TaskProvider<? extends Task>> productionTasks = new ArrayList<>();
        if (extension.getIncludeServer().get()) {
            productionTasks.add(registerServerProductionGameTest(project, extension));
        }
        if (extension.getIncludeClient().get()) {
            productionTasks.add(registerClientProductionGameTest(project, extension));
        }

        project.getTasks().register("runAllProductionGameTests", task -> {
            task.setGroup("verification");
            task.setDescription("Runs every configured production GameTest task.");
            productionTasks.forEach(task::dependsOn);
        });
    }

    private static TaskProvider<? extends Task> registerClientProductionGameTest(
            Project project,
            ProductionGameTestExtension productionExtension
    ) {
        return project.getTasks().register("runProductionClientGameTest", ClientProductionRunTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs Fabric client GameTests in Loom's production client environment.");
            task.getRunDir().convention(productionExtension.getClientRunDir());
            task.getUseXVFB().convention(productionExtension.getClientUseXvfb());
            task.getJvmArgs().add("-Dfabric.client.gametest");
            if (productionExtension.getDisableClientNetworkSynchronizer().get()) {
                task.getJvmArgs().add("-Dfabric.client.gametest.disableNetworkSynchronizer=true");
            }
            task.getJvmArgs().add("-D" + FabricModConventionsPlugin.CLIENT_GAMETEST_ENABLED_PROPERTY + "=true");
            task.getJvmArgs().addAll(productionExtension.getClientJvmArgs());
            task.getProgramArgs().addAll(productionExtension.getClientProgramArgs());
        });
    }


    private static TaskProvider<? extends Task> registerServerProductionGameTest(Project project, ProductionGameTestExtension extension) {
        return project.getTasks().register("runProductionServerGameTest", ServerProductionRunTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs Fabric server GameTests in Loom's production server environment.");
            task.getRunDir().convention(extension.getServerRunDir());
            task.getJvmArgs().add("-Dfabric-api.gametest");
            task.getJvmArgs().addAll(extension.getServerJvmArgs());
            task.getProgramArgs().addAll(extension.getServerProgramArgs());
            if (extension.getServerInstallerVersion().isPresent()) {
                task.getInstallerVersion().set(extension.getServerInstallerVersion().get());
            }
        });
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
