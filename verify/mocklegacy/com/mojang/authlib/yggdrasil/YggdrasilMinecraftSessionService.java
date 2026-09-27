package com.mojang.authlib.yggdrasil;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.properties.Property;
import harness.VanillaProbe;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 模拟 Authlib ≤ 5.x（Minecraft ≤ 1.20.4）的 YggdrasilMinecraftSessionService：
 * {@code GameProfile hasJoinedServer(GameProfile user, String serverId, InetAddress address)}
 */
public class YggdrasilMinecraftSessionService {

    public GameProfile hasJoinedServer(GameProfile user, String serverId, InetAddress address)
            throws AuthenticationUnavailableException {
        VanillaProbe.vanillaCalled = true;
        String username = user.getName();
        VanillaProbe.lastUsername = username;
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("Mojang:" + username).getBytes(StandardCharsets.UTF_8)), username);
        profile.getProperties().put("textures",
                new Property("textures", "MOJANG_SIGNED_VALUE", "MOJANG_SIGNATURE"));
        return profile;
    }
}
