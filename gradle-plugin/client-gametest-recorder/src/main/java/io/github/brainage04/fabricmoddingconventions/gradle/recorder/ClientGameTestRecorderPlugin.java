package io.github.brainage04.fabricmoddingconventions.gradle.recorder;

import io.github.brainage04.fabricmoddingconventions.gradle.fabric.FabricModConventionsPlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.task.AbstractRunTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

import java.util.List;
import java.util.Locale;

/** Configures recording support for Fabric client GameTests. */
public final class ClientGameTestRecorderPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.client-gametest-recorder";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(FabricModConventionsPlugin.class);
        ClientGameTestRecorderExtension extension = project.getExtensions().create(
                "clientGameTestRecorder",
                ClientGameTestRecorderExtension.class,
                project.getObjects(),
                project.getLayout()
        );
        String recordingRunDirectory = project.getProviders()
                .environmentVariable("GTR_RECORDING_RUN_DIR")
                .getOrNull();
        if (recordingRunDirectory != null && !recordingRunDirectory.isBlank()) {
            extension.getRunDir().fileValue(project.file(recordingRunDirectory));
        }

        TaskProvider<PrepareClientGameTestRunTask> prepareTask = project.getTasks().register(
                "prepareClientGameTestRun",
                PrepareClientGameTestRunTask.class,
                task -> configurePrepareTask(project, extension, task)
        );
        prepareTask.configure(task -> task.mustRunAfter(project.getTasks().matching(candidate -> candidate.getName().equals("deleteGameTestRunDir"))));

        project.getTasks().register("recordClientGameTest", RecordClientGameTestTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the Fabric client GameTest task under a recorded Xvfb/PipeWire/ffmpeg session.");
            task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
            task.getRunDirectory().convention(extension.getRunDir());
            task.getRecordingAudioDeviceProjectProperty().convention(extension.getRecordingAudioDeviceProperty());
            task.getRunTaskName().convention("runClientGameTest");
            task.getOutputs().upToDateWhen(_ -> false);
        });

        project.afterEvaluate(_ -> project.getTasks()
                .matching(task -> task.getName().equals("runClientGameTest"))
                .configureEach(task -> configureRunClientGameTest(project, extension, prepareTask, task)));
    }

    private static void configurePrepareTask(Project project, ClientGameTestRecorderExtension extension, PrepareClientGameTestRunTask task) {
        ProviderFactory providers = project.getProviders();
        Provider<String> audioDevice = extension.getRecordingAudioDeviceProperty()
                .flatMap(providers::gradleProperty)
                .orElse(extension.getRecordingAudioDeviceEnv().flatMap(providers::environmentVariable))
                .orElse("");

        task.getRecordingAudioDevice().convention(audioDevice);
        task.getMinecraftOptionsVersion().convention(extension.getMinecraftOptionsVersion());
        task.getMaxFps().convention(extension.getMaxFps());
        task.getRenderDistance().convention(extension.getRenderDistance());
        task.getSimulationDistance().convention(extension.getSimulationDistance());
        task.getGuiScale().convention(extension.getGuiScale());
        task.getFullscreen().convention(extension.getFullscreen());
        task.getOptionsFile().convention(extension.getRunDir().file("options.txt"));
    }

    private static void configureRunClientGameTest(
            Project project,
            ClientGameTestRecorderExtension extension,
            TaskProvider<PrepareClientGameTestRunTask> prepareTask,
            Task task
    ) {
        task.dependsOn(prepareTask);
        LoomGradleExtensionAPI loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        String runDirectory = project.getRootProject().relativePath(extension.getRunDir().get().getAsFile());
        loom.getRuns().named("clientGameTest").configure(run -> run.setRunDir(runDirectory));
        AbstractRunTask loomRunTask = requireLoomRunTask(task);
        JavaExec javaExec = requireJavaExec(task);
        javaExec.jvmArgs(List.of(
                "-Dfabric.client.gametest.disableNetworkSynchronizer=true",
                "-D" + FabricModConventionsPlugin.CLIENT_GAMETEST_ENABLED_PROPERTY + "=true"
        ));

        for (String name : List.of("ALSOFT_CONF", "ALSOFT_DRIVERS", "PULSE_SINK")) {
            String value = project.getProviders().environmentVariable(name).getOrNull();
            if (value != null && !value.isBlank()) {
                javaExec.environment(name, value);
            }
        }

        String managedXvfbValue = project.getProviders().environmentVariable(extension.getManagedXvfbEnv().get()).getOrNull();
        if (truthy(managedXvfbValue)) {
            loomRunTask.getUseXvfb().set(false);
        }
    }

    private static JavaExec requireJavaExec(Task task) {
        if (task instanceof JavaExec javaExec) {
            return javaExec;
        }
        throw new GradleException("Task " + task.getPath() + " must be a JavaExec task.");
    }

    private static AbstractRunTask requireLoomRunTask(Task task) {
        if (task instanceof AbstractRunTask loomRunTask) {
            return loomRunTask;
        }
        throw new GradleException("Task " + task.getPath() + " must be a Fabric Loom run task to disable Loom-managed Xvfb.");
    }

    private static boolean truthy(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return List.of("1", "true", "yes", "on").contains(normalized);
    }
}
