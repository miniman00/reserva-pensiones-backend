package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackofficeMfaCryptoTest {

    @Test
    void encryptsWithRandomIvAndRoundTripsSecret() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        BackofficeMfaCrypto crypto = new BackofficeMfaCrypto(true, key);

        String first = crypto.encrypt("JBSWY3DPEHPK3PXP");
        String second = crypto.encrypt("JBSWY3DPEHPK3PXP");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(crypto.decrypt(second)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void enabledMfaRejectsMissingOrWrongLengthMasterKey() {
        assertThatThrownBy(() -> new BackofficeMfaCrypto(true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
