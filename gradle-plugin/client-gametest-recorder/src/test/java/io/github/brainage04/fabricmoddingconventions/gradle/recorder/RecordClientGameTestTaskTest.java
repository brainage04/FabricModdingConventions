package io.github.brainage04.fabricmoddingconventions.gradle.recorder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordClientGameTestTaskTest {
    @Test
    void successfulGameTestStillFailsWhenFfmpegExitsEarly() {
        List<String> failures = RecordClientGameTestTask.recordingFailureMessages(
                0,
                true,
                true,
                1,
                true,
                true,
                true,
                "build/run/clientGameTest"
        );

        assertEquals(
                List.of("ffmpeg exited before the client GameTest completed with status 1."),
                failures
        );
    }

    @Test
    void missingRecordingArtifactsProduceEveryRelevantFailure() {
        List<String> failures = RecordClientGameTestTask.recordingFailureMessages(
                0,
                false,
                false,
                null,
                false,
                false,
                false,
                "build/run/clientGameTest"
        );

        assertTrue(failures.contains("Client GameTest recording-start signal was not observed."));
        assertTrue(failures.contains("Recorded video is missing or invalid."));
        assertTrue(failures.contains(
                "Client GameTest run directory was not found: build/run/clientGameTest"
        ));
    }

    @Test
    void completeCaptureHasNoRecordingFailures() {
        assertTrue(RecordClientGameTestTask.recordingFailureMessages(
                0,
                true,
                false,
                255,
                true,
                true,
                true,
                "build/run/clientGameTest"
        ).isEmpty());
    }
}
