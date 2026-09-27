package harness;

import com.hyauth.agent.config.ListManager;

/**
 * 配置热重载验证：程序启动时读一次白名单判定，等待脚本改写配置文件后再读一次。
 * <p>用法: java -javaagent:HyAuth-Agent-1.0.0.jar ... harness.Sleeper <探测玩家名> <等待毫秒>
 */
public final class Sleeper {

    public static void main(String[] args) throws Exception {
        String probe = args[0];
        long waitMs = Long.parseLong(args[1]);
        System.out.println("[HOTRELOAD] before: " + probe + " -> " + ListManager.isLittleSkinPlayer(probe)
                + ", 白名单人数=" + ListManager.whitelistSize());
        Thread.sleep(waitMs);
        System.out.println("[HOTRELOAD] after : " + probe + " -> " + ListManager.isLittleSkinPlayer(probe)
                + ", 白名单人数=" + ListManager.whitelistSize());
    }
}
