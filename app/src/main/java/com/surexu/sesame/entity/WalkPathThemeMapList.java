package com.surexu.sesame.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.idMap.PathThemeMapListMap;
import com.surexu.sesame.util.idMap.ForestHuntIdMap;
import com.surexu.sesame.util.idMap.MemberCreditSesameTaskListMap;

public class WalkPathThemeMapList extends IdAndName {
    private static List<WalkPathThemeMapList> list;
    public static String[] nickNames;
    public static String[] values;

    public WalkPathThemeMapList(String i, String n) {
        id = i;
        name = n;
    }

    public static List<WalkPathThemeMapList> getList() {
        // 每次重新加载：主题列表可能已被 initWalkPathThemeMap 同步更新到文件，
        // 若沿用首次静态缓存，UI 设置页的主题下拉会一直停留在首次进入时的空列表
        list = new ArrayList<>();
        PathThemeMapListMap.load();
        for (Map.Entry<String, String> entry : PathThemeMapListMap.getMap().entrySet()) {
            list.add(new WalkPathThemeMapList(entry.getKey(), entry.getValue()));
        }
        // 初始化 ChoiceModelField 需要的数组
        nickNames = new String[list.size()];
        values = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            nickNames[i] = list.get(i).name;
            values[i] = list.get(i).id;
        }
        return list;
    }

    public static void remove(String id) {
        getList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
    }

}
