package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.api.provider.Property;

/** GitHub release destination settings. */
public abstract class GitHubPublishingDestination extends PublishingDestination {
    public GitHubPublishingDestination() {
        getApiEndpoint().convention("https://api.github.com");
    }

    public abstract Property<String> getRepository();

    public abstract Property<String> getCommitish();

    public abstract Property<String> getApiEndpoint();
}
