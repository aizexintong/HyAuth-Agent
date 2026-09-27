package com.hyauth.agent.util;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * LittleSkin 鉴权成功后得到的角色信息（不可变）。
 *
 * <p>它是原版 {@code GameProfile} / {@code ProfileResult} 的“中立”表示，
 * 由 {@link AuthlibProfileFactory} 按当前服务端 Authlib 版本转换成对应类型。
 */
public final class VerifiedProfile {

    private final UUID id;
    private final String name;
    private final List<VerifiedProperty> properties;

    public VerifiedProfile(UUID id, String name, List<VerifiedProperty> properties) {
        this.id = id;
        this.name = name;
        this.properties = properties == null
                ? Collections.<VerifiedProperty>emptyList()
                : Collections.unmodifiableList(properties);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<VerifiedProperty> getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "VerifiedProfile{id=" + id + ", name=" + name + ", properties=" + properties.size() + "}";
    }
}
