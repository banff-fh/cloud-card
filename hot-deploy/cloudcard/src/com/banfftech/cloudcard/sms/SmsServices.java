package com.banfftech.cloudcard.sms;

import java.util.Calendar;
import java.util.HashMap;
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
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.auth0.jwt.JWTSigner;
import com.banfftech.cloudcard.CloudCardHelper;
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
			Debug.logError(phone+"短信发送异常" + e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendMessageServiceError", UtilMisc.toMap("phone", phone),locale));
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
	
	/**
	 * 获取登录验证码
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getLoginCaptcha(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String teleNumber = (String) context.get("teleNumber");
		java.sql.Timestamp nowTimestamp  = UtilDateTime.nowTimestamp();

		GenericValue customer;
		try {
			customer = EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
					EntityCondition.makeCondition(
							EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", teleNumber)), 
							EntityUtil.getFilterByDateExpr()), null, UtilMisc.toList("partyId DESC"), null, false));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isEmpty(customer)){
			Debug.logInfo("The user tel:[" + teleNumber + "] does not exist, can not get verfiy code", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserNotExistError", locale));
		}
		
		
		
		EntityConditionList<EntityCondition> captchaConditions = EntityCondition
				.makeCondition(EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),EntityUtil.getFilterByDateExpr(),EntityCondition.makeCondition("isValid", EntityOperator.EQUALS,"N"));
		
		List<GenericValue> smsList = FastList.newInstance();
		try {
			smsList = delegator.findList("SmsValidateCode", captchaConditions, null,
					null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		//判断短信是否存在，不存在创建验证码，否则判断短信是否需要重新发送。
		if(UtilValidate.isEmpty(smsList)){
			//生成验证码
			String captcha = UtilFormatOut.padString(String.valueOf(Math.round((Math.random()*10e6))), 6, false, '0');
			Map<String,Object> smsValidateCodeMap = FastMap.newInstance();
			smsValidateCodeMap.put("teleNumber", teleNumber);
			smsValidateCodeMap.put("captcha", captcha);
			smsValidateCodeMap.put("smsType", "LOGIN");
			smsValidateCodeMap.put("isValid", "N");
			smsValidateCodeMap.put("fromDate", nowTimestamp);
			smsValidateCodeMap.put("thruDate",UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.MINUTE,15));
			try {
				GenericValue smstGV = delegator.makeValue("SmsValidateCode", smsValidateCodeMap);
				smstGV.create();
			} catch (GenericEntityException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendFailedError", locale));
			}
			
			//发送短信
			context.put("phone", teleNumber);
			context.put("code", captcha);
			context.put("product", "卡云卡");
			SmsServices.sendMessage(dctx, context);
		}else{
			GenericValue sms = smsList.get(0);
			//获取短信发送间隔时间
			int validTime = Integer.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","sms.validTime","900",delegator));
			int intervalTime = Integer.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","sms.intervalTime","30",delegator));

			//如果这次请求不在上一次请求一分钟内，延长短信结束时间并重新发送短信。
			if(UtilDateTime.nowTimestamp().after(UtilDateTime.adjustTimestamp((java.sql.Timestamp)sms.get("thruDate"), Calendar.SECOND,(intervalTime-validTime)))){
				sms.set("thruDate",UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.MINUTE,15));
				try {
					sms.store();
				} catch (GenericEntityException e) {
					Debug.logError(e, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserNotExistError", locale));
				}
				//发送短信
				context.put("phone", teleNumber);
				context.put("code", sms.get("captcha"));
				context.put("product", "卡云卡");
				SmsServices.sendMessage(dctx, context);
			}
			
		}
		
		return result;
	}
	
	/**
	 * 用户app登录接口
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userAppLogin(DispatchContext dctx, Map<String, Object> context) {
		return appLogin(dctx,context);
	}
	
	/**
	 * 商户app登录接口
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizAppLogin(DispatchContext dctx, Map<String, Object> context) {
		return appLogin(dctx,context);
	}
	
	/**
	 * 手机app登录
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> appLogin(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String teleNumber = (String) context.get("teleNumber");
		String captcha = (String) context.get("captcha");
		String token = null;
		Map<String, Object> result = ServiceUtil.returnSuccess();

		GenericValue customer;
		try {
			customer = EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
					EntityCondition.makeCondition(
							EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", teleNumber)), 
							EntityUtil.getFilterByDateExpr()), null, UtilMisc.toList("partyId DESC"), null, false));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(UtilValidate.isEmpty(customer)){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserNotExistError", locale));
		}else{
			//返回机构Id
			List<String> organizationList = CloudCardHelper.getOrganizationPartyId(delegator, customer.get("partyId").toString());
			if(UtilValidate.isNotEmpty(organizationList)){
				result.put("organizationPartyId", organizationList.get(0));
			}
			
			//查找用户验证码是否存在
			EntityConditionList<EntityCondition> captchaConditions = EntityCondition
					.makeCondition(EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),EntityUtil.getFilterByDateExpr(),EntityCondition.makeCondition("isValid", EntityOperator.EQUALS, "N"));
			List<GenericValue> smsList = FastList.newInstance();
			try {
				smsList = delegator.findList("SmsValidateCode", captchaConditions, null,
						null, null, false);
			} catch (GenericEntityException e) {
				Debug.logError(e, module);
			}
			
			if(UtilValidate.isEmpty(smsList)){
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCaptchaNotExistError", locale));
			}else{
				GenericValue sms = smsList.get(0);
				
				if(sms.get("captcha").equals(captcha)){
					//有效时间
					long expirationTime = Long.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","token.expirationTime","172800L",delegator));
					String iss = EntityUtilProperties.getPropertyValue("cloudcard","token.issuer",delegator);
					String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard","token.secret",delegator);
					//开始时间
					final long iat = System.currentTimeMillis() / 1000L; // issued at claim 
					//Token到期时间
					final long exp = iat + expirationTime; 
					//生成Token
					final JWTSigner signer = new JWTSigner(tokenSecret);
					final HashMap<String, Object> claims = new HashMap<String, Object>();
					claims.put("iss", iss);
					claims.put("user", customer.get("userLoginId"));
					claims.put("delegatorName", delegator.getDelegatorName());
					claims.put("exp", exp);
					claims.put("iat", iat);
					token = signer.sign(claims);
					//修改验证码状态
					sms.set("isValid", "Y");
					try {
						sms.store();
					} catch (GenericEntityException e) {
						Debug.logError(e, module);
						return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
					}
					result.put("token", token);
				}else{
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCaptchaCheckFailedError", locale));
				}
			}
		}
		
		return result;
	}
	
	
	
}
