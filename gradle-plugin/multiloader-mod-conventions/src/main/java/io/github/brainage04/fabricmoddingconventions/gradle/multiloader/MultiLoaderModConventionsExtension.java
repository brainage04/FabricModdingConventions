package io.github.brainage04.fabricmoddingconventions.gradle.multiloader;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class MultiLoaderModConventionsExtension {
    private final Property<Boolean> fabricClientGameTests;
    private final Property<Boolean> fabricServerGameTests;
    private final Property<Boolean> neoForgeGameTests;
    private final Property<Boolean> publishing;

    @Inject
    public MultiLoaderModConventionsExtension(ObjectFactory objects) {
        fabricClientGameTests = objects.property(Boolean.class).convention(true);
        fabricServerGameTests = objects.property(Boolean.class).convention(true);
        neoForgeGameTests = objects.property(Boolean.class).convention(true);
        publishing = objects.property(Boolean.class).convention(true);
    }

    public Property<Boolean> getFabricClientGameTests() {
        return fabricClientGameTests;
    }

    public Property<Boolean> getFabricServerGameTests() {
        return fabricServerGameTests;
    }

    public Property<Boolean> getNeoForgeGameTests() {
        return neoForgeGameTests;
    }

    public Property<Boolean> getPublishing() {
        return publishing;
    }
}
