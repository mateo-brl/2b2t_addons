package com.basefinder.adapter.baritone;

import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SEULE reflection restante sur Baritone après l'étape 6 (audit/05 §5).
 *
 * Les classes {@code baritone.api.Setting} ne sont pas exposées comme interfaces
 * stables, donc l'API publique ne permet pas de muter la valeur d'un setting
 * sans casser via getName/getValue/setValue. On contourne en accédant au champ
 * privé {@code value} de la classe parent {@code Setting} — c'est documenté
 * comme la voie de moindre résistance par le projet Baritone lui-même.
 *
 * Toute autre reflection vit ailleurs (voir {@link BaritoneApi}) et l'objectif
 * est qu'elle disparaisse au profit d'une dep {@code compileOnly} sur
 * {@code baritone.api} dès qu'on aura figé la version utilisée par RusherHack.
 */
public final class BaritoneSettingsReflection {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaritoneSettingsReflection");

    private BaritoneSettingsReflection() {
    }

    public static boolean setBool(Object settings, String name, boolean value) {
        return setValue(settings, name, value);
    }

    public static boolean setInt(Object settings, String name, int value) {
        return setValue(settings, name, value);
    }

    /**
     * @return {@code true} si la mutation a réussi, {@code false} sinon. Loggué
     *         en debug pour ne pas spammer si Baritone évolue.
     */
    private static boolean setValue(Object settings, String name, Object value) {
        if (settings == null || name == null) {
            return false;
        }
        try {
            Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            if (setting == null) {
                return false;
            }
            Field valueField = setting.getClass().getSuperclass().getDeclaredField("value");
            valueField.setAccessible(true);
            valueField.set(setting, value);
            return true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.debug("Could not set Baritone setting '{}': {}", name, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error setting Baritone setting '{}': {}", name, e.getMessage());
            return false;
        }
    }
}
