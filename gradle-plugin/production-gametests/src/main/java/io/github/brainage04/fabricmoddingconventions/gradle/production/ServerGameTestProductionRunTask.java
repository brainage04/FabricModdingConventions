package io.github.brainage04.fabricmoddingconventions.gradle.production;

import net.fabricmc.loom.task.prod.ServerProductionRunTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

/** Production server GameTest task with an explicit non-mod runtime library classpath. */
@DisableCachingByDefault(because = "Runs a production Minecraft server process.")
public abstract class ServerGameTestProductionRunTask extends ServerProductionRunTask {
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeLibraries();

    @Inject
    public ServerGameTestProductionRunTask() {
        getClasspath().from(getRuntimeLibraries());
    }
}
