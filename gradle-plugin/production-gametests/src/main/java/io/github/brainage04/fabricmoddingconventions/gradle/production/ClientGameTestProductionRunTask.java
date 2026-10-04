package io.github.brainage04.fabricmoddingconventions.gradle.production;

import net.fabricmc.loom.task.prod.ClientProductionRunTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.process.ExecSpec;

/** Production client GameTest task with an explicit non-mod runtime library classpath. */
@DisableCachingByDefault(because = "Runs a production Minecraft client process.")
public abstract class ClientGameTestProductionRunTask extends ClientProductionRunTask {
    public ClientGameTestProductionRunTask() {
        // A GameTest run checks the environment as well as its declared inputs, so every invocation must
        // start the client.
        getOutputs().upToDateWhen(task -> false);
    }

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeLibraries();

    public final void includeRuntimeLibrariesInClasspath() {
        getClasspath().from(getRuntimeLibraries());
    }

    @Override
    protected void configureCommand(ExecSpec exec) {
        super.configureCommand(exec);
        exec.environment("ALSOFT_DRIVERS", "null");
    }
}
