package com.drehverschluss.packscope.gui.model;

import com.drehverschluss.packscope.core.PackStatus;
import com.drehverschluss.packscope.core.PackscopeSettings;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Which icon a pack gets and in which colour its status is written (Konzept 4: wins/loses/both neutral, only the
 * warnings stand out).
 */
public enum StatusLook {
    COVERED("covered", Line.Tone.RED),
    AGAINST_MARKING("against_marking", Line.Tone.YELLOW),
    REPLACES_SOUNDS("replaces_sounds", Line.Tone.YELLOW),
    WINS_AND_LOSES("wins_and_loses", Line.Tone.NEUTRAL),
    WINS("wins", Line.Tone.NEUTRAL),
    LOSES("loses", Line.Tone.NEUTRAL),
    INTENDED("intended", Line.Tone.GREY),
    CHECKING("checking", Line.Tone.GREY);

    /** Sprite name below {@code packscope:textures/gui/sprites/status/}. */
    public final String sprite;
    public final Line.Tone tone;

    StatusLook(String sprite, Line.Tone tone) {
        this.sprite = sprite;
        this.tone = tone;
    }

    /** Icon for a status, or null for "no icon" (no conflict, or intended only with {@code showIntendedSymbol} off). */
    @Nullable
    public static StatusLook of(PackStatus status, PackscopeSettings settings) {
        return switch (status) {
            case COMPLETELY_COVERED -> COVERED;
            case AGAINST_MARKING -> AGAINST_MARKING;
            case REPLACES_SOUNDS -> REPLACES_SOUNDS;
            case WINS_AND_LOSES -> WINS_AND_LOSES;
            case WINS -> WINS;
            case LOSES -> LOSES;
            case INTENDED_ONLY -> settings.showIntendedSymbol() ? INTENDED : null;
            case NO_CONFLICT -> null;
        };
    }

    /** Colour of a status in texts (also for statuses without an icon). */
    public static Line.Tone tone(PackStatus status) {
        return switch (status) {
            case COMPLETELY_COVERED -> Line.Tone.RED;
            case AGAINST_MARKING, REPLACES_SOUNDS -> Line.Tone.YELLOW;
            case WINS_AND_LOSES, WINS, LOSES -> Line.Tone.NEUTRAL;
            case INTENDED_ONLY, NO_CONFLICT -> Line.Tone.GREY;
        };
    }

    /** Translation key of a status name. */
    public static String key(PackStatus status) {
        return "packscope.status." + status.id().toLowerCase(Locale.ROOT);
    }
}
