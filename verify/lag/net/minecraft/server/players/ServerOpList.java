package net.minecraft.server.players;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 替身：op 名单（对应真实 StoredUserList，读取方法在父类上，这里直接放在本类）。 */
public class ServerOpList {
    private final Map<String, Object> entries = new LinkedHashMap<String, Object>();

    /** 测试用：把某个名字/UUID 加进"已是管理员"。 */
    public void addAdmin(String nameOrUuid) {
        entries.put(nameOrUuid, new Object());
    }

    public Object get(Object key) {
        return entries.get(key == null ? null : String.valueOf(key));
    }

    public String[] getUserList() {
        List<String> names = new ArrayList<String>(entries.keySet());
        return names.toArray(new String[0]);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
