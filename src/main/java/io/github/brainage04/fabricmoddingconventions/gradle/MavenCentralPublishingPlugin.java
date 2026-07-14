package io.github.brainage04.fabricmoddingconventions.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.logging.Logger;
import org.gradle.plugins.signing.Sign;
import org.gradle.plugins.signing.SigningExtension;
import org.gradle.plugins.signing.SigningPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Callable;

/** Configures reusable Maven Central publication metadata, signing, and Portal upload behavior. */
public final class MavenCentralPublishingPlugin implements Plugin<Project> {
    public static final String PLUGIN_ID = "io.github.brainage04.maven-central-publishing";
    public static final String EXTENSION_NAME = "mavenCentralPublishing";
    public static final String PUBLISH_TASK_NAME = "publishToMavenCentral";

    private static final String METADATA_VALIDATION_TASK_NAME = "validateMavenPublicationMetadata";
    private static final String CENTRAL_VALIDATION_TASK_NAME = "validateMavenCentralPublishing";
    private static final String LOCAL_REPOSITORY_NAME = "local";
    private static final String CENTRAL_REPOSITORY_NAME = "central";
    private static final URI CENTRAL_STAGING_REPOSITORY = URI.create(
            "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
    );
    private static final String CENTRAL_UPLOAD_BASE_URL =
            "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/";
    private static final Set<String> SUPPORTED_PUBLISHING_TYPES = Set.of(
            "user_managed",
            "automatic",
            "portal_api"
    );

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(MavenPublishPlugin.class);
        project.getPluginManager().apply(SigningPlugin.class);

        MavenCentralPublishingExtension extension = project.getExtensions().create(
                EXTENSION_NAME,
                MavenCentralPublishingExtension.class
        );
        configureDefaults(project, extension);

