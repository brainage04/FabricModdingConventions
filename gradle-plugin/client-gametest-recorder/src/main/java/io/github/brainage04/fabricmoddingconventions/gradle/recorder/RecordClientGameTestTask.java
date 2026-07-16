package io.github.brainage04.fabricmoddingconventions.gradle.recorder;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DisableCachingByDefault(because = "The task controls external Minecraft, Xvfb, PipeWire, and ffmpeg processes.")
public abstract class RecordClientGameTestTask extends DefaultTask {
    private static final Pattern PIPEWIRE_ID_PATTERN = Pattern.compile("^\\s*id\\s+(\\d+),");
    private static final Pattern DEFAULT_SINK_PATTERN = Pattern.compile("\\*\\s*(\\d+)\\.");

    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Internal
    public abstract DirectoryProperty getRunDirectory();

    @Input
    public abstract Property<String> getRecordingAudioDeviceProjectProperty();

    @Input
    public abstract Property<String> getRunTaskName();

    @TaskAction
    public void record() {
        requireTools(List.of("ffmpeg", "ffprobe", "Xvfb", "xdpyinfo", "pw-cli", "wpctl"));

        File projectDir = getProjectDirectory().get().getAsFile();
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        File recordingDir = projectFile(projectDir, env("GTR_RECORDING_DIR", "build/recordings"));
        String recordingName = env("GTR_RECORDING_NAME", "client-gametest");
        String recordingProfile = env("GTR_RECORDING_PROFILE", env("CLIENT_GAMETEST_PROFILE", "showcase"));
        String recordingTrace = env("GTR_RECORDING_TRACE", "false");
        File runDir = getRunDirectory().get().getAsFile();
        String fps = env("GTR_RECORDING_FPS", "30");
        int startWaitSeconds = parsePositiveInt(env("GTR_RECORDING_START_WAIT_SECONDS", "90"), "GTR_RECORDING_START_WAIT_SECONDS");
        String xvfbScreen = env("GTR_RECORDING_XVFB_SCREEN", "1920x1080x24");
        boolean setDefaultAudio = truthy(env("GTR_RECORDING_AUDIO_SET_DEFAULT", "false"));
        String safeRecordingName = sanitizePathComponent(recordingName);
        File output = new File(recordingDir, safeRecordingName + "-" + timestamp + ".mp4");
        File metadata = new File(recordingDir, safeRecordingName + "-" + timestamp + ".json");
        File startSignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".start");
        File readySignal = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + ".ready");
        File alsoftConfig = new File(recordingDir, "." + safeRecordingName + "-" + timestamp + "-alsoft.conf");
        String audioSinkName = env("GTR_RECORDING_AUDIO_SINK_NAME", "gametest_recorder_" + timestamp);
        String audioSource = audioSinkName + ".monitor";
        File keptRunDir = new File(recordingDir, safeRecordingName + "-" + timestamp + "-run");
        String recordingStartedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String xvfbDisplay = env("GTR_RECORDING_XVFB_DISPLAY", "");
        Process xvfbProcess = null;
        Process gradleProcess = null;
        Process ffmpegProcess = null;
        Integer ffmpegStatus = null;
        String audioSinkId = "";
        String previousDefaultSinkId = "";
        int gradleStatus = 1;
        boolean videoSaved = false;
        boolean audioSaved = false;
        boolean recordingStartObserved = false;
        boolean ffmpegExitedBeforeShutdown = false;
        Map<String, String> processEnvironment = new LinkedHashMap<>(System.getenv());
        List<String> gradleCommand = List.of(
                "./gradlew",
                "--no-daemon",
                "-P" + getRecordingAudioDeviceProjectProperty().get()
                        + "=FabricModdingConventions Recording Null Sink",
                getRunTaskName().get()
        );

        recordingDir.mkdirs();
        deleteIfExists(startSignal.toPath());
        deleteIfExists(readySignal.toPath());

        try {
            if (xvfbDisplay.isBlank()) {
                xvfbDisplay = findFreeXvfbDisplay();
            }
            if (!xvfbDisplay.startsWith(":")) {
                xvfbDisplay = ":" + xvfbDisplay;
            }
            getLogger().lifecycle("Starting virtual X display {} ({})...", xvfbDisplay, xvfbScreen);
            xvfbProcess = new ProcessBuilder("Xvfb", xvfbDisplay, "-screen", "0", xvfbScreen, "-nolisten", "tcp")
                    .redirectErrorStream(true)
                    .start();
            waitForXDisplay(xvfbDisplay, xvfbProcess);

            audioSinkId = createVirtualAudioSink(audioSinkName);
            if (setDefaultAudio) {
                previousDefaultSinkId = currentDefaultAudioSinkId();
                runCommand(List.of("wpctl", "set-default", audioSinkId));
            }

            Files.writeString(alsoftConfig.toPath(), """
                    [general]
                    drivers = pulse

                    [pulse]
                    device = %s
                    allow-moves = false
                    """.formatted(audioSinkName), StandardCharsets.UTF_8);
            getLogger().lifecycle("Routing GameTest audio to virtual PipeWire sink: {}", audioSinkName);
            getLogger().lifecycle("Recording audio from PulseAudio monitor source: {}", audioSource);

            processEnvironment.clear();
            processEnvironment.putAll(System.getenv());
            processEnvironment.put("DISPLAY", xvfbDisplay);
            processEnvironment.put("ALSOFT_CONF", alsoftConfig.getAbsolutePath());
            processEnvironment.put("ALSOFT_DRIVERS", "pulse");
            processEnvironment.put("PULSE_SINK", audioSinkName);
            processEnvironment.put("GTR_RECORDING_AUDIO_DEVICE", "FabricModdingConventions Recording Null Sink");
            processEnvironment.put("GTR_RECORDING_MANAGED_XVFB", "true");
            processEnvironment.put("GTR_RECORDING_NAME", recordingName);
            processEnvironment.put("GTR_RECORDING_PROFILE", recordingProfile);
            processEnvironment.put("GTR_RECORDING_TRACE", recordingTrace);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_NAME", recordingName);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_PROFILE", recordingProfile);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_TRACE", recordingTrace);
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_START_SIGNAL", startSignal.getAbsolutePath());
            processEnvironment.put("CLIENT_GAMETEST_RECORDING_READY_SIGNAL", readySignal.getAbsolutePath());
            if (!recordingProfile.isBlank()) {
                processEnvironment.putIfAbsent("CLIENT_GAMETEST_PROFILE", recordingProfile);
            }

            getLogger().lifecycle("Starting client GameTest...");
            ProcessBuilder gradleBuilder = new ProcessBuilder(gradleCommand);
            gradleBuilder.directory(projectDir);
            gradleBuilder.redirectErrorStream(true);
            gradleBuilder.environment().clear();
            gradleBuilder.environment().putAll(processEnvironment);
            gradleProcess = gradleBuilder.start();
            Thread logThread = streamToStdout(gradleProcess, "client-gametest-output");

            getLogger().lifecycle("Waiting for client GameTest recording-start signal...");
            long signalDeadline = System.currentTimeMillis() + startWaitSeconds * 1000L;
            while (System.currentTimeMillis() < signalDeadline && !startSignal.exists() && gradleProcess.isAlive()) {
                sleep(100L);
            }

            recordingStartObserved = startSignal.exists();
            if (recordingStartObserved) {
                getLogger().lifecycle("Recording virtual display {} to {}", xvfbDisplay, output.getAbsolutePath());
                String videoSize = videoSizeFromXvfbScreen(xvfbScreen);
                List<String> ffmpegCommand = List.of(
                        "ffmpeg", "-y",
                        "-f", "x11grab", "-framerate", fps, "-video_size", videoSize, "-draw_mouse", "0", "-i", xvfbDisplay + ".0",
                        "-f", "pulse", "-thread_queue_size", "1024", "-i", audioSource,
                        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "160k", "-shortest", output.getAbsolutePath()
                );
                ProcessBuilder ffmpegBuilder = new ProcessBuilder(ffmpegCommand);
                ffmpegBuilder.directory(projectDir);
                ffmpegBuilder.redirectErrorStream(true);
                ffmpegBuilder.environment().putAll(processEnvironment);
                ffmpegProcess = ffmpegBuilder.start();
                streamToStdout(ffmpegProcess, "ffmpeg-output");
                Files.writeString(readySignal.toPath(), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
            }

            gradleStatus = gradleProcess.waitFor();
            logThread.join(1000L);
        } catch (IOException exception) {
            throw new UncheckedIOException("Client GameTest recording failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while recording client GameTest.", exception);
        } finally {
            if (ffmpegProcess != null) {
                ffmpegExitedBeforeShutdown = !ffmpegProcess.isAlive();
                if (ffmpegProcess.isAlive()) {
                    runCommand(List.of("kill", "-INT", Long.toString(ffmpegProcess.pid())), Map.of(), true);
                    waitForProcess(ffmpegProcess, 10, TimeUnit.SECONDS);
                    if (ffmpegProcess.isAlive()) {
                        ffmpegProcess.destroyForcibly();
                        waitForProcess(ffmpegProcess, 2, TimeUnit.SECONDS);
                    }
                }
                if (!ffmpegProcess.isAlive()) {
                    ffmpegStatus = ffmpegProcess.exitValue();
                }
            }
            if (!previousDefaultSinkId.isBlank()) {
                runCommand(List.of("wpctl", "set-default", previousDefaultSinkId), Map.of(), true);
            }
            if (!audioSinkId.isBlank()) {
                runCommand(List.of("pw-cli", "destroy", audioSinkId), Map.of(), true);
            }
            if (xvfbProcess != null && xvfbProcess.isAlive()) {
                xvfbProcess.destroy();
                waitForProcess(xvfbProcess, 2, TimeUnit.SECONDS);
                if (xvfbProcess.isAlive()) {
                    xvfbProcess.destroyForcibly();
                }
            }
            deleteIfExists(startSignal.toPath());
            deleteIfExists(readySignal.toPath());
            deleteIfExists(alsoftConfig.toPath());
        }

        if (output.isFile() && runCommand(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", output.getAbsolutePath()), Map.of(), true).status() == 0) {
            videoSaved = true;
            getLogger().lifecycle("Recording saved: {}", output.getAbsolutePath());
        }
        if (videoSaved) {
            CommandResult audioProbe = runCommand(List.of("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_type", "-of", "csv=p=0", output.getAbsolutePath()), Map.of(), true);
            if (!audioProbe.output().trim().isBlank()) {
                audioSaved = true;
                getLogger().lifecycle("Recording audio stream saved: {}", audioSource);
            }
        }

        File resolvedRunDir = runDir.exists() ? canonicalFile(runDir) : null;
        if (resolvedRunDir != null) {
            copyDirectory(resolvedRunDir, keptRunDir);
            getLogger().lifecycle("Client GameTest run directory saved: {}", keptRunDir.getAbsolutePath());
        }

        List<String> recordingFailures = recordingFailureMessages(
                gradleStatus,
                recordingStartObserved,
                ffmpegExitedBeforeShutdown,
                ffmpegStatus,
                videoSaved,
                audioSaved,
                resolvedRunDir != null,
                runDir.toString()
        );
        recordingFailures.forEach(getLogger()::error);

        Map<String, Object> metadataModel = new LinkedHashMap<>();
        metadataModel.put("name", recordingName);
        metadataModel.put("profile", recordingProfile);
        metadataModel.put("trace", truthy(recordingTrace));
        metadataModel.put("startedAt", recordingStartedAt);
        metadataModel.put("finishedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        metadataModel.put("gradleStatus", gradleStatus);
        metadataModel.put("captureMode", "xvfb");
        metadataModel.put("fps", fps);
        metadataModel.put("xvfbScreen", xvfbScreen);
        metadataModel.put("video", output.getAbsolutePath());
        metadataModel.put("videoSaved", videoSaved);
        metadataModel.put("audioRequested", true);
        metadataModel.put("audioRoute", "virtual");
        metadataModel.put("audioSource", audioSource);
        metadataModel.put("audioSink", audioSinkName);
        metadataModel.put("audioSaved", audioSaved);
        metadataModel.put("ffmpegStatus", ffmpegStatus);
        metadataModel.put("recordingDirectory", canonicalFile(recordingDir).getPath());
        metadataModel.put("runDirectory", resolvedRunDir == null ? null : resolvedRunDir.getAbsolutePath());
        metadataModel.put("keptRunDirectory", keptRunDir.exists() ? keptRunDir.getAbsolutePath() : null);
        Map<String, Object> selectors = new LinkedHashMap<>();
        selectors.put("CLIENT_GAMETEST_PROFILE", processEnvironment.get("CLIENT_GAMETEST_PROFILE"));
        selectors.put("CLIENT_GAMETEST_ONLY", processEnvironment.get("CLIENT_GAMETEST_ONLY"));
        selectors.put("CLIENT_GAMETEST_SUITE", processEnvironment.get("CLIENT_GAMETEST_SUITE"));
        metadataModel.put("selectors", selectors);
        metadataModel.put("gradleArgs", List.copyOf(gradleCommand.subList(1, gradleCommand.size())));
        metadataModel.put("recordingFailures", List.copyOf(recordingFailures));

        try {
            Files.writeString(metadata.toPath(), RecordingJson.pretty(metadataModel) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write recording metadata: " + metadata, exception);
        }
        getLogger().lifecycle("Recording metadata saved: {}", metadata.getAbsolutePath());

        if (!recordingFailures.isEmpty()) {
            throw new GradleException(
                    "Client GameTest recording failed: " + String.join(" ", recordingFailures)
            );
        }
    }

    static List<String> recordingFailureMessages(
            int gradleStatus,
            boolean recordingStartObserved,
            boolean ffmpegExitedBeforeShutdown,
            Integer ffmpegStatus,
            boolean videoSaved,
            boolean audioSaved,
            boolean runDirectorySaved,
            String runDirectory
    ) {
        List<String> failures = new ArrayList<>();
        if (gradleStatus != 0) {
            failures.add("Client GameTest failed with status " + gradleStatus + ".");
        }
        if (!recordingStartObserved) {
            failures.add("Client GameTest recording-start signal was not observed.");
        }
        if (ffmpegExitedBeforeShutdown) {
            failures.add("ffmpeg exited before the client GameTest completed with status " + ffmpegStatus + ".");
        }
        if (!videoSaved) {
            failures.add("Recorded video is missing or invalid.");
        } else if (!audioSaved) {
            failures.add("Recording was saved without an audio stream.");
        }
        if (!runDirectorySaved) {
            failures.add("Client GameTest run directory was not found: " + runDirectory);
        }
        return List.copyOf(failures);
    }

    private void requireTools(Collection<String> names) {
        names.forEach(this::requireTool);
    }

    private void requireTool(String name) {
        CommandResult result = runCommand(List.of("sh", "-c", "command -v " + name), Map.of(), true);
        if (result.status() != 0) {
            throw new GradleException("Missing required tool: " + name);
        }
    }

    private CommandResult runCommand(List<String> command) {
        return runCommand(command, Map.of(), false);
    }

    private CommandResult runCommand(List<String> command, Map<String, String> environment, boolean allowFailure) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(getProjectDirectory().get().getAsFile());
            builder.redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0 && !allowFailure) {
                throw new GradleException("Command failed (" + status + "): " + String.join(" ", command)
                        + System.lineSeparator() + output);
            }
            return new CommandResult(status, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("Command failed to start: " + String.join(" ", command), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while running command: " + String.join(" ", command), exception);
        }
    }

    private String findFreeXvfbDisplay() {
        for (int number = 90; number <= 130; number++) {
            if (!new File("/tmp/.X" + number + "-lock").exists() && !new File("/tmp/.X11-unix/X" + number).exists()) {
                return Integer.toString(number);
            }
        }
        throw new GradleException("Expected to find a free X display between :90 and :130.");
    }

    private void waitForXDisplay(String displayName, Process xvfbProcess) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!xvfbProcess.isAlive()) {
                throw new GradleException("Xvfb exited before becoming ready.");
            }
            CommandResult check = runCommand(List.of("xdpyinfo", "-display", displayName), Map.of(), true);
            if (check.status() == 0) {
                return;
            }
            sleep(100L);
        }
        throw new GradleException("Timed out waiting for X display " + displayName + ".");
    }

    private String findPipeWireNodeIdByName(String nodeName) {
        CommandResult result = runCommand(List.of("pw-cli", "list-objects", "Node"), Map.of(), true);
        if (result.status() != 0) {
            return "";
        }
        String currentId = "";
        for (String line : result.output().lines().toList()) {
            var idMatcher = PIPEWIRE_ID_PATTERN.matcher(line);
            if (idMatcher.find()) {
                currentId = idMatcher.group(1);
            }
            if (line.contains("node.name") && line.contains("\"" + nodeName + "\"") && !currentId.isBlank()) {
                return currentId;
            }
        }
        return "";
    }

    private String createVirtualAudioSink(String sinkName) {
        runCommand(List.of(
                "pw-cli",
                "create-node",
                "adapter",
                "{ factory.name = support.null-audio-sink node.name = \"" + sinkName + "\" node.description = \"FabricModdingConventions Recording Null Sink\" media.class = Audio/Sink object.linger = true priority.driver = 1 priority.session = 1 node.autoconnect = false audio.position = [ FL FR ] }"
        ));
        for (int attempt = 0; attempt < 20; attempt++) {
            String id = findPipeWireNodeIdByName(sinkName);
            if (!id.isBlank()) {
                return id;
            }
            sleep(100L);
        }
        throw new GradleException("Expected to create a virtual PipeWire null sink for GameTest recording audio: " + sinkName);
    }

    private String currentDefaultAudioSinkId() {
        CommandResult result = runCommand(List.of("wpctl", "status"), Map.of(), true);
        if (result.status() != 0) {
            return "";
        }
        for (String line : result.output().lines().toList()) {
            if (line.contains("*")) {
                var matcher = DEFAULT_SINK_PATTERN.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return "";
    }

    private void copyDirectory(File source, File target) {
        if (target.exists()) {
            throw new GradleException("Refusing to overwrite existing kept run directory: " + target);
        }
        try (Stream<Path> sourcePaths = Files.walk(source.toPath())) {
            sourcePaths.forEach(sourcePath -> {
                Path relative = source.toPath().relativize(sourcePath);
                Path targetPath = target.toPath().resolve(relative);
                try {
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to copy client GameTest run directory from " + source + " to " + target, exception);
        }
    }

    private Thread streamToStdout(Process process, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException exception) {
                getLogger().debug("Stopped reading process output for {}.", name, exception);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static File projectFile(File projectDir, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(projectDir, path);
    }

    private static int parsePositiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new GradleException("Expected " + name + " to be a positive integer, got: " + value, exception);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean truthy(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    private static String sanitizePathComponent(String value) {
        String sanitized = (value == null ? "client-gametest" : value).replaceAll("[^A-Za-z0-9._-]", "-");
        while (sanitized.contains("--")) {
            sanitized = sanitized.replace("--", "-");
        }
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "client-gametest" : sanitized;
    }

    private static String videoSizeFromXvfbScreen(String xvfbScreen) {
        String[] parts = xvfbScreen.split("x");
        if (parts.length < 2) {
            throw new GradleException("Expected GTR_RECORDING_XVFB_SCREEN to look like WIDTHxHEIGHTxDEPTH, got: " + xvfbScreen);
        }
        return parts[0] + "x" + parts[1];
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for recording process.", exception);
        }
    }

    private static void waitForProcess(Process process, long timeout, TimeUnit unit) {
        try {
            process.waitFor(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for process to exit.", exception);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to delete temporary recording file: " + path, exception);
        }
    }

    private static File canonicalFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to resolve canonical file: " + file, exception);
        }
    }


    private record CommandResult(int status, String output) {
    }
}
