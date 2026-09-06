package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackofficeTotpTest {

    @Test
    void matchesRfc6238VectorUsingSixDigits() {
        String secret = BackofficeTotp.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        long counter = 59L / 30L;
        assertThat(BackofficeTotp.generate(secret, counter, 6)).isEqualTo("287082");
        assertThat(BackofficeTotp.verify(secret, "287082", Instant.ofEpochSecond(59), null)).isPresent();
    }

    @Test
    void acceptsOneStepClockDriftButRejectsReplayOfAcceptedCounter() {
        String secret = BackofficeTotp.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        long previousCounter = 59L / 30L;
        String previousCode = BackofficeTotp.generate(secret, previousCounter, 6);

        assertThat(BackofficeTotp.verify(secret, previousCode, Instant.ofEpochSecond(60), null)).isPresent();
        assertThat(BackofficeTotp.verify(secret, previousCode, Instant.ofEpochSecond(60), previousCounter)).isEmpty();
    }
}
