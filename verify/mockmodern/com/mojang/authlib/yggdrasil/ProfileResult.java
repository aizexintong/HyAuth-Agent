package com.mojang.authlib.yggdrasil;

import com.mojang.authlib.GameProfile;

/** 模拟 Authlib 6.x 的 ProfileResult（真实实现为 record，含 (GameProfile) 构造器）。 */
public record ProfileResult(GameProfile profile) {
}
