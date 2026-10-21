package com.coopaggregate.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogSmsSenderTest {

    private static final String TEXT = "CoopAggregate: RCT-7K2Q, 300 kg Big, 21/10. If correct, give the manager "
            + "code 482193. If wrong, don't give the code, reply 2 RCT-7K2Q.";

    @Test
    void codesAreNotPrintedByDefault(CapturedOutput output) {
        new LogSmsSender(false).send("+250788123456", TEXT);

        assertFalse(output.getAll().contains("482193"));
    }

    @Test
    void codesArePrintedWhenAskedForLocalTesting(CapturedOutput output) {
        new LogSmsSender(true).send("+250788123456", TEXT);

        assertTrue(output.getAll().contains("give the manager code 482193"));
    }

    @Test
    void showingCodesIsAllowedWithTheLogProvider() {
        assertDoesNotThrow(() -> new SmsCodeLoggingGuard("log", true));
        assertDoesNotThrow(() -> new SmsCodeLoggingGuard("africastalking", false));
    }

    @Test
    void showingCodesWithARealProviderStopsTheApp() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SmsCodeLoggingGuard("africastalking", true));

        assertTrue(e.getMessage().startsWith("SMS_LOG_SHOW_CODES=true is only allowed with SMS_PROVIDER=log"));
    }

    @Test
    void appRefusesToStartWhenCodesWouldBePrintedWithRealSms() {
        new ApplicationContextRunner()
                .withUserConfiguration(SmsCodeLoggingGuard.class)
                .withPropertyValues("app.sms.provider=africastalking", "app.sms.log-show-codes=true")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(SmsCodeLoggingGuard.class)
                .withPropertyValues("app.sms.provider=log", "app.sms.log-show-codes=true")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
