package com.surexu.sesame.model.task.friendManage;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.SelectModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.entity.AlipayUser;
import com.surexu.sesame.entity.UserEntity;
import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.ToastUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 好友管理（单删好友清理）。
 *
 * 检测原理: 直接读支付宝本地社交缓存的 friendStatus 字段。
 *   friendStatus == 1  → 双向好友
 *   friendStatus != 1  → 单向好友(对方把你删了)
 * 这个字段由 UserIdMap.initUser() 从 AliAccountDaoOp.getAllFriends() 自动填充,
 * 不需要任何 RPC 探测。
 *
 * 删除日志独立写入 friendDeleteLog.txt, 不随每日清空。
 */
public class FriendManage extends ModelTask {

    private static final String TAG = FriendManage.class.getSimpleName();

    /** 删除日志文件(放在主目录, 不随日志目录轮转清空)。 */
    private static final File DELETE_LOG_FILE = new File(FileUtil.MAIN_DIRECTORY_FILE, "friendDeleteLog.txt");

    /** 广播通道: UI 进程 → 支付宝进程（删除在支付宝进程内执行 RPC）。 */
    private static final String ACTION_DELETE = "com.eg.android.AlipayGphone.sesame.friendManage";
    private static final String ACTION_RESULT = "com.surexu.sesame.friendManageResult";

    private BooleanModelField delSingleDeleted;

    @Override
    public String getName() {
        return "好友管理";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.OTHER;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(new SelectModelField("singleDeletedList", "单删好友列表",
                new java.util.LinkedHashSet<>(),
                () -> AlipayUser.getList(ue -> {
                    Integer fs = ue.getFriendStatus();
                    return fs == null || fs != 1;
                })));
        modelFields.addField(delSingleDeleted = new BooleanModelField("delSingleDeleted", "删除单删好友", false));
        return modelFields;
    }

    @Override
    public Boolean check() {
        return true;
    }

    @Override
    public void run() {
        // 每日限跑 1 次
        if (Status.hasFlagToday("FriendManage::dailyProbeDone")) {
            return;
        }
        Status.flagToday("FriendManage::dailyProbeDone");

        // ---------- 1. 读本地好友列表 + friendStatus ----------
        UserIdMap.initUser(UserIdMap.getCurrentUid());
        List<UserEntity> allFriends = new ArrayList<>(UserIdMap.getUserEntityCollection());

        List<UserEntity> singleDeletedList = new ArrayList<>();
        int validCount = 0;
        for (UserEntity ue : allFriends) {
            Integer fs = ue.getFriendStatus();
            if (fs != null && fs == 1) {
                validCount++;
            } else {
                singleDeletedList.add(ue);
            }
        }

        Log.record("好友管理👋单向好友检测: 本地[" + allFriends.size()
                + "]#双向[" + validCount + "]#单向[" + singleDeletedList.size() + "]");

        // ---------- 2. 写好友记录(不管开关) ----------
        String probeHeader = timePrefix() + "🔎 单向好友检测: 本地" + allFriends.size()
                + "人#双向" + validCount + "#单向" + singleDeletedList.size() + "\n";
        FileUtil.append2File(probeHeader, DELETE_LOG_FILE);
        for (UserEntity sd : singleDeletedList) {
            String line = timePrefix() + "  ⚠️ 单向好友 | " + sd.getShowName()
                    + " | userId=" + sd.getUserId()
                    + " | friendStatus=" + sd.getFriendStatus() + "\n";
            FileUtil.append2File(line, DELETE_LOG_FILE);
            Log.record("好友管理👋单向好友[" + sd.getShowName() + "]#userId=" + sd.getUserId()
                    + "#friendStatus=" + sd.getFriendStatus());
        }

        // ---------- 3. 开关: 删除单删好友 ----------
        boolean deleteOn = delSingleDeleted.getValue();
        String switchLine = timePrefix() + (deleteOn ? "🟢 删除单删: 已开启" : "⚪️ 删除单删: 已关闭, 仅检测不删除") + "\n";
        FileUtil.append2File(switchLine, DELETE_LOG_FILE);
        if (!deleteOn) {
            return;
        }

        if (singleDeletedList.isEmpty()) {
            String line = timePrefix() + "✅ 无非双向好友, 无需删除\n";
            FileUtil.append2File(line, DELETE_LOG_FILE);
            return;
        }

        // ---------- 4. 删除限次: 每日最多 2 次 ----------
        int todayCount = Status.getIntFlagToday("FriendManage::delSingleDeleted");
        if (todayCount >= 2) {
            String line = timePrefix() + "🛑 今日已执行" + todayCount + "次删除, 达到上限, 跳过\n";
            FileUtil.append2File(line, DELETE_LOG_FILE);
            Log.record("好友管理👋今日已执行" + todayCount + "次删除, 达到上限, 跳过");
            return;
        }

        // ---------- 5. 执行删除 ----------
        String header = timePrefix() + "📋 本轮删除: 待删" + singleDeletedList.size()
                + "人 (今日第" + (todayCount + 1) + "/2次)"
                + " | 本地" + allFriends.size() + "人#双向" + validCount
                + "#单向" + singleDeletedList.size() + "\n";
        FileUtil.append2File(header, DELETE_LOG_FILE);

        doDelete(singleDeletedList);
        Status.setIntFlagToday("FriendManage::delSingleDeleted", todayCount + 1);
    }

