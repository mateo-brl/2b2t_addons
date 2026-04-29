package com.basefinder.adapter.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests purs (sans Baritone réel) de {@link BaritoneSettingsReflection}.
 *
 * On reproduit la structure {@code Setting<T>} extends {@code AbstractSetting}
 * avec un champ privé {@code value} → c'est exactement ce que Baritone expose,
 * et c'est tout ce que notre helper a besoin pour fonctionner.
 */
class BaritoneSettingsReflectionTest {

    /** Imite {@code baritone.api.Settings.AbstractSetting} : porte le field {@code value}. */
    static class FakeAbstractSetting<T> {
        @SuppressWarnings("unused") // accessed via reflection
        private T value;

        FakeAbstractSetting(T initial) {
            this.value = initial;
        }

        T get() {
            return value;
        }
    }

    /** Imite {@code baritone.api.Settings.Setting<T>} : sous-classe concrète. */
    static class FakeSetting<T> extends FakeAbstractSetting<T> {
        FakeSetting(T initial) {
            super(initial);
        }
    }

    /** Imite l'objet conteneur de settings. */
    static class FakeSettings {
        public final FakeSetting<Boolean> allowParkour = new FakeSetting<>(false);
        public final FakeSetting<Integer> maxFallHeightNoWater = new FakeSetting<>(3);
    }

    @Test
    void setBool_mutatesUnderlyingValue() {
        FakeSettings settings = new FakeSettings();
        assertFalse(settings.allowParkour.get());

        boolean result = BaritoneSettingsReflection.setBool(settings, "allowParkour", true);

        assertTrue(result);
        assertTrue(settings.allowParkour.get());
    }

    @Test
    void setInt_mutatesUnderlyingValue() {
        FakeSettings settings = new FakeSettings();
        assertEquals(3, settings.maxFallHeightNoWater.get());

        boolean result = BaritoneSettingsReflection.setInt(settings, "maxFallHeightNoWater", 6);

        assertTrue(result);
        assertEquals(6, settings.maxFallHeightNoWater.get());
    }

    @Test
    void unknownField_returnsFalseWithoutThrowing() {
        FakeSettings settings = new FakeSettings();

        boolean result = BaritoneSettingsReflection.setBool(settings, "thisFieldDoesNotExist", true);

        assertFalse(result);
    }

    @Test
    void nullSettings_returnsFalseGracefully() {
        assertFalse(BaritoneSettingsReflection.setBool(null, "anything", true));
        assertFalse(BaritoneSettingsReflection.setInt(null, "anything", 42));
    }

    @Test
    void nullName_returnsFalse() {
        FakeSettings settings = new FakeSettings();
        assertFalse(BaritoneSettingsReflection.setBool(settings, null, true));
    }
}
