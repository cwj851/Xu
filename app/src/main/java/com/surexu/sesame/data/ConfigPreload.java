package com.surexu.sesame.data;

import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.idMap.AnimalIdMap;
import com.surexu.sesame.util.idMap.AntDodoTaskListMap;
import com.surexu.sesame.util.idMap.AntFarmDoFarmTaskListMap;
import com.surexu.sesame.util.idMap.AntFarmDrawMachineTaskListMap;
import com.surexu.sesame.util.idMap.AntForestHuntTaskListMap;
import com.surexu.sesame.util.idMap.AntForestVitalityTaskListMap;
import com.surexu.sesame.util.idMap.AntMemberTaskListMap;
import com.surexu.sesame.util.idMap.AntOceanAntiepTaskListMap;
import com.surexu.sesame.util.idMap.AntOceanFishBlackListMap;
import com.surexu.sesame.util.idMap.AntOrchardTaskListMap;
import com.surexu.sesame.util.idMap.AntSportsTaskListMap;
import com.surexu.sesame.util.idMap.AntStallTaskListMap;
import com.surexu.sesame.util.idMap.BeachIdMap;
import com.surexu.sesame.util.idMap.CooperationIdMap;
import com.surexu.sesame.util.idMap.FarmOrnamentsIdMap;
import com.surexu.sesame.util.idMap.GameCenterMallItemMap;
import com.surexu.sesame.entity.AlipayGoldenBeansMallItem;
import com.surexu.sesame.entity.AlipayWelfareFundTaskList;
import com.surexu.sesame.util.idMap.GoldenBeansMallItemMap;
import com.surexu.sesame.util.idMap.GoldenBeansTaskListMap;
import com.surexu.sesame.util.idMap.MarathonIdMap;
import com.surexu.sesame.util.idMap.WelfareFundTaskListMap;
import com.surexu.sesame.util.idMap.MemberBenefitIdMap;
import com.surexu.sesame.util.idMap.MemberCreditSesameTaskListMap;
import com.surexu.sesame.util.idMap.MonopolyTaskListMap;
import com.surexu.sesame.util.idMap.NewAncientTreeIdMap;
import com.surexu.sesame.util.idMap.PathThemeMapListMap;
import com.surexu.sesame.util.idMap.PlantSceneIdMap;
import com.surexu.sesame.util.idMap.PromiseSimpleTemplateIdMap;
import com.surexu.sesame.util.idMap.ReserveIdMap;
import com.surexu.sesame.util.idMap.TreeIdMap;
import com.surexu.sesame.util.idMap.UserIdMap;
import com.surexu.sesame.util.idMap.VitalityBenefitIdMap;
import com.surexu.sesame.util.idMap.ForestHuntIdMap;
import com.surexu.sesame.util.idMap.rpcRequestMap;

/**
 * 配置相关的预加载逻辑（原 SettingsActivity / NewSettingsActivity 中的初始化）。
 * 新 UI 界面复用同一套数据，必须在这里完成 IdMap 加载与 ConfigV2.load，
 * 否则 SELECT_ONE / SELECT 等字段的选项列表为空。
 */
public final class ConfigPreload {

    private ConfigPreload() {
    }

    /** 最近一次 prepare 实际加载过配置的 userId（null 表示「默认」账号）；从未加载过为 null 且 configLoaded=false。 */
    private static boolean configLoaded = false;
    private static String lastPreparedUserId = null;

    public static void prepare(String userId) {
        UserIdMap.setCurrentUserId(userId);
        UserIdMap.load(userId);
        CooperationIdMap.load(userId);
        VitalityBenefitIdMap.load(userId);
        GameCenterMallItemMap.load(userId);
        FarmOrnamentsIdMap.load(userId);
        MemberBenefitIdMap.load(userId);
        PromiseSimpleTemplateIdMap.load(userId);
        TreeIdMap.load();
        ReserveIdMap.load();
        AnimalIdMap.load();
        MarathonIdMap.load();
        NewAncientTreeIdMap.load();
        BeachIdMap.load();
        PlantSceneIdMap.load();
        rpcRequestMap.load();
        ForestHuntIdMap.load();
        MemberCreditSesameTaskListMap.load();
        AntForestVitalityTaskListMap.load();
        AntForestHuntTaskListMap.load();
        AntFarmDoFarmTaskListMap.load();
        AntFarmDrawMachineTaskListMap.load();
        AntDodoTaskListMap.load();
        AntOceanAntiepTaskListMap.load();
        AntOceanFishBlackListMap.load();
        AntOrchardTaskListMap.load();
        AntStallTaskListMap.load();
        AntSportsTaskListMap.load();
        PathThemeMapListMap.load();
        AntMemberTaskListMap.load();
        GoldenBeansTaskListMap.load();
        GoldenBeansMallItemMap.load();
        // 候选实体的静态缓存是注入进程写的，App 进程必须清一次才会重建
        AlipayGoldenBeansMallItem.clear();
        WelfareFundTaskListMap.load();
        AlipayWelfareFundTaskList.clear();
        MonopolyTaskListMap.load();
        // 同一账号的配置只加载一次：二级/三级/四级配置页的 onCreate 都会调用 prepare，
        // 若每次都 ConfigV2.load，磁盘值会把内存中尚未保存的修改整体覆盖，
        // 表现为"配置改完返回没保存"（典型：三级改开关后点选择类字段进四级，返回时改动已丢）。
        // 账号切换（userId 变化）时必须重载对应账号的配置；注入进程 restart 的加载走
        // ApplicationHook 直调 ConfigV2.load，不走本方法，不受此幂等影响。
        if (!configLoaded || !java.util.Objects.equals(lastPreparedUserId, userId)) {
            ConfigV2.load(userId);
            lastPreparedUserId = userId;
            configLoaded = true;
        }
    }

    /** 强制重载指定账号配置（导入配置后调用，让新内容立即进入内存与 UI）。 */
    public static void reload(String userId) {
        ConfigV2.load(userId);
        lastPreparedUserId = userId;
        configLoaded = true;
    }

    /**
     * 清除预加载状态与 ConfigV2 内存（删除配置后调用）。
     * 否则 configLoaded 仍为 true、内存旧值还在，同进程再次进入配置页不会重载，
     * 改动任意字段保存时会把整份旧配置重新写盘，表现为「删除的配置复活」。
     */
    public static void clear() {
        configLoaded = false;
        lastPreparedUserId = null;
        ConfigV2.INSTANCE.setModelFieldsMap(null);
        ConfigV2.unload();
    }

    public static boolean isEmpty(String userId) {
        return StringUtil.isEmpty(userId);
    }

    public static java.io.File getConfigFile(String userId) {
        if (StringUtil.isEmpty(userId)) {
            return FileUtil.getDefaultConfigV2File();
        }
        return FileUtil.getConfigV2File(userId);
    }
}
