package com.surexu.sesame.util.idMap;

import java.util.Map;

import com.surexu.sesame.util.FileUtil;

public class GoldenBeansMallItemMap {

    private static final StringMapStore STORE = new StringMapStore(ignoredUserId -> FileUtil.getGoldenBeansMallItemMapFile());

    public static Map<String, String> getMap() {
        return STORE.getMap();
    }

    public static String get(String key) {
        return STORE.get(key);
    }

    public static void add(String key, String value) {
        STORE.add(key, value);
    }

    public static void load() {
        STORE.load(null);
    }

    public static boolean save() {
        return STORE.save(null);
    }

    public static void clear() {
        STORE.clear();
    }

}