    private static String timePrefix() {
        // 好友记录长期累积,必须带日期;同时带毫秒以满足 LogScreen 正则
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    /** 自动删除（支付宝进程内执行，RPC 桥可直接用）。 */
    private void doDelete(List<UserEntity> toDelete) {
        int successCount = 0;
        int failCount = 0;
        for (UserEntity ue : toDelete) {
            try {
                String account = ue.getAccount() != null ? ue.getAccount() : "";
                String res = FriendManageRpcCall.handleFriendship(ue.getUserId(), account);
                JSONObject jo = new JSONObject(res);
                String time = timePrefix();

                // handleFriendship 返回结构特判:
                //  - 支付宝删除好友接口返回 {"error":100, "errorMessage":""} 这种结构
                //  - 没有 resultCode 字段, checkResultCode 会判 false, 但 error=100 实际是成功
                //  - 真正失败的 error 是 305(对方不是好友) 之类
                boolean ok = MessageUtil.checkResultCode(TAG, jo);
                if (!ok) {
                    // handleFriendship 成功形态: success=true / resultCode=100 / error=100
                    if (jo.optBoolean("success")) ok = true;
                    if (!ok) {
                        Object rcObj = jo.opt("resultCode");
                        if (rcObj instanceof Number && ((Number) rcObj).intValue() == 100) ok = true;
                        else if (rcObj instanceof String && "100".equals(rcObj)) ok = true;
                    }
                    if (!ok) {
                        Object errObj = jo.opt("error");
                        if (errObj instanceof Number && ((Number) errObj).intValue() == 100) ok = true;
                        else if (errObj instanceof String && "100".equals(errObj)) ok = true;
                    }
                }

                if (ok) {
                    successCount++;
                    FileUtil.append2File(time + " ✅ 删除成功 | " + ue.getShowName()
                            + " | userId=" + ue.getUserId() + " | account=" + account + " | 自动\n", DELETE_LOG_FILE);
                    Log.other("好友管理👋删除单向好友[" + ue.getShowName() + "]成功");
                } else {
                    failCount++;
                    String err = jo.optString("error", jo.optString("resultCode", "?"));
                    String msg = jo.optString("errorMessage", jo.optString("resultDesc", ""));
                    FileUtil.append2File(time + " ❌ 删除失败 | " + ue.getShowName()
                            + " | userId=" + ue.getUserId() + " | err=" + err + " | msg=" + msg + " | 自动\n", DELETE_LOG_FILE);
                    Log.record(TAG + ".删除单向好友失败[" + ue.getShowName() + "]#" + err + " " + msg);
                }
                Thread.sleep(500 + (long) (Math.random() * 500));
            } catch (Throwable t) {
                failCount++;
                Log.printStackTrace(TAG + ".doDelete." + ue.getShowName(), t);
            }
        }
        Log.record("好友管理👋删除完成: 成功=" + successCount + " # 失败=" + failCount + " # 总计=" + toDelete.size());
    }

    /** 单删好友页面数据: 读取结果 + 单向列表。失败时返回 null 并已 Toast 提示。 */
    public static class SingleDeletedData {
        public String userId;
        public List<UserEntity> single;
        public int totalFriends;
    }

    /**
     * 加载单删好友数据(UI 进程): 读当前账号本地好友缓存, 筛出 friendStatus != 1 的单向好友。
     * 与 showSingleDeletedDialog 共用同一份数据源。
     */
    public static SingleDeletedData loadSingleDeleted(Context ctx) {
        if (ctx == null) return null;

        // 当前选中账号（与账号配置页同 key），未显式选中时取账号目录下第一个
        String userId = ctx.getSharedPreferences("sesame_ui_state", Context.MODE_PRIVATE)
                .getString("last_selected_user_id", null);
        if (StringUtil.isEmpty(userId)) {
            File[] dirs = FileUtil.CONFIG_DIRECTORY_FILE.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory()) {
                        userId = d.getName();
                        break;
                    }
                }
            }
        }
        if (StringUtil.isEmpty(userId)) {
            ToastUtil.show(ctx, "未检测到账号数据，请先打开支付宝等待好友列表加载");
            return null;
        }

        // 从本地缓存读好友列表 (load 是纯文件读取, UI 进程可用)
        UserIdMap.load(userId);
        List<UserEntity> allFriends = new ArrayList<>(UserIdMap.getUserEntityCollection());
        if (allFriends.isEmpty()) {
            ToastUtil.show(ctx, "本地无好友数据, 先跑一轮模块或打开支付宝等待好友列表加载");
            return null;
        }
        final List<UserEntity> single = new ArrayList<>();
        for (UserEntity ue : allFriends) {
            Integer fs = ue.getFriendStatus();
            if (fs != null && fs == 1) continue;
            single.add(ue);
        }
        SingleDeletedData data = new SingleDeletedData();
        data.userId = userId;
        data.single = single;
        data.totalFriends = allFriends.size();
        return data;
    }

    /**
     * 弹出"单向好友列表"对话框:搜索框 + 全选/反选 + 勾选列表 + 删除按钮。
     * 删除前二次确认。删除走广播到支付宝进程执行 RPC。
     */
    public static void showSingleDeletedDialog(Context ctx) {
        try {
            SingleDeletedData data = loadSingleDeleted(ctx);
            if (data == null) return;
            final List<UserEntity> single = data.single;
            int totalFriends = data.totalFriends;

            // 构建自定义布局
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 16);
            root.setPadding(pad, pad, pad, pad);

            // 搜索框
            EditText search = new EditText(ctx);
            search.setHint("搜索好友...");
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            root.addView(search, sp);

            // 全选/反选 按钮行
            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            Button btnSelectAll = new Button(ctx);
            btnSelectAll.setText("全选");
            Button btnInvert = new Button(ctx);
            btnInvert.setText("反选");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            btnRow.addView(btnSelectAll, bp);
            btnRow.addView(btnInvert, bp);
            root.addView(btnRow, sp);

            // 列表区域 (ScrollView)
            ScrollView scrollView = new ScrollView(ctx);
            LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 400));
            scrollView.setLayoutParams(scp);
            LinearLayout listContainer = new LinearLayout(ctx);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(listContainer);
            root.addView(scrollView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // 列表数据
            final List<UserEntity> filtered = new ArrayList<>(single);
            final List<CheckBox> checkBoxes = new ArrayList<>();
            final List<UserEntity> displayedList = new ArrayList<>(filtered);

            Runnable renderList = () -> {
                listContainer.removeAllViews();
                checkBoxes.clear();
                for (UserEntity ue : displayedList) {
                    CheckBox cb = new CheckBox(ctx);
                    cb.setText(ue.getShowName()
                            + " (" + (ue.getAccount() != null ? ue.getAccount() : "")
                            + ", fs=" + ue.getFriendStatus() + ")");
                    cb.setChecked(false);
                    checkBoxes.add(cb);
                    listContainer.addView(cb);
                }
            };
            renderList.run();

            // 搜索过滤
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    displayedList.clear();
                    String kw = s.toString().toLowerCase(Locale.getDefault());
                    for (UserEntity ue : filtered) {
                        String hay = (ue.getShowName() + " " + (ue.getAccount() != null ? ue.getAccount() : "")
                                + " " + ue.getUserId()).toLowerCase(Locale.getDefault());
                        if (kw.isEmpty() || hay.contains(kw)) displayedList.add(ue);
                    }
                    renderList.run();
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            btnSelectAll.setOnClickListener(v -> {
                for (CheckBox cb : checkBoxes) cb.setChecked(true);
            });
            btnInvert.setOnClickListener(v -> {
                for (CheckBox cb : checkBoxes) cb.setChecked(!cb.isChecked());
            });

            String title = "单向好友(" + single.size() + "/" + totalFriends + ") [实时] ";

            AlertDialog dialog = new AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setView(root)
                    .setPositiveButton("删除", null)  // 后面手动覆盖点击
                    .setNegativeButton("取消", null)
                    .create();

            dialog.setOnShowListener(d -> {
                Button deleteBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                deleteBtn.setOnClickListener(v -> {
                    // 收集勾选的
                    List<UserEntity> toDelete = new ArrayList<>();
                    for (int i = 0; i < checkBoxes.size(); i++) {
                        if (checkBoxes.get(i).isChecked()) {
                            toDelete.add(displayedList.get(i));
                        }
                    }
                    if (toDelete.isEmpty()) {
                        ToastUtil.show(ctx, "请先勾选要删除的好友");
                        return;
                    }
                    // 二次确认
                    new AlertDialog.Builder(ctx)
                            .setTitle("确认删除")
                            .setMessage("确认删除 " + toDelete.size() + " 个单向好友? 不可恢复!")
                            .setPositiveButton("确认删除", (d2, w2) -> {
                                doDeleteNow(ctx, toDelete);
                                dialog.dismiss();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            });

            dialog.show();
        } catch (Throwable t) {
            Log.printStackTrace(TAG + ".showSingleDeletedDialog", t);
            try { ToastUtil.show(ctx, "弹单向好友列表失败: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    /**
     * 执行实际删除: 发送广播到支付宝进程, 由 ApplicationHook 在支付宝进程内执行 RPC 并回传结果。
     */
    public static void doDeleteNow(Context ctx, List<UserEntity> toDelete) {
        try {
            String requestId = UUID.randomUUID().toString();
            JSONArray arr = new JSONArray();
            for (UserEntity ue : toDelete) {
                JSONObject item = new JSONObject();
                item.put("userId", ue.getUserId());
                item.put("account", ue.getAccount() != null ? ue.getAccount() : "");
                item.put("name", ue.getShowName());
                arr.put(item);
            }

            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent in) {
                    if (!ACTION_RESULT.equals(in.getAction())) return;
                    String rid = in.getStringExtra("requestId");
                    if (rid == null || !rid.equals(requestId)) return;
                    try { c.unregisterReceiver(this); } catch (Throwable ignored) {}
                    String result = in.getStringExtra("result");
                    String detail = in.getStringExtra("detail");
                    ToastUtil.show(c, result != null ? result : "删除完成");
                    if (detail != null && !detail.isEmpty()) {
                        new AlertDialog.Builder(c)
                                .setTitle("删除结果")
                                .setMessage(detail.trim())
                                .setPositiveButton("确定", null)
                                .show();
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, new IntentFilter(ACTION_RESULT), Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, new IntentFilter(ACTION_RESULT));
            }

            Intent intent = new Intent(ACTION_DELETE);
            intent.putExtra("type", "delete");
            intent.putExtra("requestId", requestId);
            intent.putExtra("userIds", arr.toString());
            ctx.sendBroadcast(intent);
            ToastUtil.show(ctx, "删除请求已发送，等待支付宝处理...");
        } catch (Throwable t) {
            ToastUtil.show(ctx, "发送删除请求失败: " + t.getMessage());
            Log.printStackTrace(TAG + ".doDeleteNow", t);
        }
    }

    private static int dp(Context ctx, int px) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (px * density);
    }
}
