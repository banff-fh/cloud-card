package com.banfftech.cloudcard.jpush;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;

import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import cn.jpush.api.JPushClient;
import cn.jpush.api.push.PushResult;


public class JPushServices {
	public static final String module = JPushServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";

	//注册jPushClient
	private static JPushClient getJPushClient(Delegator delegator,String appType) {
		JPushClient jPushClient = null;
		if(appType.equals("biz")){
			String appkey = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.bizAppKey", delegator);
			String secret = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.bizSecret", delegator);
			jPushClient = new JPushClient(secret, appkey);
			return jPushClient;
		}else if(appType.equals("user")){
			String appkey = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.userAppKey", delegator);
			String secret = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.userSecret", delegator);
			jPushClient = new JPushClient(secret, appkey);
		}
		return jPushClient;
	}
	
	// 推送消息
	public static void pushMessage(Delegator delegator, String title, String msgContent, String registrationID,String appType,int seedType) {
		JPushClient jPushClient = getJPushClient(delegator,appType);
		PushResult pushResult = null;
		try {
			if (seedType == 0) {
				pushResult = jPushClient.sendAndroidNotificationWithRegistrationID(title, msgContent, null,registrationID);
			} else {
				pushResult = jPushClient.sendNotificationAll(msgContent);
			}
			if (UtilValidate.isEmpty(pushResult)) {
				Debug.logError(registrationID +":推送消息失败", module);
			}
		} catch (APIConnectionException e) {
			Debug.logError(registrationID +":推送消息失败", module);
		} catch (APIRequestException e) {
			Debug.logError(registrationID +":推送消息失败", module);
		}
		
	}

}
