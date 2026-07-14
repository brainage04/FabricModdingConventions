package io.github.brainage04.fabricmoddingconventions;

public final class GameTestRecorderEnvironment {
    public static final String ENABLED_PROPERTY = "fabricmoddingconventions.clientGameTest";

    public static final String RECORDING_NAME_ENV = "CLIENT_GAMETEST_RECORDING_NAME";
    public static final String RECORDING_PROFILE_ENV = "CLIENT_GAMETEST_RECORDING_PROFILE";
    public static final String RECORDING_TRACE_ENV = "CLIENT_GAMETEST_RECORDING_TRACE";
    public static final String START_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_START_SIGNAL";
    public static final String READY_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_READY_SIGNAL";

    public static final String TEST_PROFILE_ENV = "CLIENT_GAMETEST_PROFILE";
    public static final String TEST_ONLY_ENV = "CLIENT_GAMETEST_ONLY";
    public static final String TEST_SUITE_ENV = "CLIENT_GAMETEST_SUITE";

    private GameTestRecorderEnvironment() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }
}
