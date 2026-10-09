package com.surexu.sesame.model.task.friendManage;

import com.surexu.sesame.hook.ApplicationHook;

/**
 * 好友关系管理 RPC 调用。
 * 删除接口: alipay.mobile.relation.handleFriendship（bizType=2 为删除动作）
 */
public final class FriendManageRpcCall {

    private static final String VERSION = "20250818";

    private FriendManageRpcCall() {
    }

    /**
     * 删除好友。
     * 抓包样本: {"alipayAccount":"1xx****xx06","bizType":"2","targetUserId":"2088..."}
     */
    public static String handleFriendship(String targetUserId, String alipayAccount) {
        String args = "[{\"alipayAccount\":\"" + alipayAccount + "\",\"bizType\":\"2\",\"targetUserId\":\"" + targetUserId + "\"}]";
        return ApplicationHook.requestString("alipay.mobile.relation.handleFriendship", args);
    }

    /**
     * 设置好友备注名。
     * 接口名已由抓包日志确认(alipay.mobile.relation.setRemark), 参数结构待真机验证。
     */
    public static String setRemark(String targetUserId, String alipayAccount, String remarkName) {
        String args = "[{\"alipayAccount\":\"" + alipayAccount + "\",\"remarkName\":\"" + remarkName + "\",\"targetUserId\":\"" + targetUserId + "\"}]";
        return ApplicationHook.requestString("alipay.mobile.relation.setRemark", args);
    }
}
