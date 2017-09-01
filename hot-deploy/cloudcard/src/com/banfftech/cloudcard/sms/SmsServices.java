package com.banfftech.cloudcard.sms;

import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Date;
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
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.CloudCardQueryServices;
import com.banfftech.cloudcard.constant.CloudCardConstant;

import cn.jsms.api.SendSMSResult;
import cn.jsms.api.common.SMSClient;
import cn.jsms.api.common.model.SMSPayload;
import javolution.util.FastList;
import javolution.util.FastMap;

public class SmsServices {
	public static final String module = CloudCardQueryServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";
	private static String appkey = null;
	private static String secret = null;
	private static String smsTemplateCode = null;

	public static void getSmsProperty(Delegator delegator,String smsType){
		appkey = EntityUtilProperties.getPropertyValue("cloudcard","sms.appkey",delegator);
		secret = EntityUtilProperties.getPropertyValue("cloudcard","sms.secret",delegator);
		smsTemplateCode = EntityUtilProperties.getPropertyValue("cloudcard",smsType,delegator);
	}

	public static Map<String, Object> sendMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String phone = (String) context.get("phone");
		String smsType = (String) context.get("smsType");

		//短信内容
		Map<String, String> smsMap = FastMap.newInstance();
		if(smsType.equals(CloudCardConstant.LOGIN_SMS_TYPE)){
			String code = (String) context.get("code");

			smsMap.put("code", code);
			smsMap.put("product", "库胖");
			smsType = "sms.smsLoginTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PAY_SMS_TYPE)){
			String amount = (String) context.get("amount");
			String storeName = (String) context.get("storeName");
			String cardBalance = (String) context.get("cardBalance");
			String cardCode = (String) context.get("cardCode");

			smsMap.put("storeName", storeName);
			smsMap.put("amount", amount);
			smsMap.put("cardCode", cardCode);
			smsMap.put("cardBalance", cardBalance);
			smsType = "sms.smsUserPayTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PAY_CAPTCHA_SMS_TYPE)){
			String amount = (String) context.get("amount");
			String validTime = (String) context.get("validTime");
			String code = (String) context.get("code");
			String storeName = (String) context.get("storeName");

			smsMap.put("money", amount);
			smsMap.put("verfiyCode", code);
			smsMap.put("time", validTime);
			smsMap.put("storeName", storeName);
			smsType = "sms.smsUserPayVCTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_RECHARGE_CAPTCHA_SMS_TYPE)){
			String amount = (String) context.get("amount");
			String validTime = (String) context.get("validTime");
			String code = (String) context.get("code");
			String storeName = (String) context.get("storeName");

			smsMap.put("money", amount);
			smsMap.put("verfiyCode", code);
			smsMap.put("time", validTime);
			smsMap.put("storeName", storeName);
			smsType = "sms.smsUserRechargeVCTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_RECHARGE_SMS_TYPE)){
			String amount = (String) context.get("amount");
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");
			String cardBalance = (String) context.get("cardBalance");

			smsMap.put("storeName", storeName);
			smsMap.put("cardCode", cardCode);
			smsMap.put("cardBalance", cardBalance);
			smsMap.put("amount", amount);
			smsType = "sms.smsUserRechargeTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PURCHASE_CARD_SMS_TYPE)){
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");
			String cardBalance = (String) context.get("cardBalance");

			smsMap.put("storeName", storeName);
			smsMap.put("cardCode", cardCode);
			smsMap.put("cardBalance", cardBalance);
			smsType = "sms.smsUserPurchaseCardTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_CREATE_CARD_AUTH_TYPE)){
			String authType = (String) context.get("authType");
			String storeName = (String) context.get("storeName");
			String telNum = (String) context.get("telNum");
			String cardBalance = (String) context.get("amount");

			if("1".equals(authType)){
				String validTime = (String) context.get("validTime");
				smsMap.put("teleNumber", telNum);
				smsMap.put("storeName", storeName);
				smsMap.put("cardBalance", cardBalance);
				smsMap.put("date", validTime);
				smsType = "sms.smsUserCreateCardAuthShortTimeTemplateCode";
			}else if("2".equals(authType)){
				smsMap.put("teleNumber", telNum);
				smsMap.put("storeName", storeName);
				smsMap.put("cardBalance", cardBalance);
				smsType = "sms.smsUserCreateCardAuthLongTimeTemplateCode";
			}else if("3".equals(authType)){
				String startTime = (String) context.get("startTime");
				String endTime = (String) context.get("endTime");

				smsMap.put("teleNumber", telNum);
				smsMap.put("storeName", storeName);
				smsMap.put("cardBalance", cardBalance);
				smsMap.put("startTime", startTime);
				smsMap.put("endTime", endTime);
				smsType = "sms.smsUserCreateCardAuthTimeIntervalTemplateCode";
			}
		}else if(smsType.equals(CloudCardConstant.USER_REVOKE_CARD_AUTH_TYPE)){
			String teleNumber = (String) context.get("telNum");
			String storeName = (String) context.get("storeName");
			String cardCode = (String) context.get("cardCode");

			smsMap.put("teleNumber", teleNumber);
			smsMap.put("storeName", storeName);
			smsMap.put("cardCode", cardCode);

			smsType = "sms.smsUserRevokeCardAuthTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_MODIFY_CARD_OWNER_TYPE)){
			String teleNumber = (String) context.get("telNum");
			String storeName = (String) context.get("storeName");
			String cardBalance = (String) context.get("cardBalance");

			smsMap.put("teleNumber", teleNumber);
			smsMap.put("storeName", storeName);
			smsMap.put("cardBalance", cardBalance);
			smsType = "sms.smsModifyCardOwnerTemplateCode";
		}else if(smsType.equals(CloudCardConstant.USER_PURCHASE_CARD_CAPTCHA_SMS_TYPE)){
			String verfiyCode = (String) context.get("captcha");
			String time = (String) context.get("validTime");
			String money = (String) context.get("amount");

			smsMap.put("money", money);
			smsMap.put("verfiyCode", verfiyCode);
			smsMap.put("time", time);
			smsType = "sms.smsUserPurchaseCardVCTemplateCode";
		}else if(smsType.equals(CloudCardConstant.BIZ_CREATE_STORE_CAPTCHA)){
			String verfiyCode = (String) context.get("code");
			String time = (String) context.get("validTime");

			smsMap.put("verfiyCode", verfiyCode);
			smsMap.put("time", time);
			smsType = "sms.smsBizCreateStoreVCTemplateCode";
		}
		//初始化短信发送配置文件
		getSmsProperty(delegator,smsType);
		//发送短信
		SMSClient client = new SMSClient(secret, appkey);
    		SMSPayload payload = SMSPayload.newBuilder()
    				.setMobileNumber(phone)
    				.setTempId(Integer.parseInt(smsTemplateCode))
    				.setTempPara(smsMap)
    				.build();
    	try {
            SendSMSResult res = client.sendTemplateSMS(payload);
            if(res!=null && !res.isResultOK()){
    			Debug.logWarning("something wrong when send the short message, response body:" + res.ERROR_MESSAGE_NONE, module);
    		}
		} catch (Exception e) {
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
		String deviceId = (String) context.get("deviceId");
		String partyIdentificationTypeId = "";

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
		//用户ID
		String customerId = "";
		if(null != customer){
			customerId = customer.getString("partyId");
			organizationList = CloudCardHelper.getOrganizationPartyId(delegator, customerId);
		}else{
			customerId = customerMap.get("customerPartyId").toString();
			organizationList = CloudCardHelper.getOrganizationPartyId(delegator, customerId);
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
				final long iat = System.currentTimeMillis(); // issued at claim
				//Token到期时间
				final long exp = iat + expirationTime;
				//生成Token
				
				String user = null;
				if(null != customer){
					user = customer.getString("userLoginId");
				}else{
					user =  customerMap.get("userLoginId").toString();
				}
				
				Algorithm algorithm;
				try {
					algorithm = Algorithm.HMAC256(tokenSecret);
					token = JWT.create()
				    		.withIssuer(iss)
				    		.withIssuedAt(new Date(iat))
				    		.withExpiresAt(new Date(exp))
				    		.withClaim("delegatorName",  delegator.getDelegatorName())
				    		.withClaim("user",user)
				        .sign(algorithm);
				} catch (IllegalArgumentException | UnsupportedEncodingException e1) {
					Debug.logError(e1.getMessage(), module);
				}
				
				//修改验证码状态
				sms.set("isValid", "Y");
				try {
					sms.store();
				} catch (GenericEntityException e) {
					Debug.logError(e.getMessage(), module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}

				//增加设备ID
				if(UtilValidate.isNotEmpty(deviceId)){
					if(appType.equals("biz")){
						partyIdentificationTypeId = CloudCardConstant.BIZ_APP_UUID_TYPE;
					}else if(appType.equals("user")){
						partyIdentificationTypeId = CloudCardConstant.USER_APP_UUID_TYPE;
					}

					GenericValue partyIdentification = null;
					Map<String, Object> lookupFields = FastMap.newInstance();
					lookupFields.put("partyId", customerId);
					lookupFields.put("partyIdentificationTypeId", partyIdentificationTypeId);
					try {
						partyIdentification = delegator.findByPrimaryKey("PartyIdentification", lookupFields);
						//判断该用户是否存在deviceId,如果不存在，插入一条新数据，否则修改该partyId的deviceId
						if(UtilValidate.isEmpty(partyIdentification)){
							lookupFields.put("idValue", deviceId);
							delegator.makeValue("PartyIdentification", lookupFields).create();
						}else{
							partyIdentification.set("idValue", deviceId);
							partyIdentification.store();
						}
					} catch (GenericEntityException e) {
						Debug.logError(e.getMessage(), module);
						return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
					}
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
		String amount = (String) context.get("amount");
		String storeName = (String) context.get("storeName");

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
			Map<String, Object> sendMessageMap;
			Map<String, String> smsMap = FastMap.newInstance();
			smsMap.put("code", captcha);
			smsMap.put("smsType", smsType);
			smsMap.put("phone", teleNumber);
			smsMap.put("validTime", String.valueOf(validTime/60));
			if(UtilValidate.isNotEmpty(amount)){
				smsMap.put("amount", amount);
			}
			if(UtilValidate.isNotEmpty(storeName)){
				smsMap.put("storeName", storeName);
			}

			try {
				sendMessageMap = dispatcher.runSync("sendMessage", smsMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}

			if(!ServiceUtil.isSuccess(sendMessageMap)){
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendFailedError", locale));
			}
		}

		return ServiceUtil.returnSuccess();
	}

}
