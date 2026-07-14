package io.github.brainage04.fabricmoddingconventions.gradle;

final class RecordingCommandFailure extends RuntimeException {
    RecordingCommandFailure(String message) {
        super(message);
    }
}
