package com.drehverschluss.packscope.gui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.drehverschluss.packscope.core.PackStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5C: combined status of a pack with children (e.g. eatinganimations_compat) for its entry in the pack screen. */
class ParentStatusTest {
    @Test
    void warningsFirstThenWinsAndLosses() {
        assertEquals(PackStatus.COMPLETELY_COVERED, TooltipModel.parentStatus(List.of(PackStatus.WINS, PackStatus.COMPLETELY_COVERED)));
        assertEquals(PackStatus.AGAINST_MARKING, TooltipModel.parentStatus(List.of(PackStatus.REPLACES_SOUNDS, PackStatus.AGAINST_MARKING)));
        assertEquals(PackStatus.WINS_AND_LOSES, TooltipModel.parentStatus(List.of(PackStatus.WINS, PackStatus.LOSES)));
        assertEquals(PackStatus.WINS_AND_LOSES, TooltipModel.parentStatus(List.of(PackStatus.NO_CONFLICT, PackStatus.WINS_AND_LOSES)));
        assertEquals(PackStatus.WINS, TooltipModel.parentStatus(List.of(PackStatus.WINS, PackStatus.INTENDED_ONLY)));
        assertEquals(PackStatus.LOSES, TooltipModel.parentStatus(List.of(PackStatus.NO_CONFLICT, PackStatus.LOSES)));
        assertEquals(PackStatus.INTENDED_ONLY, TooltipModel.parentStatus(List.of(PackStatus.NO_CONFLICT, PackStatus.INTENDED_ONLY)));
        assertEquals(PackStatus.NO_CONFLICT, TooltipModel.parentStatus(List.of(PackStatus.NO_CONFLICT, PackStatus.NO_CONFLICT)));
        assertEquals(PackStatus.NO_CONFLICT, TooltipModel.parentStatus(List.of()));
    }
}
