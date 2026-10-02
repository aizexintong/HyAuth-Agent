package net.minecraft.resources;

/** 替身：维度键（勘探切面靠 {@code dimension().location()} 认出是哪个维度）。 */
public class ResourceKey {

    private final ResourceLocation location;

    private ResourceKey(ResourceLocation location) {
        this.location = location;
    }

    public static ResourceKey create(ResourceLocation location) {
        return new ResourceKey(location);
    }

    public ResourceLocation location() {
        return location;
    }
}
