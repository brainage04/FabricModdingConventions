package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** CurseForge file destination settings. */
public abstract class CurseForgePublishingDestination extends PublishingDestination {
    public CurseForgePublishingDestination() {
        getApiEndpoint().convention("https://minecraft.curseforge.com");
        getClient().convention(true);
        getServer().convention(true);
        getJavaVersions().convention(java.util.List.of());
    }

    public abstract Property<String> getProjectId();

    public abstract Property<String> getProjectSlug();

    public abstract Property<String> getApiEndpoint();

    public abstract Property<Boolean> getClient();

    public abstract Property<Boolean> getServer();

    public abstract ListProperty<String> getJavaVersions();
}
