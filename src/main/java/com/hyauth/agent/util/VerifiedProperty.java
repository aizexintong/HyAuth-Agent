package com.hyauth.agent.util;

/**
 * 已通过 RSA 验签的 Yggdrasil 角色属性（如 textures 皮肤数据）。
 *
 * <p>不直接复用 {@code com.mojang.authlib.properties.Property}：
 * 该类在 Authlib 3.x 中是普通类（{@code getName()}），
 * 在 Authlib 6.x 中变成了 record（{@code name()}），
 * 直接调用会因方法不存在而在运行期报 {@code NoSuchMethodError}。
 * 因此自行持有字段，仅在最外层构造 {@code Property}（两种版本都存在三元构造器）。
 */
public final class VerifiedProperty {

    private final String name;
    private final String value;
    private final String signature;

    public VerifiedProperty(String name, String value, String signature) {
        this.name = name;
        this.value = value;
        this.signature = signature;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getSignature() {
        return signature;
    }

    @Override
    public String toString() {
        return "VerifiedProperty{name=" + name + ", signature=" + (signature != null ? "yes" : "no") + "}";
    }
}
