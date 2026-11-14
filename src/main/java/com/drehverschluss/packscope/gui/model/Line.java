package com.drehverschluss.packscope.gui.model;

import java.util.Arrays;
import java.util.List;

/**
 * One piece of text for the UI, still without Minecraft components: a translation key with arguments and a tone
 * (colour). The drawing side turns it into a component; the unit tests check keys and arguments.
 *
 * @param args {@link String} (shown as it is), {@link Line} (translated itself) or {@link Parts} (joined with ", ")
 */
public record Line(Tone tone, String key, List<Object> args) {
    public Line {
        args = List.copyOf(args);
    }

    public static Line of(Tone tone, String key, Object... args) {
        return new Line(tone, key, Arrays.asList(args));
    }

    public static Line of(String key, Object... args) {
        return of(Tone.NORMAL, key, args);
    }

    public Line withTone(Tone t) {
        return new Line(t, key, args);
    }

    /** Several values joined with ", " (e.g. "3 files, 12 language entries"). */
    public record Parts(List<Object> parts) {
        public Parts {
            parts = List.copyOf(parts);
        }
    }

    /** Text colours. */
    public enum Tone {
        NORMAL(0xFFFFFFFF),
        /** Neutral statuses (wins/loses/both). */
        NEUTRAL(0xFFE0E0E0),
        /** Info and intended conflicts. */
        GREY(0xFFA0A0A0),
        DARK_GREY(0xFF808080),
        RED(0xFFFF5555),
        YELLOW(0xFFFFFF55),
        GREEN(0xFF55FF55),
        HEADER(0xFFFFFFFF);

        public final int argb;

        Tone(int argb) {
            this.argb = argb;
        }
    }
}
