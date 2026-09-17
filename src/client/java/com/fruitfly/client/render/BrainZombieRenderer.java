package com.fruitfly.client.render;

import com.fruitfly.entity.BrainZombieEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Renders the connectome-controlled host with the vanilla zombie humanoid model. */
public final class BrainZombieRenderer extends MobRenderer<BrainZombieEntity, HumanoidModel<BrainZombieEntity>> {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public BrainZombieRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
    }

    @Override
    protected void scale(BrainZombieEntity zombie, PoseStack poseStack, float partialTick) {
        // Keep the registered hitbox and vanilla zombie proportions; brain activity is shown by HUD telemetry.
        poseStack.scale(1.0f, 1.0f, 1.0f);
    }

    @Override
    public ResourceLocation getTextureLocation(BrainZombieEntity zombie) {
        return TEXTURE;
    }
}
