package io.github.brainage04.fabricmoddingconventions.gradle.modpublishing.modrinth;

import io.github.brainage04.fabricmoddingconventions.gradle.modpublishing.PublishingDestination;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Modrinth project and version destination settings. */
public abstract class ModrinthPublishingDestination extends PublishingDestination {
    public ModrinthPublishingDestination() {
        getApiEndpoint().convention("https://api.modrinth.com/v2");
        getDiscordUrl().convention("https://discord.gg/N4zfhBx8Fm");
    }

    public abstract Property<String> getProjectId();

    public abstract Property<String> getProjectSlug();

    public abstract Property<String> getApiEndpoint();

    public abstract Property<String> getRepositoryDescription();

    public abstract Property<String> getDiscordUrl();

    public abstract RegularFileProperty getProjectConfig();

    public abstract RegularFileProperty getProjectBody();

    public abstract RegularFileProperty getIconFile();
}