        Provider<String> centralPortalUsername = project.getProviders()
                .environmentVariable("CENTRAL_PORTAL_USERNAME");
        Provider<String> centralPortalPassword = project.getProviders()
                .environmentVariable("CENTRAL_PORTAL_PASSWORD");
        Provider<String> signingKey = project.getProviders().environmentVariable("SIGNING_KEY");
        Provider<String> signingPassword = project.getProviders().environmentVariable("SIGNING_PASSWORD");
        Provider<Boolean> useGpgAgentSigning = project.getProviders()
                .gradleProperty("useGpgAgentSigning")
                .map(Boolean::parseBoolean)
                .orElse(false);

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        configurePublications(extension, publishing);
        configurePluginMarkerMetadata(project, publishing);
        configureRepositories(
                extension,
                publishing,
                centralPortalUsername,
                centralPortalPassword
        );
        configureSigning(
                project,
                publishing,
                signingKey,
                signingPassword,
                useGpgAgentSigning
        );
        configurePublishingTasks(
                project,
                extension,
                centralPortalUsername,
                centralPortalPassword,
                signingKey,
                signingPassword,
                useGpgAgentSigning
        );
    }

    private static void configureDefaults(Project project, MavenCentralPublishingExtension extension) {
        extension.getPublicationName().convention(project.getProviders().provider(() -> {
            Object modName = project.findProperty("mod_name");
            return modName == null ? project.getName() : modName.toString();
        }));
        extension.getNamespace().convention(
                project.getProviders().gradleProperty("centralNamespace")
                        .orElse(project.getProviders().provider(() -> project.getGroup().toString()))
        );
        extension.getPublishingType().convention(
                project.getProviders().gradleProperty("centralPublishingType").orElse("user_managed")
        );
        extension.getDeveloperId().convention("brainage04");
        extension.getDeveloperName().convention("brainage04");
        extension.getDeveloperUrl().convention("https://github.com/brainage04");
        extension.getLicenseName().convention("MIT License");
        extension.getLicenseUrl().convention("https://opensource.org/license/mit");
        extension.getLocalRepository().convention(project.getLayout().getBuildDirectory().dir("local-repo"));
    }

    private static void configurePublications(
            MavenCentralPublishingExtension extension,
            PublishingExtension publishing
    ) {
        publishing.getPublications().withType(MavenPublication.class).configureEach(publication ->
                publication.pom(pom -> {
                    pom.getName().convention(extension.getPublicationName());
                    pom.getDescription().convention(extension.getDescription());
                    pom.getUrl().set(extension.getRepository().map(MavenCentralPublishingPlugin::githubUrl));
                    pom.licenses(licenses -> licenses.license(license -> {
                        license.getName().set(extension.getLicenseName());
                        license.getUrl().set(extension.getLicenseUrl());
                        license.getDistribution().set("repo");
                    }));
                    pom.developers(developers -> developers.developer(developer -> {
                        developer.getId().set(extension.getDeveloperId());
                        developer.getName().set(extension.getDeveloperName());
                        developer.getUrl().set(extension.getDeveloperUrl());
                    }));
                    pom.scm(scm -> {
                        scm.getConnection().set(extension.getRepository().map(repository ->
                                "scm:git:" + githubUrl(repository) + ".git"));
                        scm.getDeveloperConnection().set(extension.getRepository().map(repository ->
                                "scm:git:ssh://git@github.com/" + normalizedRepository(repository) + ".git"));
                        scm.getUrl().set(extension.getRepository().map(MavenCentralPublishingPlugin::githubUrl));
                    });
                }));
    }

    private static void configurePluginMarkerMetadata(Project project, PublishingExtension publishing) {
        project.getPluginManager().withPlugin("java-gradle-plugin", ignored ->
                project.afterEvaluate(evaluatedProject -> {
                    GradlePluginDevelopmentExtension development = project.getExtensions()
                            .getByType(GradlePluginDevelopmentExtension.class);
                    development.getPlugins().forEach(declaration -> {
                        String publicationName = declaration.getName() + "PluginMarkerMaven";
                        if (!(publishing.getPublications().findByName(publicationName)
                                instanceof MavenPublication markerPublication)) {
                            return;
                        }
                        markerPublication.pom(pom -> {
                            if (declaration.getDisplayName() != null
                                    && !declaration.getDisplayName().isBlank()) {
                                pom.getName().set(declaration.getDisplayName());
                            }
                            if (declaration.getDescription() != null
                                    && !declaration.getDescription().isBlank()) {
                                pom.getDescription().set(declaration.getDescription());
                            }
                        });
                    });
                }));
    }

    private static void configureRepositories(
            MavenCentralPublishingExtension extension,
            PublishingExtension publishing,
            Provider<String> centralPortalUsername,
            Provider<String> centralPortalPassword
    ) {
        publishing.getRepositories().maven(repository -> {
            repository.setName(LOCAL_REPOSITORY_NAME);
            repository.setUrl(extension.getLocalRepository());
        });
        publishing.getRepositories().maven(repository -> {
            repository.setName(CENTRAL_REPOSITORY_NAME);
            repository.setUrl(CENTRAL_STAGING_REPOSITORY);
            repository.credentials(PasswordCredentials.class, credentials -> {
                credentials.setUsername(centralPortalUsername.getOrNull());
                credentials.setPassword(centralPortalPassword.getOrNull());
            });
        });
    }

    private static void configureSigning(
            Project project,
            PublishingExtension publishing,
            Provider<String> signingKey,
            Provider<String> signingPassword,
            Provider<Boolean> useGpgAgentSigning
    ) {
        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        signing.setRequired((Callable<Boolean>) () -> project.getGradle().getTaskGraph().getAllTasks().stream()
                .anyMatch(MavenCentralPublishingPlugin::isCentralPublishingTask));
        if (useGpgAgentSigning.get()) {
            signing.useGpgCmd();
        } else if (signingKey.isPresent() && signingPassword.isPresent()) {
            signing.useInMemoryPgpKeys(signingKey.get(), signingPassword.get());
        }
        signing.sign(publishing.getPublications());

        project.getTasks().withType(Sign.class).configureEach(task -> task.doFirst(_ -> {
            if (signing.isRequired()
                    && !useGpgAgentSigning.get()
                    && (!signingKey.isPresent() || !signingPassword.isPresent())) {
                throw missingSigningConfiguration();
            }
        }));
    }

    private static void configurePublishingTasks(
            Project project,
            MavenCentralPublishingExtension extension,
            Provider<String> centralPortalUsername,
            Provider<String> centralPortalPassword,
            Provider<String> signingKey,
            Provider<String> signingPassword,
            Provider<Boolean> useGpgAgentSigning
    ) {
        TaskProvider<Task> validateMetadata = project.getTasks().register(
                METADATA_VALIDATION_TASK_NAME,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates Maven publication metadata.");
                    task.doLast(_ -> validateMetadata(extension));
                }
        );
        TaskProvider<Task> validateCentral = project.getTasks().register(
                CENTRAL_VALIDATION_TASK_NAME,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates credentials, signing, and Central release configuration.");
                    task.dependsOn(validateMetadata);
                    task.doLast(_ -> validateCentralConfiguration(
                            extension,
                            centralPortalUsername,
                            centralPortalPassword,
                            signingKey,
                            signingPassword,
                            useGpgAgentSigning
                    ));
                }
        );
        TaskProvider<Task> publishToCentral = project.getTasks().register(
                PUBLISH_TASK_NAME,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Publishes artifacts to Sonatype Central and uploads the staged deployment to the Central Portal."
                    );
                    task.dependsOn("publishAllPublicationsToCentralRepository");
                    task.doLast(_ -> uploadDeployment(
                            extension,
                            centralPortalUsername.get(),
                            centralPortalPassword.get(),
                            project.getLogger()
                    ));
                }
        );

        project.getTasks().withType(Sign.class).configureEach(task -> task.mustRunAfter(validateCentral));

        project.getTasks().withType(PublishToMavenRepository.class).configureEach(task -> {
            task.dependsOn(validateMetadata);
            if (isCentralPublishingTask(task)) {
                task.dependsOn(validateCentral);
            }
        });
    }

    private static void validateMetadata(MavenCentralPublishingExtension extension) {
        requiredValue(extension.getPublicationName().getOrNull(), "publicationName");
        requiredValue(extension.getDescription().getOrNull(), "description");
        normalizedRepository(requiredValue(extension.getRepository().getOrNull(), "repository"));
        requiredValue(extension.getDeveloperId().getOrNull(), "developerId");
        requiredValue(extension.getDeveloperName().getOrNull(), "developerName");
        requiredValue(extension.getDeveloperUrl().getOrNull(), "developerUrl");
        requiredValue(extension.getLicenseName().getOrNull(), "licenseName");
        requiredValue(extension.getLicenseUrl().getOrNull(), "licenseUrl");
    }

    private static void validateCentralConfiguration(
            MavenCentralPublishingExtension extension,
            Provider<String> centralPortalUsername,
            Provider<String> centralPortalPassword,
            Provider<String> signingKey,
            Provider<String> signingPassword,
            Provider<Boolean> useGpgAgentSigning
    ) {
        String publishingType = requiredValue(extension.getPublishingType().getOrNull(), "publishingType");
        if (!SUPPORTED_PUBLISHING_TYPES.contains(publishingType)) {
            throw new GradleException(
                    "Unsupported centralPublishingType '" + publishingType
                            + "'. Use one of: user_managed, automatic, portal_api."
            );
        }
        requiredValue(extension.getNamespace().getOrNull(), "namespace");
        if (!centralPortalUsername.isPresent() || !centralPortalPassword.isPresent()) {
            throw new GradleException(
                    "Set CENTRAL_PORTAL_USERNAME and CENTRAL_PORTAL_PASSWORD before publishing to Maven Central."
            );
        }
        if (!useGpgAgentSigning.get() && (!signingKey.isPresent() || !signingPassword.isPresent())) {
            throw missingSigningConfiguration();
        }
    }

    private static GradleException missingSigningConfiguration() {
        return new GradleException(
                "Use -PuseGpgAgentSigning=true for local GPG-agent signing, or set SIGNING_KEY and "
                        + "SIGNING_PASSWORD for in-memory signing."
        );
    }

    private static boolean isCentralPublishingTask(Task task) {
        String name = task.getName();
        return PUBLISH_TASK_NAME.equals(name)
                || (name.startsWith("publish") && name.endsWith("PublicationToCentralRepository"));
    }

    private static String requiredValue(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new GradleException("mavenCentralPublishing." + property + " must not be blank.");
        }
        return value.strip();
    }

    private static String normalizedRepository(String repository) {
        String normalized = repository.strip();
        String[] parts = normalized.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new GradleException(
                    "mavenCentralPublishing.repository must use nonblank owner/name notation, but was '"
                            + repository + "'."
            );
        }
        return parts[0] + "/" + parts[1];
    }

    private static String githubUrl(String repository) {
        return "https://github.com/" + normalizedRepository(repository);
    }

    private static void uploadDeployment(
            MavenCentralPublishingExtension extension,
            String username,
            String password,
            Logger logger
    ) {
        String namespace = extension.getNamespace().get().strip();
        String publishingType = extension.getPublishingType().get().strip();
        String bearerToken = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
        String uploadUrl = CENTRAL_UPLOAD_BASE_URL
                + namespace
                + "?publishing_type="
                + URLEncoder.encode(publishingType, StandardCharsets.UTF_8);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(uploadUrl).toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            String responseText = readResponse(connection, responseCode);
            if (responseCode < 200 || responseCode >= 300) {
                throw new GradleException(
                        "Central upload request failed with HTTP " + responseCode + ": " + responseText
                );
            }
            String response = responseText.isBlank() ? Integer.toString(responseCode) : responseText;
            logger.lifecycle(
                    "Central deployment uploaded for namespace '{}'. Response: {}",
                    namespace,
                    response
            );
        } catch (IOException exception) {
            throw new GradleException("Central upload request failed: " + exception.getMessage(), exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readResponse(HttpURLConnection connection, int responseCode) throws IOException {
        InputStream responseStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (responseStream == null) {
            return "";
        }
        try (responseStream) {
            return new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
