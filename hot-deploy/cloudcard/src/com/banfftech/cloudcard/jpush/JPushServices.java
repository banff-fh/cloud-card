package com.banfftech.cloudcard.jpush;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardServices;

import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import cn.jpush.api.JPushClient;
import cn.jpush.api.push.PushResult;


public class JPushServices {
	public static final String module = JPushServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";

	//注册jPushClient
	private static JPushClient getJPushClient(Delegator delegator) {
		String appkey = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.appkey", delegator);
		String secret = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.secret", delegator);
		String expirationTime = EntityUtilProperties.getPropertyValue("cloudcard", "jpush.expirationTime", "172800",delegator);
		@SuppressWarnings("deprecation")
		JPushClient jPushClient = new JPushClient(secret, appkey, Integer.valueOf(expirationTime));
		
		return jPushClient;
	}
	
	// 推送消息
	public static void pushMessage(Delegator delegator, String title, String msgContent, String registrationID,int seedType) {
		JPushClient jPushClient = getJPushClient(delegator);
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
