package com.banfftech.cloudcard.sms;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.auth0.jwt.JWTSigner;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.CloudCardQueryServices;
import com.banfftech.cloudcard.constant.CloudCardConstant;
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

	public static void getSmsProperty(Delegator delegator,String smsType){
		url = EntityUtilProperties.getPropertyValue("cloudcard","sms.url",delegator);
		appkey = EntityUtilProperties.getPropertyValue("cloudcard","sms.appkey",delegator);
		secret = EntityUtilProperties.getPropertyValue("cloudcard","sms.secret",delegator);
		smsFreeSignName = EntityUtilProperties.getPropertyValue("cloudcard","sms.smsFreeSignName",delegator);
		smsTemplateCode = EntityUtilProperties.getPropertyValue("cloudcard",smsType,delegator);
	}

	public static Map<String, Object> sendMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String phone = (String) context.get("phone");
		String smsType = (String) context.get("smsType");
		String smsParamString = "";
		String captcha = (String) context.get("captcha");

		if(smsType.equals(CloudCardConstant.LOGIN_SMS_TYPE)){
			smsParamString = "{code:'"+captcha+"',product:'"+"库胖"+"'}";
			smsType = "sms.smsLoginTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PAY_SMS_TYPE)){
			BigDecimal amount = (BigDecimal) context.get("amount");
			String storeName = (String) context.get("storeName");
			BigDecimal cardBalance = (BigDecimal) context.get("cardBalance");
			String cardCode = (String) context.get("cardCode");

			smsParamString = "{storeName:'"+storeName+"',amount:'"+amount+"',cardCode:'"+cardCode+"',cardBalance:'"+cardBalance+"'}";
			smsType = "sms.smsUserPayTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PAY_CAPTCHA_SMS_TYPE)){
			String amount = (String) context.get("amount");
			Integer validTime = (Integer) context.get("validTime");
			smsParamString = "{money:'"+amount+"',verfiyCode:'"+captcha+"',time:'"+validTime+"'}";
			smsType = "sms.smsUserPayVCTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_RECHARGE_SMS_TYPE)){
			BigDecimal amount = (BigDecimal) context.get("amount");
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");
			BigDecimal cardBalance = (BigDecimal) context.get("cardBalance");

			smsParamString = "{storeName:'"+storeName+"',cardCode:'"+ cardCode+"',cardBalance:'"+cardBalance+"',amount:'"+amount+"'}";
			smsType = "sms.smsUserRechargeTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PURCHASE_CARD_SMS_TYPE)){
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");
			BigDecimal cardBalance = (BigDecimal) context.get("cardBalance");

			smsParamString = "{storeName:'"+storeName+"',cardCode:'"+ cardCode+"',cardBalance:'"+cardBalance+"'}";
			smsType = "sms.smsUserPurchaseCardTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_CREATE_CARD_AUTH_TYPE)){
			String authType = (String) context.get("authType");
			String storeName = (String) context.get("storeName");
			String teleNumber = (String) context.get("teleNumber");
			BigDecimal cardBalance = (BigDecimal) context.get("amount");

			if("1".equals(authType)){
				String date = (String) context.get("date");
				smsParamString = "{teleNumber:'" + teleNumber + "',storeName:'" + storeName + "',cardBalance:'" + cardBalance + "',date:'" + date + "'}";
				smsType = "sms.smsUserCreateCardAuthShortTimeTemplateCode";
			}else if("2".equals(authType)){
				smsParamString = "{teleNumber:'"+teleNumber+ "',storeName:'" + storeName + "',cardBalance:'"+ cardBalance + "'}";
				smsType = "sms.smsUserCreateCardAuthLongTimeTemplateCode";
			}else if("3".equals(authType)){
				String startTime = (String) context.get("startTime");
				String endTime = (String) context.get("endTime");
				smsParamString = "{teleNumber:'"+teleNumber+ "',storeName:'" + storeName + "',cardBalance:'"+ cardBalance + "',startTime:'"+ startTime + "',endTime:'"+ endTime + "'}";
				smsType = "sms.smsUserCreateCardAuthTimeIntervalTemplateCode";
			}
		}else if(smsType.equals(CloudCardConstant.USER_REVOKE_CARD_AUTH_TYPE)){
			String teleNumber = (String) context.get("teleNumber");
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");

			smsParamString = "{teleNumber:'"+teleNumber+"',storeName:'"+ storeName+"',cardCode:'"+cardCode+"'}";
			smsType = "sms.smsUserRevokeCardAuthTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_MODIFY_CARD_OWNER_TYPE)){
			String teleNumber = (String) context.get("teleNumber");
			String storeName = (String) context.get("storeName");
			BigDecimal cardBalance = (BigDecimal) context.get("cardBalance");

			smsParamString = "{teleNumber:'"+teleNumber+"',storeName:'"+ storeName+"',cardBalance:'"+cardBalance+"'}";
			smsType = "sms.smsModifyCardOwnerTemplateCode";
		}
		//初始化短信发送配置文件
		getSmsProperty(delegator,smsType);
		//发送短信
		TaobaoClient client = new DefaultTaobaoClient(url, appkey, secret);
		AlibabaAliqinFcSmsNumSendRequest req = new AlibabaAliqinFcSmsNumSendRequest();
		req.setExtend("");
		req.setSmsType("normal");
		req.setSmsFreeSignName(smsFreeSignName);
		req.setSmsParamString(smsParamString);
		req.setRecNum(phone);
		req.setSmsTemplateCode(smsTemplateCode);
		AlibabaAliqinFcSmsNumSendResponse rsp = null;
		try {
			rsp = client.execute(req);
		} catch (ApiException e) {
			Debug.logError(phone+"短信发送异常" + e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendMessageServiceError", UtilMisc.toMap("phone", phone),locale));
		}
		if(rsp!=null && !rsp.isSuccess()){
			Debug.logWarning("something wrong when send the short message, response body:" + rsp.getBody(), module);
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
		String userType = (String) context.get("userType");

		GenericValue customer;
		try {
			customer = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(customer) && userType =="biz" ){
			Debug.logInfo("The user tel:[" + teleNumber + "] does not exist, can not get verfiy code", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserNotExistError", locale));
		}

		context.put("smsType", CloudCardConstant.LOGIN_SMS_TYPE);
		context.put("isValid", "N");
		Map<String, Object> result = getSMSCaptcha(dctx, context);
		return result;
	}

	/**
	 * 用户app登录接口
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> userAppLogin(DispatchContext dctx, Map<String, Object> context) {
		context.put("appType", "user");
		return appLogin(dctx,context);
	}

	/**
	 * 商户app登录接口
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizAppLogin(DispatchContext dctx, Map<String, Object> context) {
		context.put("appType", "biz");
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
		String appType = (String) context.get("appType");

		String token = null;
		Map<String, Object> result = ServiceUtil.returnSuccess();

		GenericValue customer;
		try {
			customer = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		Map<String,Object> customerMap = FastMap.newInstance();
		if(UtilValidate.isEmpty(customer) && "biz".equals(appType)){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserNotExistError", locale));
		}else if(UtilValidate.isEmpty(customer) && "user".equals(appType)){
			context.put("organizationPartyId", "Company");
			context.put("ensureCustomerRelationship", true);
			customerMap = CloudCardHelper.getOrCreateCustomer(dctx, context);
		}
		//返回机构Id
		List<String> organizationList = FastList.newInstance();
		if(null != customer){
			organizationList = CloudCardHelper.getOrganizationPartyId(delegator, customer.get("partyId").toString());
		}else{
			organizationList = CloudCardHelper.getOrganizationPartyId(delegator, customerMap.get("customerPartyId").toString());
		}

		if(UtilValidate.isNotEmpty(organizationList)){
			result.put("organizationPartyId", organizationList.get(0));
		}

		//判断商户app登录权限
		if("biz".equals(appType)){
			if(null == result.get("organizationPartyId")){
				Debug.logError(teleNumber+"不是商户管理人员，不能登录 商户app", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBizLoginIsNotManager", locale));
			}
		}


		//查找用户验证码是否存在
		EntityConditionList<EntityCondition> captchaConditions = EntityCondition
				.makeCondition(EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),EntityUtil.getFilterByDateExpr(),EntityCondition.makeCondition("isValid", EntityOperator.EQUALS, "N"),EntityCondition.makeCondition("smsType", EntityOperator.EQUALS, CloudCardConstant.LOGIN_SMS_TYPE));
		List<GenericValue> smsList = FastList.newInstance();
		try {
			smsList = delegator.findList("SmsValidateCode", captchaConditions, null,
					UtilMisc.toList("-" + ModelEntity.CREATE_STAMP_FIELD), null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
				if(null != customer){
					claims.put("user", customer.get("userLoginId"));
				}else{
					claims.put("user", customerMap.get("userLoginId"));
				}
				claims.put("delegatorName", delegator.getDelegatorName());
				claims.put("exp", exp);
				claims.put("iat", iat);
				token = signer.sign(claims);
				//修改验证码状态
				sms.set("isValid", "Y");
				try {
					sms.store();
				} catch (GenericEntityException e) {
					Debug.logError(e.getMessage(), module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
				result.put("token", token);
			}else{
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCaptchaCheckFailedError", locale));
			}
		}

		return result;
	}

	public static  Map<String, Object> getSMSCaptcha(DispatchContext dctx, Map<String, Object> context){
		java.sql.Timestamp nowTimestamp  = UtilDateTime.nowTimestamp();
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String teleNumber = (String) context.get("teleNumber");
		String isValid = (String) context.get("isValid");
		String smsType = (String) context.get("smsType");

		//校验电话号码是否非法
		Pattern p = Pattern.compile("^((13[0-9])|(15[^4,\\D])|(14[57])|(17[0])|(17[3])|(17[5])|(17[6])|(17[7])|(18[0,0-9]))\\d{8}$");
		Matcher m = p.matcher(teleNumber);
		if(!m.matches()){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardTelNumIllegal", locale));
		}

		EntityCondition captchaCondition = EntityCondition.makeCondition(
						EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),
						EntityUtil.getFilterByDateExpr(),
						EntityCondition.makeCondition("isValid", EntityOperator.EQUALS,"N"),EntityCondition.makeCondition("smsType", EntityOperator.EQUALS,smsType));

		GenericValue sms = null;
		try {
			sms = EntityUtil.getFirst(
					delegator.findList("SmsValidateCode", captchaCondition, null,UtilMisc.toList("-" + ModelEntity.CREATE_STAMP_FIELD), null, false)
					);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}


		int validTime = Integer.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","sms.validTime","900",delegator));
		int intervalTime = Integer.valueOf(EntityUtilProperties.getPropertyValue("cloudcard","sms.intervalTime","60",delegator));


		boolean sendSMS = false;
		if(UtilValidate.isEmpty(sms)){
			sendSMS = true;
		}else{
			Debug.logInfo("The user tel:[" + teleNumber + "]  verfiy code[" + sms.getString("captcha") + "], check the interval time , if we'll send new code", module);
			// 如果已有未验证的记录存在，则检查是否过了再次重发的时间间隔，没过就忽略本次请求
			if(UtilDateTime.nowTimestamp().after(UtilDateTime.adjustTimestamp((java.sql.Timestamp)sms.get("fromDate"), Calendar.SECOND, intervalTime))){
				sms.set("thruDate", nowTimestamp);
				try {
					sms.store();
				} catch (GenericEntityException e) {
					Debug.logError(e, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
				Debug.logInfo("The user tel:[" + teleNumber + "]  will get new verfiy code!", module);
				sendSMS = true;
			}
		}

		if(sendSMS){
			//生成验证码
			String captcha = UtilFormatOut.padString(String.valueOf(Math.round((Math.random()*10e6))), 6, false, '0');
			Map<String,Object> smsValidateCodeMap = FastMap.newInstance();
			smsValidateCodeMap.put("teleNumber", teleNumber);
			smsValidateCodeMap.put("captcha", captcha);
			smsValidateCodeMap.put("smsType", smsType);
			smsValidateCodeMap.put("isValid", isValid);
			smsValidateCodeMap.put("fromDate", nowTimestamp);
			smsValidateCodeMap.put("thruDate",UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.SECOND, validTime));
			try {
				GenericValue smstGV = delegator.makeValue("SmsValidateCode", smsValidateCodeMap);
				smstGV.create();
			} catch (GenericEntityException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendFailedError", locale));
			}
			//发送短信
			context.put("captcha", captcha);
			context.put("phone", teleNumber);
			context.put("validTime", validTime/60);
			SmsServices.sendMessage(dctx, context);
		}

		return ServiceUtil.returnSuccess();
	}

}
