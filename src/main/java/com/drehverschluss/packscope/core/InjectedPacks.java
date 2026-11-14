package com.drehverschluss.packscope.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Packs a mod puts directly into the resource stack, without the pack repository (Konzept 3.1, Phase 5C; e.g. KubeJS
 * changes the list of {@code PackRepository.openAllSelected()}). They have no entry in the pack screen. Pure logic
 * without Minecraft: where they sit in the real stack, and where they go when the order in a pack screen changes.
 *
 * <p>Position rules, taken from the real stack of the last resource reload:
 * <ul>
 *     <li>{@link Anchor#TOP}: no repository pack above it → always on top;</li>
 *     <li>{@link Anchor#ABOVE_VANILLA}: directly above {@code vanilla} → stays there;</li>
 *     <li>{@link Anchor#ABOVE_TOP_ZIP}: the mod is known to put it directly above the highest zip pack (KubeJS
 *     {@code findAfterModsIndex}: after the last {@code FilePackResources}) → follows that pack when the order
 *     changes;</li>
 *     <li>{@link Anchor#NEIGHBOUR}: nothing fixed recognisable → directly above the nearest repository pack below it
 *     (rule of Phase 3A).</li>
 * </ul>
 */
public final class InjectedPacks {
    private InjectedPacks() {}

    public enum Anchor { TOP, ABOVE_VANILLA, ABOVE_TOP_ZIP, NEIGHBOUR }

    /**
     * One pack of the real stack.
     *
     * @param id            {@code PackResources.packId()}
     * @param zip           a zip pack ({@code FilePackResources})
     * @param aboveTopZip   put by its mod directly above the highest zip pack (known rule, e.g. KubeJS)
     */
    public record StackPack(String id, boolean zip, boolean aboveTopZip) {}

    /**
     * Where an injected pack sits.
     *
     * @param below     ids of the repository packs below it, nearest first
     * @param aboveMods above the mod packs ({@code mod_resources} and its children): rated like a resource pack, else
     *                  mod/vanilla info
     */
    public record Placement(String id, Anchor anchor, List<String> below, boolean aboveMods) {}

    /** An injected pack and its placement. */
    public record Injected(PackRef ref, Placement placement) {}

    /**
     * Finds the injected packs of a stack.
     *
     * @param stackBottomFirst the packs of the resource manager, bottom first
     * @param repositoryIds    ids of every pack of the pack repository, with all children
     * @param modIds           {@code mod_resources} and its children (the mod packs)
     */
    public static List<Placement> place(List<StackPack> stackBottomFirst, Set<String> repositoryIds, Set<String> modIds) {
        int modsTop = -1;
        int vanilla = -1;
        for (int i = 0; i < stackBottomFirst.size(); i++) {
            String id = stackBottomFirst.get(i).id();
            if (modIds.contains(id)) modsTop = i;
            if ("vanilla".equals(id) && vanilla < 0) vanilla = i;
        }
        if (modsTop < 0) modsTop = vanilla;
        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < stackBottomFirst.size(); i++) {
            StackPack p = stackBottomFirst.get(i);
            if (repositoryIds.contains(p.id())) continue;
            List<String> below = new ArrayList<>();
            StackPack nearestBelow = null;
            for (int j = i - 1; j >= 0; j--) {
                StackPack b = stackBottomFirst.get(j);
                if (!repositoryIds.contains(b.id())) continue;
                if (nearestBelow == null) nearestBelow = b;
                below.add(b.id());
            }
            boolean repoAbove = false;
            for (int j = i + 1; j < stackBottomFirst.size(); j++) {
                if (repositoryIds.contains(stackBottomFirst.get(j).id())) {
                    repoAbove = true;
                    break;
                }
            }
            Anchor anchor;
            if (!repoAbove) anchor = Anchor.TOP;
            else if (nearestBelow != null && "vanilla".equals(nearestBelow.id())) anchor = Anchor.ABOVE_VANILLA;
            else if (p.aboveTopZip() && nearestBelow != null && nearestBelow.zip()) anchor = Anchor.ABOVE_TOP_ZIP;
            else anchor = Anchor.NEIGHBOUR;
            out.add(new Placement(p.id(), anchor, List.copyOf(below), i > modsTop));
        }
        return out;
    }

    /**
     * Puts the injected packs into an order of the pack screen (top first, already expanded, without injected packs).
     *
     * @param injected in the order of the real stack, bottom first (packs with the same anchor keep their order)
     */
    public static List<PackRef> insert(List<PackRef> topFirst, List<Injected> injected) {
        if (injected.isEmpty()) return topFirst;
        List<PackRef> list = new ArrayList<>(topFirst);
        Map<String, PackRef> lastOfGroup = new HashMap<>();
        for (Injected inj : injected) {
            Placement p = inj.placement();
            PackRef anchorRef = switch (p.anchor()) {
                case TOP -> null;
                case ABOVE_VANILLA -> find(list, "vanilla");
                case ABOVE_TOP_ZIP -> topZip(list);
                case NEIGHBOUR -> firstPresent(list, p.below());
            };
            Anchor anchor = p.anchor() == Anchor.ABOVE_TOP_ZIP && anchorRef == null ? Anchor.TOP : p.anchor();
            String group = anchor + "|" + (anchorRef == null ? "" : anchorRef.id());
            PackRef last = lastOfGroup.get(group);
            int at;
            if (last != null) at = list.indexOf(last);
            else if (anchor == Anchor.TOP) at = 0;
            else if (anchorRef != null) at = list.indexOf(anchorRef);
            else at = list.size();
            list.add(at, inj.ref());
            lastOfGroup.put(group, inj.ref());
        }
        return list;
    }

    private static PackRef find(List<PackRef> list, String id) {
        for (PackRef r : list) if (!r.injected() && r.id().equals(id)) return r;
        return null;
    }

    private static PackRef firstPresent(List<PackRef> list, List<String> ids) {
        for (String id : ids) {
            PackRef r = find(list, id);
            if (r != null) return r;
        }
        return null;
    }

    /** Highest zip pack of the order (top first), not injected. */
    private static PackRef topZip(List<PackRef> list) {
        for (PackRef r : list) if (!r.injected() && r.kind() == ScanKind.ZIP) return r;
        return null;
    }
}
