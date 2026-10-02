package net.minecraft.resources;

/** 替身：资源路径。 */
public class ResourceLocation {

    private final String id;

    private ResourceLocation(String id) {
        this.id = id;
    }

    public static ResourceLocation of(String id) {
        return new ResourceLocation(id);
    }

    public static ResourceLocation parse(String id) {
        return new ResourceLocation(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
