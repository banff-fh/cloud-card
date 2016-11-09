package com.banfftech.cloudcard.sms;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardQueryServices;
import com.taobao.api.ApiException;
import com.taobao.api.DefaultTaobaoClient;
import com.taobao.api.TaobaoClient;
import com.taobao.api.request.AlibabaAliqinFcSmsNumSendRequest;
import com.taobao.api.response.AlibabaAliqinFcSmsNumSendResponse;

import javolution.util.FastList;
import javolution.util.FastMap;

public class SmsServices {
	public static final String module = CloudCardQueryServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";
	private static String url = null;
	private static String appkey = null;
	private static String secret = null;
	private static String smsFreeSignName = null;
	private static String smsTemplateCode = null;
	
	public static void getSmsProperty(Delegator delegator){
		url = EntityUtilProperties.getPropertyValue("cloudcard","sms.url",delegator);
		appkey = EntityUtilProperties.getPropertyValue("cloudcard","sms.appkey",delegator);
		secret = EntityUtilProperties.getPropertyValue("cloudcard","sms.secret",delegator);
		smsFreeSignName = EntityUtilProperties.getPropertyValue("cloudcard","sms.smsFreeSignName",delegator);
		smsTemplateCode = EntityUtilProperties.getPropertyValue("cloudcard","sms.smsTemplateCode",delegator);
	}

	public static Map<String, Object> sendMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String phone = (String) context.get("phone");
		String code = (String) context.get("code");
		String product = (String) context.get("product");
		//初始化短信发送配置文件
		getSmsProperty(delegator);
		//发送短信
		TaobaoClient client = new DefaultTaobaoClient(url, appkey, secret);
		AlibabaAliqinFcSmsNumSendRequest req = new AlibabaAliqinFcSmsNumSendRequest();
		req.setExtend("");
		req.setSmsType("normal");
		req.setSmsFreeSignName(smsFreeSignName);
		req.setSmsParamString("{code:'"+code+"',product:'"+product+"'}");
		req.setRecNum(phone);
		req.setSmsTemplateCode(smsTemplateCode);
		AlibabaAliqinFcSmsNumSendResponse rsp = null;
		try {
			rsp = client.execute(req);
		} catch (ApiException e) {
			Debug.logError(phone+"短信发送异常", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendMessageServiceError", UtilMisc.toMap("phone", phone),locale));
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
	
	public static Map<String, Object> getLoginCaptcha(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		String telNum = (String) context.get("telNum");
		java.sql.Timestamp nowTimestamp  = UtilDateTime.nowTimestamp();

		EntityConditionList<EntityCondition> incrementConditions = EntityCondition
				.makeCondition(
						UtilMisc.toList(EntityCondition.makeCondition("telNum", EntityOperator.EQUALS, telNum),
								EntityCondition.makeCondition(
										EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN,
												UtilDateTime.nowTimestamp()),
										EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN,
												UtilDateTime.nowTimestamp()))),
						EntityOperator.AND);
		
		List<GenericValue> smsList = FastList.newInstance();
		try {
			smsList = delegator.findList("SmsValidateCode", incrementConditions, null,
					null, null, false);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//判断短信是否存在，不存在创建验证码，否则判断短信是否需要重新发送。
		if(UtilValidate.isEmpty(smsList)){
			//生成验证码
			String captcha = UtilFormatOut.padString(String.valueOf(Math.floor((Math.random()*10e6))), 6, false, '0');
			Map<String,Object> smsValidateCodeMap = FastMap.newInstance();
			smsValidateCodeMap.put("telNum", telNum);
			smsValidateCodeMap.put("captcha", captcha);
			smsValidateCodeMap.put("smsType", "LOGIN");
			smsValidateCodeMap.put("isValidate", "N");
			smsValidateCodeMap.put("fromDate", nowTimestamp);
			smsValidateCodeMap.put("thruDate",UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.MINUTE,15));
			try {
				GenericValue smstGV = delegator.makeValue("SmsValidateCode", smsValidateCodeMap);
				smstGV.create();
			} catch (GenericEntityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			//发送短信
			/*context.put("phone", telNum);
			context.put("code", captcha);
			context.put("product", "卡云卡");
			SmsServices.sendMessage(dctx, context);*/

		}else{
			GenericValue sms = smsList.get(0);
			//获取短信发送间隔时间
			int time = Integer.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","sms.time",delegator));
			//如果这次请求不在上一次请求一分钟内，修改短信开始时间并重新发送短信。
			if(UtilDateTime.adjustTimestamp((java.sql.Timestamp)sms.get("thruDate"), Calendar.MINUTE,(1-time)).before(UtilDateTime.nowTimestamp())){
				sms.set("thruDate",UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.MINUTE,15));
				try {
					sms.store();
				} catch (GenericEntityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				//发送短信
				/*context.put("phone", telNum);
				context.put("code", sms.get("captcha"));
				context.put("product", "卡云卡");
				SmsServices.sendMessage(dctx, context);*/
			}
			
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
	
	public static Map<String, Object> appLogin(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		String telNum = (String) context.get("telNum");
		String captcha = (String) context.get("captcha");
		EntityConditionList<EntityCondition> incrementConditions = EntityCondition
				.makeCondition(
						UtilMisc.toList(EntityCondition.makeCondition("telNum", EntityOperator.EQUALS, telNum),
								EntityCondition.makeCondition(
										EntityCondition.makeCondition("fromDate", EntityOperator.LESS_THAN,
												UtilDateTime.nowTimestamp()),
										EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN,
												UtilDateTime.nowTimestamp()))),
						EntityOperator.AND);
		List<GenericValue> smsList = FastList.newInstance();
		try {
			smsList = delegator.findList("SmsValidateCode", incrementConditions, null,
					null, null, false);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if(!UtilValidate.isEmpty(smsList)){
			System.out.println("验证码不存在");
		}else{
			System.out.println("22");
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
	
}
