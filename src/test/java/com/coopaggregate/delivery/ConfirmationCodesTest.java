package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The temporary demo code (SMS_DEMO_CODE). The values here are test values only. */
@ExtendWith(OutputCaptureExtension.class)
class ConfirmationCodesTest {

    private static final String TEST_DEMO = "864213";
    private static final String WARNING = "A demo confirmation code is enabled. Remove SMS_DEMO_CODE after the demo.";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void notSetByDefault(CapturedOutput output) {
        ConfirmationCodes codes = new ConfirmationCodes(encoder);

        assertFalse(codes.isDemoCode(TEST_DEMO));
        assertFalse(output.getAll().contains("demo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emptyMeansOff(String value, CapturedOutput output) {
        ConfirmationCodes codes = new ConfirmationCodes(encoder, value);

        assertFalse(codes.isDemoCode(TEST_DEMO));
        assertFalse(codes.isDemoCode(value));
        assertFalse(output.getAll().contains(WARNING));
    }

    @Test
    void whenSetOnlyThatCodeIsAccepted() {
        ConfirmationCodes codes = new ConfirmationCodes(encoder, TEST_DEMO);

        assertTrue(codes.isDemoCode(TEST_DEMO));
        assertFalse(codes.isDemoCode("864214"));
        assertFalse(codes.isDemoCode(null));
        assertFalse(codes.isDemoCode(""));
    }

    @Test
    void warnsOnceAtStartupWithoutTheCode(CapturedOutput output) {
        new ConfirmationCodes(encoder, TEST_DEMO);

        String logs = output.getAll();
        assertTrue(logs.contains("WARN"));
        assertTrue(logs.contains(WARNING));
        assertEquals(logs.indexOf(WARNING), logs.lastIndexOf(WARNING));
        assertFalse(logs.contains(TEST_DEMO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "12345a", "12 345"})
    void aValueThatIsNotSixDigitsStopsStartupWithoutRepeatingIt(String value, CapturedOutput output) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ConfirmationCodes(encoder, value));

        assertEquals("SMS_DEMO_CODE must be exactly 6 digits, or not set.", e.getMessage());
        assertFalse(output.getAll().contains(value));
    }
}
