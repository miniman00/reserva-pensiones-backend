package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicLocationPrivacyTest {

    @Test
    void bulkCoordinatesAreRoundedToAboutOneHundredMeters() {
        assertThat(PublicLocationPrivacy.approximateCoordinate(-34.901234)).isEqualTo(-34.901d);
        assertThat(PublicLocationPrivacy.approximateCoordinate(-56.164987)).isEqualTo(-56.165d);
    }

    @Test
    void nullCoordinateRemainsNull() {
        assertThat(PublicLocationPrivacy.approximateCoordinate(null)).isNull();
    }
}
