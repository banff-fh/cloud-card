package com.banfftech.cloudcard.jpush;

import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import cn.jpush.api.JPushClient;
import cn.jpush.api.push.PushResult;
import cn.jpush.api.push.model.Message;
import cn.jpush.api.push.model.Options;
import cn.jpush.api.push.model.Platform;
import cn.jpush.api.push.model.PushPayload;
import cn.jpush.api.push.model.PushPayload.Builder;
import cn.jpush.api.push.model.audience.Audience;
import cn.jpush.api.push.model.notification.AndroidNotification;
import cn.jpush.api.push.model.notification.IosNotification;
import cn.jpush.api.push.model.notification.Notification;
import javolution.util.FastList;
import javolution.util.FastMap;


public class JPushServices {
	public static final String module = JPushServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";

	/**
	 * android用户端 和 商户端 用来存储 极光推送的id 的  partyIdentificationTypeId 映射
	 */
	public static final Map<String, String> ANDROID_APPTYPE_PIFT_MAP = FastMap.newInstance();
	static{
		ANDROID_APPTYPE_PIFT_MAP.put("biz", "JPUSH_ANDROID_BIZ");
		ANDROID_APPTYPE_PIFT_MAP.put("user", "JPUSH_ANDROID_USER");
	}
	
	/**
	 * ios 用户端 和 商户端 用来存储 极光推送的id 的  partyIdentificationTypeId 映射
	 */
	public static final Map<String, String> IOS_APPTYPE_PIFT_MAP = FastMap.newInstance();
	static{
		IOS_APPTYPE_PIFT_MAP.put("biz", "JPUSH_IOS_BIZ");
		IOS_APPTYPE_PIFT_MAP.put("user", "JPUSH_IOS_USER");
	}
	
	
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
	public static Map<String,Object> pushNotifOrMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		
		// biz 、user
		String appType = (String) context.get("appType");
		
		// all 所有人
		String sendType = (String) context.get("sendType");
		String title = (String) context.get("title");

		// 通知消息
		String content = (String) context.get("content");
		if(UtilValidate.isNotEmpty(content) && content.indexOf("${")>0){
			content = FlexibleStringExpander.expandString(content, context);
		}

		// 透传消息
		String message = (String) context.get("message");
		if(UtilValidate.isNotEmpty(message) && message.indexOf("${")>0){
			message = FlexibleStringExpander.expandString(message, context);
		}

		String partyId = (String) context.get("partyId");
		
		Map<String, String> extras = UtilGenerics.checkMap(context.get("extras"));
		if(null==extras){
			extras = FastMap.newInstance();
		}

		PushResult pushResult = null;
		
		if("all".equals(sendType)){
			// 推送到全平台所有人
			JPushClient jPushClient = getJPushClient(delegator, appType);
			PushPayload payload = null;
			// TODO 全平台的情况下不能同时推送消息和通知吗？
			if(UtilValidate.isNotEmpty(content)){
				payload = PushPayload.alertAll(content);
			}
			if(UtilValidate.isNotEmpty(message)){
				payload = PushPayload.messageAll(message);
			}
			try {
				pushResult = jPushClient.sendPush(payload);
				if (200 != pushResult.getResponseCode()) {
					Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败: code:" + pushResult.getResponseCode() + " response: " + pushResult.getOriginalContent(), module);
				}
			} catch (APIConnectionException e) {
				Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败:" + e.getMessage(), module);
			} catch (APIRequestException e) {
				Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败:" + e.getMessage(), module);
			}
			return ServiceUtil.returnSuccess();
		}

		// 发送特定人群
		JPushClient jPushClient = getJPushClient(delegator, appType);
		boolean setApnsProduction = "1".equals(EntityUtilProperties.getPropertyValue("cloudcard", "jpush.setApnsProduction", "0", delegator));

		Builder payloadBuilder = PushPayload.newBuilder()
				.setPlatform(Platform.all())
				.setOptions(Options.newBuilder().setApnsProduction(setApnsProduction).build());

		// 按照registrationID发送
		if("one".equals(sendType)){
			// 查询registrationID
			EntityCondition pConditions = EntityCondition.makeCondition("partyId", partyId);
			List<EntityCondition> devTypeExprs = FastList.newInstance();
			devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", ANDROID_APPTYPE_PIFT_MAP.get(appType)));
			devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", IOS_APPTYPE_PIFT_MAP.get(appType)));
			EntityCondition devCondition = EntityCondition.makeCondition(devTypeExprs, EntityOperator.OR);
			pConditions = EntityCondition.makeCondition(pConditions, devCondition);
			
			//查找regId
			List<GenericValue> partyIdentifications = FastList.newInstance();
			try {
				partyIdentifications = delegator.findList("PartyIdentification", pConditions, UtilMisc.toSet("idValue"), null, null, false);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
			}
			
			if(UtilValidate.isEmpty(partyIdentifications)){
				Debug.logWarning("没有推送目标", module);
				return ServiceUtil.returnSuccess();
			}
			List<String> idValues = EntityUtil.getFieldListFromEntityList(partyIdentifications, "idValue", true);
			
			payloadBuilder.setAudience(Audience.registrationId(idValues)); // FIXME 多个regId的情况下，一个id出错全错？
		}

		// 按标签发送
		if("tag".equals(sendType)){
			String tag = (String) context.get("tag");
			if(UtilValidate.isEmpty(tag)){
				Debug.logWarning("没有推送目标", module);
				return ServiceUtil.returnSuccess();
			}
			payloadBuilder.setAudience(Audience.tag(tag));
		}

		// 发送透传消息
		if(UtilValidate.isNotEmpty(message)){
			payloadBuilder.setMessage(
					Message.newBuilder().setMsgContent(message).addExtras(extras).build());
		}

		// 发送通知
		if(UtilValidate.isNotEmpty(content)){
			payloadBuilder.setNotification(
					Notification.newBuilder().addPlatformNotification(
							IosNotification.newBuilder().setAlert(content).setBadge(0).addExtras(extras).build())
					.addPlatformNotification(
							AndroidNotification.newBuilder().setAlert(content).setTitle(title).addExtras(extras).build())
					.build());
		}

		// 发送消息
		try {
			pushResult = jPushClient.sendPush(payloadBuilder.build());
			if (200 != pushResult.getResponseCode()) {
				Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败: code:" + pushResult.getResponseCode() + " response: " + pushResult.getOriginalContent(), module);
			}
		} catch (APIConnectionException e) {
			Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败:" + e.getMessage(), module);
		} catch (APIRequestException e) {
			Debug.logError("推送通知[" + content + "], 消息[" + message + "]失败:" + e.getMessage(), module);
		}

		return ServiceUtil.returnSuccess();
	}
}
