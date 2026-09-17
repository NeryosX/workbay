package com.neryos.workbay.bus;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What one link is allowed to carry. SPEC.md §5.
 *
 * <p><b>An entry is a resource, or a tag of them.</b> Not components, not the mod it came from,
 * not a count. Every one of those is a second axis with its own toggle, its own tooltip and its own
 * way of being subtly wrong, and none of them is what a player means by "only iron goes here". XNet
 * ships four such toggles and LaserIO five filter <em>items</em>, and both are answering a routing
 * puzzle this mod deliberately is not (SPEC.md §0).
 *
 * <p><b>Tags are the one that was actually asked for</b>, and were out until it became clear what
 * they cost: "all ores into this chest" was nine rows a player had to list by hand and get wrong,
 * and every mod in the genre - XNet, EnderIO, LaserIO - ships a tag mode. OPEN_ISSUES #33.
 *
 * <p><b>An entry keeps its item even when it matches by tag.</b> That is the whole trick: the slot
 * still has a sprite to draw and a name to cycle through, so a tag entry is the item the player
 * dropped in with a different question attached, rather than a fifth kind of thing the panel has to
 * be able to show. It is also what makes stepping through an item's tags reversible - the item is
 * still there to step back to.
 *
 * <p><b>Whitelist and blacklist, one flag.</b> "Everything except cobblestone" is the same question
 * asked the other way round and it costs one boolean and one {@code !=}; a mod that answers only
 * the first half sends the player back to a chest full of cobblestone with nothing to do about it.
 *
 * <p>An <b>empty list means no filter at all</b>, in both modes. A deny list with nothing on it
 * denies nothing, which is the only reading that does not make a half-configured filter stop a
 * link dead.
 *
 * <p>The ids are ids, and which registry they name is the <em>link's</em> business: an item link's
 * are items, a fluid link's are fluids, a chemical link's are chemicals. That is why changing a
 * link's resource clears its filter ({@link BusConfig#withResource}) - the same nine ids read as
 * nonsense in the other registry, and silently keeping them is how a link starts refusing
 * everything for no visible reason. Energy has no identity to match on and therefore no filter.
 */
public record BusFilter(List<Entry> entries, boolean deny) {

    /**
     * One row. {@code id} always names the resource itself; {@code tag} is what it matches on when
     * it is present.
     *
     * <p>Encoded as a bare string when there is no tag, which is exactly what every saved world
     * already holds - so this reads old data with no migration, and writes nothing new until
     * somebody actually asks for a tag.
     */
    public record Entry(ResourceLocation id, Optional<ResourceLocation> tag) {

        private static final Codec<Entry> TAGGED = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("Id").forGetter(Entry::id),
            ResourceLocation.CODEC.optionalFieldOf("Tag").forGetter(Entry::tag)
        ).apply(i, Entry::new));

        public static final Codec<Entry> CODEC =
            Codec.either(ResourceLocation.CODEC, TAGGED).xmap(
                either -> either.map(Entry::of, entry -> entry),
                entry -> entry.tag().isEmpty()
                    ? Either.left(entry.id()) : Either.right(entry));

        public static Entry of(ResourceLocation id) {
            return new Entry(id, Optional.empty());
        }

        /** What this row is called on screen: the tag when there is one, else the resource. */
        public ResourceLocation shown() {
            return tag.orElse(id);
        }
    }

    /** One row of ghost slots in the panel, which is the room the links list has. */
    public static final int MAX = 9;

    public static final BusFilter NONE = new BusFilter(List.of(), false);

    public static final Codec<BusFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
        Entry.CODEC.listOf().optionalFieldOf("Entries", List.of())
            .forGetter(BusFilter::entries),
        Codec.BOOL.optionalFieldOf("Deny", false).forGetter(BusFilter::deny)
    ).apply(i, BusFilter::new));

    public BusFilter {
        entries = List.copyOf(entries);
    }

    /** One entry, allowed. What most links want and what a test says in one line. */
    public static BusFilter only(ResourceLocation id) {
        return new BusFilter(List.of(Entry.of(id)), false);
    }

    /** The plain ids, in order. What a test asserts and what a chemical filter is built from. */
    public List<ResourceLocation> ids() {
        return entries.stream().map(Entry::id).toList();
    }

    public static BusFilter ofIds(List<ResourceLocation> ids, boolean deny) {
        return new BusFilter(ids.stream().map(Entry::of).toList(), deny);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Whether this link may carry that stack. Empty allows everything, whichever mode it is in. */
    public boolean allows(ItemStack stack) {
        return entries.isEmpty() || listed(stack) != deny;
    }

    public boolean allows(FluidStack stack) {
        return entries.isEmpty() || listed(stack) != deny;
    }

    /**
     * The same question asked of a bare id, which is what a chemical has instead of a stack: there
     * is no {@code ChemicalStack} in this class's world and there must not be - it is loaded in
     * installs with no Mekanism in them. A chemical entry never carries a tag, because there is
     * nothing in the panel that could put one there. OPEN_ISSUES #41.
     */
    public boolean allowsId(ResourceLocation id) {
        return entries.isEmpty()
            || entries.stream().anyMatch(entry -> entry.id().equals(id)) != deny;
    }

    private boolean listed(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return entries.stream().anyMatch(entry -> entry.tag()
            .map(tag -> stack.is(TagKey.create(Registries.ITEM, tag)))
            .orElseGet(() -> entry.id().equals(id)));
    }

    private boolean listed(FluidStack stack) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        return entries.stream().anyMatch(entry -> entry.tag()
            .map(tag -> stack.is(TagKey.create(Registries.FLUID, tag)))
            .orElseGet(() -> entry.id().equals(id)));
    }

    public Optional<Entry> at(int slot) {
        return slot >= 0 && slot < entries.size() ? Optional.of(entries.get(slot)) : Optional.empty();
    }

    /**
     * Puts {@code id} in, or takes whatever is at {@code slot} out when it is empty.
     *
     * <p>The list has no holes: an entry removed from the middle closes up, and an id dropped on a
     * slot past the end is appended. A sparse nine-slot array would have to be serialised, synced
     * and compared with holes in it to buy a player nothing but the column their iron sits in.
     */
    public BusFilter with(int slot, Optional<ResourceLocation> id) {
        List<Entry> now = new ArrayList<>(entries);
        if (id.isEmpty()) {
            if (slot < 0 || slot >= now.size()) {
                return this;
            }
            now.remove(slot);
        } else if (now.stream().anyMatch(entry -> entry.id().equals(id.get()))) {
            return this;
        } else if (slot >= 0 && slot < now.size()) {
            now.set(slot, Entry.of(id.get()));
        } else if (now.size() < MAX) {
            now.add(Entry.of(id.get()));
        } else {
            return this;
        }
        return new BusFilter(now, deny);
    }

    /**
     * Replaces one row's tag: the next one round the ring, or none, which is the resource itself.
     *
     * <p>Which tags a row can be stepped through is not this class's business - it depends on the
     * registry the link names, and only the caller knows that. This takes the answer.
     */
    public BusFilter withTag(int slot, Optional<ResourceLocation> tag) {
        if (slot < 0 || slot >= entries.size()) {
            return this;
        }
        List<Entry> now = new ArrayList<>(entries);
        now.set(slot, new Entry(now.get(slot).id(), tag));
        return new BusFilter(now, deny);
    }

    public BusFilter withDeny(boolean nowDeny) {
        return new BusFilter(entries, nowDeny);
    }
}
