package com.banfftech.cloudcard.jpush;

import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityExpr;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;

import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import cn.jpush.api.JPushClient;
import cn.jpush.api.push.PushResult;
import cn.jpush.api.push.model.Message;
import cn.jpush.api.push.model.Options;
import cn.jpush.api.push.model.Platform;
import cn.jpush.api.push.model.PushPayload;
import cn.jpush.api.push.model.audience.Audience;
import cn.jpush.api.push.model.notification.AndroidNotification;
import cn.jpush.api.push.model.notification.IosNotification;
import cn.jpush.api.push.model.notification.Notification;
import javolution.util.FastList;
import javolution.util.FastMap;


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
	public static void pushNotifOrMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		String appType = (String) context.get("appType");
		String sendType = (String) context.get("sendType");
		String title = (String) context.get("title");
		String content = (String) context.get("content");
		String message = (String) context.get("message");
		String partyId = (String) context.get("partyId");

		// 查询registrationID
		Map<String, Object> fields = FastMap.newInstance();
		fields.put("partyId", partyId);
		
		EntityConditionList<EntityCondition> pConditions = null;
        EntityCondition.makeCondition("partyId", EntityOperator.EQUALS,partyId);
        List<EntityExpr> devTypeExprs = FastList.newInstance();
		if ("biz".equals(appType)) {
	        devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", EntityOperator.EQUALS,"JPUSH_ANDROID_BIZ"));
	        devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", EntityOperator.EQUALS,"JPUSH_IOS_BIZ"));
	        EntityCondition devCondition = EntityCondition.makeCondition(devTypeExprs, EntityOperator.OR);
	        pConditions = EntityCondition.makeCondition(UtilMisc.toList(EntityCondition.makeCondition("partyId", EntityOperator.EQUALS,partyId),devCondition),EntityOperator.AND);
		} else if ("user".equals(appType)) {
	        devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", EntityOperator.EQUALS,"JPUSH_ANDROID_USER"));
	        devTypeExprs.add(EntityCondition.makeCondition("partyIdentificationTypeId", EntityOperator.EQUALS,"JPUSH_IOS_USER"));
	        EntityCondition devCondition = EntityCondition.makeCondition(devTypeExprs, EntityOperator.OR);
	        pConditions = EntityCondition.makeCondition(UtilMisc.toList(EntityCondition.makeCondition("partyId", EntityOperator.EQUALS,partyId),devCondition),EntityOperator.AND);
		}
		
		//查找regId
		List<GenericValue> partyIdentifications = FastList.newInstance();
		try {
        	partyIdentifications = delegator.findList("PartyIdentification", pConditions, UtilMisc.toSet("idValue"), null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
		}
		
		//如果partyIdentifications大于0，获取regId,并推送消息
		if(partyIdentifications.size() > 0){
			JPushClient jPushClient = getJPushClient(delegator, appType);
			String registrationID = partyIdentifications.get(0).getString("idValue");
			PushPayload payload = null;
			PushResult pushResult = null;
			try {
				if (("0").equals(sendType)) {
					if(UtilValidate.isNotEmpty(message) && UtilValidate.isNotEmpty(content)){
						payload = PushPayload.newBuilder().setPlatform(Platform.all()).setAudience(Audience.registrationId(registrationID))
								.setNotification(Notification.newBuilder()
										.addPlatformNotification(
												IosNotification.newBuilder().setAlert(content).setBadge(5).build())
										.addPlatformNotification(AndroidNotification.newBuilder().setAlert(content)
												.setTitle(title).build())
										.build())
								.setMessage(Message.newBuilder()
				                        .setMsgContent(message)
				                        .build())
								.setOptions(Options.newBuilder()
				                         .setApnsProduction(true)
				                         .build())
								.build();
					}else if(UtilValidate.isNotEmpty(content) && UtilValidate.isEmpty(message)){
						payload = PushPayload.newBuilder().setPlatform(Platform.all()).setAudience(Audience.registrationId(registrationID))
								.setNotification(Notification.newBuilder()
										.addPlatformNotification(
												IosNotification.newBuilder().setAlert(content).setBadge(5).build())
										.addPlatformNotification(AndroidNotification.newBuilder().setAlert(content)
												.setTitle(title).build())
										.build())
								.setOptions(Options.newBuilder()
				                         .setApnsProduction(true)
				                         .build())
								.build();
					}else if(UtilValidate.isNotEmpty(message) && UtilValidate.isEmpty(content)){
						payload = PushPayload.newBuilder().setPlatform(Platform.all()).setAudience(Audience.registrationId(registrationID))
								.setMessage(Message.newBuilder()
				                        .setMsgContent(message)
				                        .build())
								.setOptions(Options.newBuilder()
				                         .setApnsProduction(true)
				                         .build())
								.build();
					}
				} else {
					payload = PushPayload.alertAll(content);
				}
				
				// 发送消息
				pushResult = jPushClient.sendPush(payload);

				if (UtilValidate.isEmpty(pushResult)) {
					Debug.logError(registrationID + ":推送消息失败:" + content, module);
				}

			} catch (APIConnectionException e) {
				Debug.logError(registrationID + ":推送消息失败" + content, module);
			} catch (APIRequestException e) {
				Debug.logError(registrationID + ":推送消息失败" + content, module);
			}
		}
	}
}
