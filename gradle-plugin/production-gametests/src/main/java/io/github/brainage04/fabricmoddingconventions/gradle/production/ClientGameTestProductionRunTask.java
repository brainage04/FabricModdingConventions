package io.github.brainage04.fabricmoddingconventions.gradle.production;

import net.fabricmc.loom.task.prod.ClientProductionRunTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

/** Production client GameTest task with an explicit non-mod runtime library classpath. */
@DisableCachingByDefault(because = "Runs a production Minecraft client process.")
public abstract class ClientGameTestProductionRunTask extends ClientProductionRunTask {
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeLibraries();

    @Inject
    public ClientGameTestProductionRunTask() {
        getClasspath().from(getRuntimeLibraries());
    }
}
