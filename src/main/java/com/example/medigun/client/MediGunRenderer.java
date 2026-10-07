package com.example.medigun.client;

import com.example.medigun.MediGunMod;
import com.example.medigun.item.MediGunItem;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** Looks for: geo/item/medigun.geo.json, animations/item/medigun.animation.json, textures/item/medigun.png */
public class MediGunRenderer extends GeoItemRenderer<MediGunItem> {
    public MediGunRenderer() {
        super(new DefaultedItemGeoModel<>(new Identifier(MediGunMod.MOD_ID, "medigun")));
    }
}
