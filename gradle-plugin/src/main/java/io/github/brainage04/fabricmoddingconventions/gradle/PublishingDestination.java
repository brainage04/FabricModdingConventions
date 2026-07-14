package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.api.provider.Property;

/** Common opt-in and credential settings for one mod distribution destination. */
public abstract class PublishingDestination {
    public PublishingDestination() {
        getEnabled().convention(false);
    }

    public abstract Property<Boolean> getEnabled();

    public abstract Property<String> getToken();
}
