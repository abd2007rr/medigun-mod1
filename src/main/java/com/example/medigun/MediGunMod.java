package com.example.medigun;

import com.example.medigun.item.MediGunItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class MediGunMod implements ModInitializer {
    public static final String MOD_ID = "medigun";

    public static final Item MEDIGUN = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "medigun"),
            new MediGunItem(new FabricItemSettings().maxCount(1)));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(MEDIGUN));
    }
}
