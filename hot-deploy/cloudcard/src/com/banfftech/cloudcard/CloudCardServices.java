package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

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
import org.ofbiz.entity.condition.EntityJoinOperator;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.auth0.jwt.JWTExpiredException;
import com.auth0.jwt.JWTVerifier;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.jpush.JPushServices;
import com.banfftech.cloudcard.sms.SmsServices;
import com.banfftech.cloudcard.util.CloudCardInfoUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * @author subenkun
 *
 */
public class CloudCardServices {

	public static final String module = CloudCardServices.class.getName();


	/**
	 * 卡授权
	 * 1、手机号关联的用户不存在则创建，存在则找到Ta，
	 * 		并为这个party创建一个关联到finAccount的 SHAREHOLDER 角色，带有起止时间，与授权起止时间一致
	 * 2、构造新卡号创建一个有起止时间的giftCard，关联到当前FinAccount
	 * 3、根据授权金额创建finAccountAuth记录，有起止时间，开始于当前，结束于传入的终止时间
	 */
	public static Map<String, Object> createCardAuth(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		BigDecimal amount = (BigDecimal) context.get("amount");
		Integer days = (Integer) context.get("days");

		// 起止时间，前端只填入日期，默认 开始为 当前时间
		Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		if(UtilValidate.isEmpty(fromDate)){
			fromDate = nowTimestamp;
		}
		fromDate = UtilDateTime.adjustTimestamp(fromDate, Calendar.SECOND, -2);
		context.put("fromDate", fromDate);

		Timestamp thruDate =(Timestamp)context.get("thruDate");
		if(thruDate != null){
			thruDate =  UtilDateTime.getDayStart(thruDate, 1);
		}else if(days != null && days > 0){
			thruDate = UtilDateTime.getDayStart(fromDate, days);
		}
		context.put("thruDate", thruDate);

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		String finAccountId = cloudCard.getString("finAccountId");

		// 余额检查
		BigDecimal balance = cloudCard.getBigDecimal("actualBalance");// availableBalance
		if(null == amount){
			amount = balance;
		}
		if(balance.compareTo(amount)<0 || balance.compareTo(CloudCardHelper.ZERO)<=0){
			Debug.logError("This card balance is Not Enough, can NOT authorize", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardBalanceIsNotEnough",
					UtilMisc.toMap("balance", balance.toPlainString()), locale));
		}

		// 检查是不是已经授权的卡
		// 卡号的前缀是带有 auth: 字样，说明是别人授权给我的卡，不能再次授权
		String cardCode = cloudCard.getString("cardNumber");
		if(UtilValidate.isNotEmpty(cardCode)&& cardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX)){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenAuthorized", locale));
		}
		// 存在SHAREHOLDER 角色的，未过期的 finAccountRole，表示此卡已授权给他人，不能再次授权
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
		if(isAuthorized){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenAuthorizedToOthers", locale));
		}

		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行授权", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotBeAuthorized", locale));
		}

		// 用户自己的userLogin没有 诸如 创建partyContact之类的权限，需要用到system权限
		GenericValue systemUser;
		try {
			systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
		} catch (GenericEntityException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		context.put("systemUser", systemUser);

		// 授权时判断用户是否存在， 不存在则创建用户
		Map<String, Object> getOrCreateCustomerOut = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");

		if(customerPartyId.equals(userLogin.getString("partyId"))){
			Debug.logInfo("用户partyId:[" + customerPartyId + "]试图授权cardNumber:[" + cardCode + "]给自己", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotBeAuthorizedToYourself", locale));
		}

		//创建giftCard
		String finAccountName = cloudCard.getString("finAccountName");
		Random rand = new Random();
		String newCardCode = new StringBuilder(CloudCardConstant.AUTH_CARD_CODE_PREFIX)
				.append(rand.nextInt(10)).append(rand.nextInt(10))
				.append(customerPartyId)
				.append(cloudCard.getString("finAccountCode")).toString();
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("finAccountId", finAccountId);
		giftCardInMap.put("cardNumber", newCardCode);
		giftCardInMap.put("description", finAccountName + "授权给用户" + customerPartyId);
		giftCardInMap.put("customerPartyId", customerPartyId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}

		// 为被授权人 创建finAccountRole，  SHAREHOLDER角色
		Map<String, Object> createCloudCardOutMap;
		try {
			createCloudCardOutMap = dispatcher.runSync("createFinAccountRole",
					UtilMisc.toMap("userLogin", systemUser, "locale",locale,
							"finAccountId", finAccountId,
							"partyId", customerPartyId,
							"roleTypeId", "SHAREHOLDER",
							// 卡授权一定有起止时间，
							"fromDate", fromDate,
							"thruDate", thruDate));
		} catch (GenericServiceException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createCloudCardOutMap)) {
			return createCloudCardOutMap;
		}

		// 授权金额的处理
		Map<String, Object> createFinAccountAuthOutMap;
		try {
			createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
							"finAccountId", finAccountId,
							"amount", amount,
							//不管授权什么时候生效，金额应该立马就划出去
							"fromDate", UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.SECOND, -2),
							"thruDate", thruDate)
					);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
			return createFinAccountAuthOutMap;
		}

		//授权短信通知
		String teleNumber = (String) context.get("teleNumber");
		String storeName = cloudCard.getString("distributorPartyName");
	    amount = cloudCard.getBigDecimal("actualBalance");

	    /*
	     * 1.代表短时间授权
	     * 2.代表长时间授权
	     */
	    String authType = "1";
	    String date = "";
	    if(UtilValidate.isEmpty(thruDate)){
	    	authType = "2";
	    }else if(UtilValidate.isEmpty(days) && UtilValidate.isNotEmpty(fromDate) && UtilValidate.isNotEmpty(thruDate)){
	    	//授权类型
	    	authType = "3";
	    	//开始时间
	    	Timestamp fromDateTemp = UtilDateTime.adjustTimestamp(fromDate, Calendar.SECOND, 2);
	    	int startYear = fromDateTemp.getYear() + 1900;
			int startMonth = fromDateTemp.getMonth() + 1;
			int startDay = fromDateTemp.getDate();
			String startTime = startYear + "年" + startMonth + "月" + startDay + "日";
	    	context.put("startTime", startTime);
	    	//结束时间
	    	Timestamp thruDateTemp = UtilDateTime.getDayEnd(thruDate, 1L);
	    	int endYear = thruDate.getYear() + 1900;
			int endMonth = thruDate.getMonth() + 1;
			int endDay = thruDate.getDate();
			String endTime = endYear + "年" + endMonth + "月" + endDay + "日";
	    	context.put("endTime", endTime);
	    }else{
			int year = thruDate.getYear() + 1900;
			int month = thruDate.getMonth() + 1;
			int day = thruDate.getDate();
			date = year + "年" + month + "月" + day + "日";
		    context.put("date", date);
	    }

	    //获取卡主人电话号码
  		String ownTeleNumber = null;
  		List<GenericValue> partyAndTelecomNumbers;
  		try {
  			partyAndTelecomNumbers = delegator.findByAnd("PartyAndTelecomNumber", UtilMisc.toMap("partyId",userLogin.getString("partyId"),"statusId","PARTY_ENABLED","statusId", "LEAD_ASSIGNED"));
  			if(UtilValidate.isNotEmpty(partyAndTelecomNumbers)){
  	    		GenericValue partyAndTelecomNumber = partyAndTelecomNumbers.get(0);
  	    		ownTeleNumber = partyAndTelecomNumber.getString("contactNumber");
  	    	}
  		} catch (GenericEntityException e) {
  			Debug.logError(e.getMessage(), module);
  			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
  		}

	    context.put("smsType", CloudCardConstant.USER_CREATE_CARD_AUTH_TYPE);
	    context.put("phone", teleNumber);
	    context.put("authType", authType);
	    context.put("teleNumber", ownTeleNumber);
		context.put("storeName", storeName);
		context.put("amount", amount);
		SmsServices.sendMessage(dctx, context);

		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("customerPartyId", customerPartyId);
		return result;
	}

	/**
	 * 解除卡授权
	 * 1、使相关finAccountRole失效
	 * 2、使相关giftCard，失效
	 * 3、使相关finAccountAuth失效
	 */
	public static Map<String, Object> revokeCardAuth(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");

		// 检查是不是已经授权的卡
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
		if(!isAuthorized){
			// 未授权的卡调用此接口直接忽略
			Debug.logWarning("This card has not been authorized, No need to revoke authorization", module);
			return ServiceUtil.returnSuccess();
		}

		String finAccountId = cloudCard.getString("finAccountId");
		Timestamp authFromDate =  (Timestamp) cardAuthorizeInfo.get("fromDate");// 授权开始时间
		String toPartyId =  (String) cardAuthorizeInfo.get("toPartyId");// 授权给谁了
		String roleTypeId = "SHAREHOLDER";

		Timestamp nowTimestamp = UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2) ;

		// 1、删除finAccountRole
		// 删除以前的finAccountRole，而不是更新thruDate使之失效 因为 finAccountRole中 fromDate是主键，如果回收授权后再次授权，fromDate一样的情况下会主键冲突
		Map<String, Object> updateFinAccountRoleOut;
		try {
			updateFinAccountRoleOut = dispatcher.runSync("deleteFinAccountRole", UtilMisc.toMap("userLogin", userLogin, "finAccountId", finAccountId,
					"fromDate", authFromDate, "partyId", toPartyId, "roleTypeId", roleTypeId));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if (ServiceUtil.isError(updateFinAccountRoleOut)) {
			return updateFinAccountRoleOut;
		}


		// 2、使用 finAccountId 和 toPartyId 查找所有未过期PaymentMethod，置为过期
//		EntityCondition  filterByDateCond =  EntityUtil.getFilterByDateExpr();
		EntityCondition  filterByDateCond =  EntityCondition.makeCondition(
				EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
				EntityOperator.OR,
				EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp())
			);

		EntityCondition paymentMethodCond = EntityCondition.makeCondition(UtilMisc.toMap("finAccountId",finAccountId, "partyId", toPartyId));
		try {
			List<GenericValue> paymentMethodList = delegator.findList("PaymentMethod", EntityCondition.makeCondition(paymentMethodCond, filterByDateCond),
					null, null, null, false);
			if(UtilValidate.isNotEmpty(paymentMethodList)){
				for(GenericValue g: paymentMethodList){
					g.set("thruDate", nowTimestamp);
				}
				delegator.storeAll(paymentMethodList);
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}


		// 3、使用finAccountId查找所有未过期 finAccountAuth，置为过期
		EntityCondition finAccountAuthCond = EntityCondition.makeCondition("finAccountId", finAccountId);
		finAccountAuthCond = EntityCondition.makeCondition(finAccountAuthCond, filterByDateCond);

		try {
			List<GenericValue> finAccountAuthList = delegator.findList("FinAccountAuth", finAccountAuthCond, null, null, null, false);
			if(UtilValidate.isNotEmpty(finAccountAuthList)){
				for(GenericValue g: finAccountAuthList){
					g.set("thruDate", nowTimestamp);
				}
				delegator.storeAll(finAccountAuthList);
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//授权短信通知
		String storeName = cloudCard.getString("distributorPartyName");
		String cardCodeTmp = cloudCard.getString("finAccountCode");
		String cardCode = cardCodeTmp.substring(cardCodeTmp.length()-4,cardCodeTmp.length());

		//获取卡主人电话号码
		String ownTeleNumber = null;
		List<GenericValue> partyAndTelecomNumbers;
		try {
			partyAndTelecomNumbers = delegator.findByAnd("PartyAndTelecomNumber", UtilMisc.toMap("partyId",userLogin.getString("partyId"),"statusId","PARTY_ENABLED","statusId", "LEAD_ASSIGNED"));
			if(UtilValidate.isNotEmpty(partyAndTelecomNumbers)){
	    		GenericValue partyAndTelecomNumber = partyAndTelecomNumbers.get(0);
	    		ownTeleNumber = partyAndTelecomNumber.getString("contactNumber");
	    	}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//获取被授权人电话号码
		String toTeleNumber = null;
		List<GenericValue> toPartyAndTelecomNumbers;
		try {
			EntityCondition partyIdCond = EntityCondition.makeCondition("partyId", cardAuthorizeInfo.get("toPartyId"));
			EntityCondition leadAssignedCond = EntityCondition.makeCondition("statusId", "LEAD_ASSIGNED");
			EntityCondition partyEnabledCond = EntityCondition.makeCondition("statusId", "PARTY_ENABLED");
			EntityCondition statusIdCond = EntityCondition.makeCondition(leadAssignedCond, EntityOperator.OR, partyEnabledCond);
			EntityCondition telNumCond = EntityCondition.makeCondition(partyIdCond, EntityOperator.AND, statusIdCond);
			toPartyAndTelecomNumbers = delegator.findList("PartyAndTelecomNumber", telNumCond, null, null, null, true);
			if(UtilValidate.isNotEmpty(toPartyAndTelecomNumbers)){
	    		GenericValue partyAndTelecomNumber = toPartyAndTelecomNumbers.get(0);
	    		toTeleNumber = partyAndTelecomNumber.getString("contactNumber");
	    	}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		context.put("smsType", CloudCardConstant.USER_REVOKE_CARD_AUTH_TYPE);
		context.put("phone", toTeleNumber);
	    context.put("teleNumber", ownTeleNumber);
		context.put("storeName", storeName);
		context.put("cardCode", cardCode);
		SmsServices.sendMessage(dctx, context);

		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}



	/**
	 *	开卡、充值统一服务,手机接口调用
	 *   1、扣商户开卡余额
	 *   2、存入用户账户
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> activateCloudCardAndRecharge(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		String teleNumber = (String) context.get("teleNumber");
		BigDecimal amount = (BigDecimal) context.get("amount");

		// TODO，数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能进行开卡、充值操作", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}

		// 传入的organizationPartyId必须是一个存在的partyGroup
		GenericValue partyGroup = null;

		//如果cardCode不存在,则创建一个
		if(UtilValidate.isEmpty(cardCode)){
			//根据teleNumber查找用户，不存在则创建
			context.put("ensureCustomerRelationship", true);
			Map<String, Object>	 getOrCreateCustomerOut  = CloudCardHelper.getOrCreateCustomer(dctx, context);
			if (ServiceUtil.isError(getOrCreateCustomerOut)) {
				return getOrCreateCustomerOut;
			}
			String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");

			try {
				partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
			if(null == partyGroup ){
				Debug.logWarning("商户："+organizationPartyId + "不存在", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError,
						"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
			}

			//判断用户是否在本店有没有卡
			try {

	        	EntityCondition thruDateEntityCondition = EntityCondition.makeCondition(EntityOperator.OR,EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp()),EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null));
	        	EntityCondition cloudCardInfoEntityCondition = EntityCondition.makeCondition(EntityOperator.AND,
	                    EntityCondition.makeCondition(UtilMisc.toMap("partyId", customerPartyId, "distributorPartyId", partyGroup.getString("partyId"))),thruDateEntityCondition);
				List<GenericValue> cloudCardInfoList = delegator.findList("CloudCardInfo", cloudCardInfoEntityCondition, null, null, null, false);

				//如果该用户存在本店授权卡，也可以继续购买本店的卡
				if(UtilValidate.isNotEmpty(cloudCardInfoList) || cloudCardInfoList.size() > 0){
					String oldCardCode = null;
					for (GenericValue cloudCardInfo : cloudCardInfoList){
						oldCardCode = cloudCardInfo.getString("cardNumber");
						if(UtilValidate.isNotEmpty(oldCardCode) && !oldCardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX)){
							return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUsersHaveCardsInOurStore", locale));
						}
					}
				}
			} catch (GenericEntityException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}

			//生成的卡号
			String newCardCode = null;
			String description = partyGroup.getString("groupName")+"库胖卡";
			String cardId = "";
			Timestamp fromDate = UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2);

			try {
				newCardCode = CloudCardHelper.generateCloudCardCode(delegator);
				String finAccountId = delegator.getNextSeqId("FinAccount");
				Map<String, Object> finAccountMap = FastMap.newInstance();
				finAccountMap.put("finAccountId", finAccountId);
				finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
				finAccountMap.put("finAccountName", description);
				finAccountMap.put("finAccountCode", newCardCode);
				finAccountMap.put("organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID);
				finAccountMap.put("ownerPartyId", customerPartyId);
				finAccountMap.put("currencyUomId", "CNY");
				finAccountMap.put("postToGlAccountId", "213200");
				finAccountMap.put("isRefundable", "Y");
				finAccountMap.put("statusId", "FNACT_ACTIVE");
		        finAccountMap.put("fromDate", fromDate);

				//保存finaccount数据
				GenericValue finAccount = delegator.makeValue("FinAccount", finAccountMap);
				finAccount.create();

				//保存finaccountRole数据
				GenericValue finAccountRole = delegator.makeValue("FinAccountRole", UtilMisc.toMap( "finAccountId", finAccountId, "partyId", organizationPartyId, "roleTypeId", "DISTRIBUTOR","fromDate", fromDate));
				finAccountRole.create();


	            // 创建PaymentMethod GiftCard
	            Map<String, Object> giftCardInMap = FastMap.newInstance();
	            giftCardInMap.putAll(context);
	            giftCardInMap.put("cardNumber", newCardCode);
	            giftCardInMap.put("description", description);
	            giftCardInMap.put("customerPartyId", customerPartyId);
	            giftCardInMap.put("finAccountId", finAccountId);
	            giftCardInMap.put("fromDate", fromDate);
	            Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
	            if (ServiceUtil.isError(giftCardOutMap)) {
	                return giftCardOutMap;
	            }

	    		context.put("cardCode", (String) newCardCode);
	    		context.put("cardId", (String) giftCardOutMap.get("paymentMethodId"));

			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardGenCardNumberError", locale));
			}
		}


		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		//1、根据二维码获取卡信息
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		String customerPartyId;
		String cardId;
		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			// 没有激活的卡，调用卡激活服务
			if(UtilValidate.isEmpty(teleNumber)){
				Debug.logInfo("激活新卡需要输入客户手机号码", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNeedTelenumber", locale));
			}else{
				Map<String, Object> createCloudCardOutMap;
				try {
					createCloudCardOutMap = dispatcher.runSync("activateCloudCard",
							UtilMisc.toMap("userLogin", userLogin, "locale",locale,
									"organizationPartyId", organizationPartyId,
									"teleNumber", teleNumber,
									"cardCode", cardCode));
				} catch (GenericServiceException e1) {
					Debug.logError(e1, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
				if (ServiceUtil.isError(createCloudCardOutMap)) {
					return createCloudCardOutMap;
				}

				customerPartyId = (String) createCloudCardOutMap.get("customerPartyId");
				cardId = (String)createCloudCardOutMap.get("cardId");
			}
		}else{
			// ownerPartyId 如果卡已授权给别人，partyId是被授权人，ownerPartyId是原主人
			customerPartyId = cloudCard.getString("partyId");
			cardId = cloudCard.getString("paymentMethodId");
		}

		//2、充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"organizationPartyId", organizationPartyId,
							"cardId", cardId,
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(rechargeCloudCardOutMap)) {
			return rechargeCloudCardOutMap;
		}

		//开卡成功后发送开卡短信通知
		if(UtilValidate.isNotEmpty(teleNumber)){
			cardCode = (String) context.get("cardCode");
			context.put("smsType", CloudCardConstant.USER_PURCHASE_CARD_SMS_TYPE);
			context.put("phone", teleNumber);
			context.put("storeName", partyGroup.getString("groupName"));
			context.put("cardCode", cardCode.substring(cardCode.length()-4,cardCode.length()));
			context.put("cardBalance", rechargeCloudCardOutMap.get("actualBalance"));
			SmsServices.sendMessage(dctx, context);
		}

		//查找商家名称
		//查找商家名称
        String groupName = "";
		try {
			 GenericValue pg = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			 if(UtilValidate.isNotEmpty(pg)){
				 groupName = pg.getString("groupName");
			 }
		} catch (GenericEntityException e) {
		    Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("amount", amount);
		result.put("storeName", groupName);
		result.put("cardBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("cardId", cardId);
		return result;
	}


	/**
	 * 激活卡服务
	 * 	1、根据teleNumber查找用户，不存在则创建一个用户
	 *	2、根据carCode找到FinAccount，修改ownerPartyId为用户id，并创建PaymentMethod
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> activateCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		// 检查是否系统生成的卡
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");


		// 没有被导出系统，交付印卡的账户，不能激活
		if(!"FNACT_PUBLISHED".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行激活", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotBeActivated", locale));
		}
		// 不是本商家的卡
		if(!organizationPartyId.equals(cloudCard.getString("distributorPartyId"))){
			Debug.logInfo("此卡[" + cardCode + "]不是商家[" +organizationPartyId + "]发行的卡，不能进行激活", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotOurCardCanNotBeActivated", locale));
		}

		// 1、根据teleNumber查找用户，不存在则创建
		context.put("ensureCustomerRelationship", true);
		Map<String, Object>	 getOrCreateCustomerOut  = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");


		// 2、更新finAccount状态，并创建paymentMethod
		// finAccount
		Map<String, Object> finAccountMap = FastMap.newInstance();
		finAccountMap.put("userLogin", userLogin);
		finAccountMap.put("locale", locale);
		finAccountMap.put("finAccountId", cloudCard.get("finAccountId"));
		finAccountMap.put("statusId", "FNACT_ACTIVE");
		finAccountMap.put("ownerPartyId", customerPartyId);
		finAccountMap.put("fromDate", UtilDateTime.nowTimestamp());

		Map<String, Object> finAccountOutMap;
		try {
			finAccountOutMap = dispatcher.runSync("updateFinAccount", finAccountMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountOutMap)) {
			return finAccountOutMap;
		}
		String finAccountId = (String) finAccountOutMap.get("finAccountId");


		// 创建PaymentMethod  GiftCard
		String description = cloudCard.getString("finAccountName");
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("cardNumber", cardCode);
		giftCardInMap.put("description", description);
		giftCardInMap.put("customerPartyId", customerPartyId);
		giftCardInMap.put("finAccountId", finAccountId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}

		// 3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("customerPartyId", customerPartyId);
		result.put("finAccountId", finAccountId);
		result.put("cardId", giftCardOutMap.get("paymentMethodId"));
		return result;
	}


	/**
     *  充值服务--收用户款
     *   1、扣商户开卡余额
     *   2、商户账户收款
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> rechargeCloudCardReceipt(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String organizationPartyId = (String) context.get("organizationPartyId");
        BigDecimal amount = (BigDecimal) context.get("amount");

        String payChannel = (String) context.get("payChannel"); // 支付渠道
        String paymentMethodTypeId = CloudCardConstant.PMT_CASH;
        if (CloudCardConstant.PAY_CHANNEL_WXPAY.equals(payChannel)) {
            paymentMethodTypeId = CloudCardConstant.PMT_WXPAY;
        } else if (CloudCardConstant.PAY_CHANNEL_ALIPAY.equals(payChannel)) {
            paymentMethodTypeId = CloudCardConstant.PMT_ALIPAY;
        }

        Timestamp nowTimestamp = UtilDateTime.nowTimestamp();

        Map<String, Object> checkParamOut = checkInputParam(dctx, context);
        if(ServiceUtil.isError(checkParamOut)){
            return checkParamOut;
        }
        GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
        String cardId = (String) checkParamOut.get("cardId");
        GenericValue systemUserLogin = (GenericValue) checkParamOut.get("systemUserLogin");

        // 不是本商家的卡
        if(!organizationPartyId.equals(cloudCard.getString("distributorPartyId"))){
            Debug.logInfo("此卡[" + cloudCard.getString("cardNumber") + "]不是商家[" +organizationPartyId + "]发行的卡，不能进行充值", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotOurCardCanNotRecharge", locale));
        }

        // 获取商户用于管理卖卡限额的金融账户
        GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
            Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }

        // 获取商户用于收款的金融账户
        GenericValue receiptAccount = CloudCardHelper.getReceiptAccount(delegator, organizationPartyId, true);
        if (UtilValidate.isEmpty(receiptAccount)) {
            Debug.logError("商家[" + organizationPartyId + "]未配置收款账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }

        // 1、扣商户开卡余额
        String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");

        // 检查开卡余额是否够用
        BigDecimal creditLimit = creditLimitAccount.getBigDecimal("availableBalance");
        if(null==creditLimit){
            creditLimit = BigDecimal.ZERO;
        }
        if (creditLimit.compareTo(amount) < 0) {
            Debug.logInfo("商家["+organizationPartyId+"]可用卖卡余额不足, 余额：" + creditLimit.toPlainString(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCreditIsNotEnough", UtilMisc.toMap("balance", creditLimit.toPlainString()), locale));
        }

        // 商户扣减开卡余额
        Map<String, Object> createFinAccountAuthOutMap;
        try {
            createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
                            "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
                            "finAccountId", creditLimitAccountId,
                            "amount", amount,
                            //FIXME 奇怪的BUG，如果fromDate直接用当前时间戳，
                            // 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
                            // 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
                            // 所以，这里人为地把fromDate时间提前2秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
                            "fromDate", UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.SECOND, -2)));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (!ServiceUtil.isSuccess(createFinAccountAuthOutMap)) {
            return createFinAccountAuthOutMap;
        }
        delegator.clearCacheLine(creditLimitAccount);

        // 2、商户收款 用户--》商家
        String receiptAccountId = receiptAccount.getString("finAccountId");
        String customerPartyId = cloudCard.getString("ownerPartyId");
        Map<String, Object> receiptAccountDepositOutMap;
        try {
            receiptAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale, "statusId", "PMNT_RECEIVED", "currencyUomId",
                            CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT",
                            "paymentTypeId","CUSTOMER_DEPOSIT",
                            "paymentMethodTypeId", paymentMethodTypeId, "finAccountId", receiptAccountId,
                            "partyIdFrom", customerPartyId, "partyIdTo", organizationPartyId
                            , "amount", amount, "comments", "充值，商家收款",
                            "reasonEnumId", "FATR_REPLENISH"));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(receiptAccountDepositOutMap)) {
            return receiptAccountDepositOutMap;
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("customerPartyId", customerPartyId);
        result.put("amount", amount);
        result.put("cardId", cardId);
        result.put("receiptPaymentId", receiptAccountDepositOutMap.get("paymentId"));
        return result;
    }


    /**
     *	充值服务--入用户账户
     *   1、从商户自己的收款账户转出
     *   2、转入用户账户
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> rechargeCloudCardDeposit(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        // 收用户款的payment
        String receiptPaymentId = (String) context.get("receiptPaymentId");
        GenericValue receiptPayment;
        try {
            receiptPayment = delegator.findByPrimaryKey("Payment", UtilMisc.toMap("paymentId", receiptPaymentId));
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (null == receiptPayment) {
            Debug.logWarning("找不到收款payment[" + receiptPaymentId + "]", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "ErrorCode:TODO", locale)); //TODO 定义错误码
        }
        // TODO 检查payment的状态，是不是 PMNT_RECEIVED?


        String organizationPartyId = (String) context.get("organizationPartyId");
        BigDecimal amount =  receiptPayment.getBigDecimal("amount");

        Map<String, Object> checkParamOut = checkInputParam(dctx, context);
        if(ServiceUtil.isError(checkParamOut)){
            return checkParamOut;
        }
        GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
        String cardId = (String) checkParamOut.get("cardId");
        String finAccountId = cloudCard.getString("finAccountId");
        GenericValue systemUserLogin = (GenericValue) checkParamOut.get("systemUserLogin");

        // 不是本商家的卡
        if(!organizationPartyId.equals(cloudCard.getString("distributorPartyId"))){
            Debug.logInfo("此卡[" + cloudCard.getString("cardNumber") + "]不是商家[" +organizationPartyId + "]发行的卡，不能进行充值", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotOurCardCanNotRecharge", locale));
        }

        // 获取商户用于收款的金融账户
        GenericValue receiptAccount = CloudCardHelper.getReceiptAccount(delegator, organizationPartyId, true);
        if (UtilValidate.isEmpty(receiptAccount)) {
            Debug.logError("商家[" + organizationPartyId + "]未配置收款账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }

        String receiptAccountId = receiptAccount.getString("finAccountId");
        String customerPartyId = cloudCard.getString("ownerPartyId");

        // 商家收款账户---》用户账户
        // 1、从商家账户转出
        Map<String, Object> finAccountWithdrawOutMap;
        try {
            finAccountWithdrawOutMap = dispatcher.runSync("createFinAccountTrans",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
                            "finAccountId", receiptAccountId,
                            "partyId", customerPartyId,
                            "amount",amount,
                            "finAccountTransTypeId","WITHDRAWAL",
                            "reasonEnumId", "FATR_REPLENISH",
                            "glAccountId", "111000",//TODO 待定
                            "comments", "充值",
                            "statusId", "FINACT_TRNS_APPROVED"));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
            return finAccountWithdrawOutMap;
        }

        // 2、存入用户账户
        Map<String, Object> finAccountDepositOutMap;
        try {
            finAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale, "statusId", "PMNT_RECEIVED", "currencyUomId",
                            CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT",
                            "paymentTypeId","GC_DEPOSIT",
                            "finAccountId", finAccountId,
                            "paymentMethodId", cardId,
                            "paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
                            customerPartyId, "amount", amount, "comments", "充值，存入用户账户",
                            "reasonEnumId", "FATR_REPLENISH"));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(finAccountDepositOutMap)) {
            return finAccountDepositOutMap;
        }

        String depositPaymentId = (String) finAccountDepositOutMap.get("paymentId");


        //3、应用用户支付给商家，和商家存入用户账户 的两个payment
        Map<String, Object> paymentApplicationOutMap;
        try {
            paymentApplicationOutMap = dispatcher.runSync("createPaymentApplication",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
                            "amountApplied", amount,
                            "paymentId", receiptPaymentId,
                            "toPaymentId", depositPaymentId));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(paymentApplicationOutMap)) {
            return paymentApplicationOutMap;
        }

        //4、修改这两个payment的状态为PMNT_CONFIRMED
        try {
            Map<String, Object> setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus", UtilMisc.toMap("userLogin", systemUserLogin,
                    "locale", locale, "paymentId", depositPaymentId, "statusId", "PMNT_CONFIRMED"));

            if (ServiceUtil.isError(setPaymentStatusOutMap)) {
                return setPaymentStatusOutMap;
            }

            setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus", UtilMisc.toMap("userLogin", systemUserLogin,
                    "locale", locale, "paymentId", receiptPaymentId, "statusId", "PMNT_CONFIRMED"));

            if (ServiceUtil.isError(setPaymentStatusOutMap)) {
                return setPaymentStatusOutMap;
            }
        } catch (GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }


        // 因为余额会被ECA更新，这里是旧值，
        BigDecimal actualBalance = (BigDecimal) cloudCard.get("actualBalance");
        if(null == actualBalance){
            actualBalance = BigDecimal.ZERO;
        }
        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("actualBalance", actualBalance.add(amount));
        result.put("customerPartyId", customerPartyId);
        result.put("amount", amount);
        result.put("cardId", cardId);
        return result;
    }

    /**
     * 充值服务
     *
     * <pre>
     *    1、调用rechargeCloudCardReceipt服务:
     *         1.1、扣商户开卡余额
     *         1.2、商户账户收款
     *
     *    2、调用rechargeCloudCardDeposit服务:
     *         2.1、商户账户将充值款项转入用户账户
     * </pre>
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> rechargeCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

        String organizationPartyId = (String) context.get("organizationPartyId");
        BigDecimal amount = (BigDecimal) context.get("amount");

        Map<String, Object> checkParamOut = checkInputParam(dctx, context);
        if (ServiceUtil.isError(checkParamOut)) {
            return checkParamOut;
        }
        GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
        String cardId = (String) checkParamOut.get("cardId");

        try {
            Map<String, Object> rechargeCloudCardReceiptOutMap = dispatcher.runSync("rechargeCloudCardReceipt", UtilMisc.toMap("userLogin", userLogin, "locale",
                    locale, "cloudCard", cloudCard, "amount", amount, "organizationPartyId", organizationPartyId));

            if (!ServiceUtil.isSuccess(rechargeCloudCardReceiptOutMap)) {
                return rechargeCloudCardReceiptOutMap;
            }

            String receiptPaymentId = (String) rechargeCloudCardReceiptOutMap.get("receiptPaymentId");

            Map<String, Object> rechargeCloudCardDepositOutMap = dispatcher.runSync("rechargeCloudCardDeposit", UtilMisc.toMap("userLogin", userLogin, "locale",
                    locale, "cloudCard", cloudCard, "receiptPaymentId", receiptPaymentId, "organizationPartyId", organizationPartyId));

            if (!ServiceUtil.isSuccess(rechargeCloudCardDepositOutMap)) {
                return rechargeCloudCardDepositOutMap;
            }
            // 返回结果
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("actualBalance", rechargeCloudCardDepositOutMap.get("actualBalance"));
            result.put("customerPartyId", rechargeCloudCardDepositOutMap.get("customerPartyId"));
            result.put("amount", rechargeCloudCardDepositOutMap.get("amount"));
            result.put("cardId", cardId);
            return result;
        } catch (GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
    }


	/**
	 * 客户 扫商家码 付款
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> customerWithdraw(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

        String qrCode = (String) context.get("qrCode");
        String storeId = (String) context.get("storeId");
        if (UtilValidate.isEmpty(storeId) && UtilValidate.isEmpty(qrCode)) {
            Debug.logWarning("缺少参数： storeId  和 qrCode 不能同时为空", module);
            return ServiceUtil.returnError(
                    UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardMissingParameter", UtilMisc.toMap("param", "storeId"), locale));
        }

		GenericValue partyGroup = null;
		try {
            partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", storeId));
            if (null == partyGroup) {
                partyGroup = CloudCardHelper.getPartyGroupByQRcode(qrCode, delegator);
            }
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(null == partyGroup){
            Debug.logWarning("商户 storeId[" + storeId + "] qrCode[" + qrCode + "]不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardOrganizationPartyNotFound", locale));
		}

		storeId = (String) partyGroup.getString("partyId");

		context.put("partyGroup", partyGroup);
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		// 获取用户用于支付的云卡
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		String cardId = (String) checkParamOut.get("cardId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		String customerPartyId = userLogin.getString("partyId");
		String cardHolder = cloudCard.getString("partyId");
		if( null==customerPartyId || !customerPartyId.equals(cardHolder)){
			Debug.logWarning("不是本人的卡，当前用户partyId[" + customerPartyId + "] 卡ID[" + cardId + "] 持卡人[" + cardHolder + "]",
					module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotYourCard", locale));
		}

		// 调用内部 云卡支付服务
		Map<String, Object> cloudCardWithdrawOut;
		try {
			cloudCardWithdrawOut = dispatcher.runSync("cloudCardWithdraw", UtilMisc.toMap("userLogin", userLogin, "locale",locale,
					"organizationPartyId", storeId, "cloudCard", cloudCard, "amount",amount));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(cloudCardWithdrawOut)) {
			return cloudCardWithdrawOut;
		}

		BigDecimal cardBalance = (BigDecimal) cloudCardWithdrawOut.get("cardBalance");

		// 返回成功
		Map<String, Object> resp = ServiceUtil.returnSuccess();
		resp.put("amount", amount);
		resp.put("cardId", cardId);
		resp.put("storeId", storeId);
		resp.put("storeName", partyGroup.getString("groupName"));
		resp.put("cardBalance", cardBalance);
		return resp;
	}

	/**
	 * 商家扫卡收款
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> receiptByCardCode(DispatchContext dctx, Map<String, Object> context) {

		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");

		// 数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logWarning("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行扫码消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}


		// 调用内部 云卡支付服务
		Map<String, Object> cloudCardWithdrawOut;
		try {
			cloudCardWithdrawOut = dispatcher.runSync("cloudCardWithdraw", context);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		return cloudCardWithdrawOut;
	}

	/**
	 *	云卡支付服务 --底层内部服务
	 *   1、扣除用户卡内余额
	 *   2、创建发票
	 *   3、应用支付与发票
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> cloudCardWithdraw(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		//GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		// 获取用户用于支付的帐号
		String cardCode = (String) checkParamOut.get("cardCode");
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		if(null == cloudCard){
			Debug.logWarning("找不到云卡", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotFound", locale));
		}
		// 没有激活的账户，不能用于付款
		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			Debug.logWarning("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行付款", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenDisabled", locale));
		}

		// 检查商家收款帐号
		GenericValue receiptAccount = CloudCardHelper.getReceiptAccount(delegator, organizationPartyId);
		if(null==receiptAccount){
			Debug.logError("商家["+organizationPartyId+"]用于收款的账户没有正确配置", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError,
					"CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
		}
		String receiptAccountId = receiptAccount.getString("finAccountId");


		// 是别人授权给我的卡
		boolean isAuth2me =  cardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX);
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");

		String finAccountId = cloudCard.getString("finAccountId");
		String customerPartyId = cloudCard.getString("ownerPartyId");// ownerPartyId 是原卡主， partyId 是持卡人，交易记录记录在卡主上
		String cardId = cloudCard.getString("paymentMethodId");
		String distributorPartyId = cloudCard.getString("distributorPartyId");
		boolean isSameStore = distributorPartyId.equals(organizationPartyId); //是否 店内消费（本店卖出去的卡在本店消费）
		/*if (!isSameStore) {
            // 如果是跨店，检查是否加入圈子，是否圈友关系被冻结，是否是圈主的卡(即distributorPartyId是否为圈主) 等条件
            try {
                GenericValue groupRel = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, false);
                if (null == groupRel) {
                    // 未加入圈子，不能跨店
                    Debug.logWarning("本店[" + organizationPartyId + "]没有加入圈子，不能使用其他店[" + distributorPartyId + "]的卡", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardStoreNotInAGroup", locale));
                }

                if (CloudCardHelper.isFrozenGroupRelationship(groupRel)) {
                    // 圈子关系已被冻结
                    Debug.logWarning("本店[" + organizationPartyId + "]在圈子中被冻结，不再接收跨店交易", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardGroupRelationshipIsFrozen", locale));
                }

                // 获取本店所在圈子的 圈主
                String groupOwnerId = CloudCardHelper.getGroupOwneIdByStoreId(delegator, organizationPartyId, false);
                if (!distributorPartyId.equals(groupOwnerId)) {
                    // 发卡商家 不是本店所在圈子的的圈主
                    Debug.logWarning("此卡[" + cardId + "]的发卡商家[" + distributorPartyId + "]不是本店[" + organizationPartyId + "]所在圈子的圈主[" + groupOwnerId + "]，不能支付",
                            module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotGroupOwnerCard", locale));
                }
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
        }*/

		// 1、扣除用户余额
		// 检查余额是否够用
		BigDecimal cardBalance = CloudCardHelper.getCloudCardBalance(cloudCard, isAuthorized);
		if(null==cardBalance){
			cardBalance = BigDecimal.ZERO;
		}
		if (cardBalance.compareTo(amount) < 0) {
			if(isAuth2me){
				Debug.logWarning("授权可用余额不足, 余额：" + cardBalance.toPlainString(), module);
			}else{
				Debug.logWarning("余额不足, 余额：" + cardBalance.toPlainString(), module);
			}
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardBalanceIsNotEnough",
					UtilMisc.toMap("balance", cardBalance.toPlainString()), locale));
		}

		// 因为 商家 和 用户 都可能调用云卡支付服务，所以内部直接用system权限，在外部做权限限制处理
		GenericValue systemUserLogin = (GenericValue) context.get("userLogin");
		try {
			systemUserLogin = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
		} catch (GenericEntityException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(isAuth2me){
			// 用别人授权的卡支付，授权剩余额度需要扣减，扣减方法：为此账户创建负数金额的finAccountAuth
			Map<String, Object> createFinAccountAuthOutMap;
			try {
				createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
						UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
								"currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
								"finAccountId", finAccountId,
								"amount", amount.negate(),
								"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2),
								"thruDate", cloudCard.getTimestamp("thruDate")));
			} catch (GenericServiceException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
				return createFinAccountAuthOutMap;
			}
		}

		// 扣款
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
							"statusId", "PMNT_SENT",
							"currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "WITHDRAWAL",
							"paymentTypeId","GC_WITHDRAWAL",
							"finAccountId", finAccountId,
							"paymentMethodId",cardId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", customerPartyId, "partyIdTo",
							organizationPartyId, "amount", amount, "comments", "顾客消费",
							"reasonEnumId", "FATR_PURCHASE"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawalOutMap)) {
			return finAccountWithdrawalOutMap;
		}

		String withdrawalPaymentId = (String) finAccountWithdrawalOutMap.get("paymentId");

		// 2、创建发票及发票明细
		Map<String, Object> invoiceOutMap;
		String description = customerPartyId +"在"+organizationPartyId+"消费，金额："+amount.toPlainString();
		try {
			invoiceOutMap = dispatcher.runSync("createInvoice",
					UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
							"statusId", "INVOICE_IN_PROCESS",
							"invoiceTypeId", "SALES_INVOICE",
							"currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
							"description", description,
							"partyId", customerPartyId,
							"partyIdFrom", organizationPartyId));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceOutMap)) {
			return invoiceOutMap;
		}
		String invoiceId = (String) invoiceOutMap.get("invoiceId");

		Map<String, Object> invoiceItemOutMap;
		try {
			invoiceItemOutMap = dispatcher.runSync("createInvoiceItem",
					UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
							"invoiceId",invoiceId,
							"description", description,
							"invoiceItemTypeId", "INV_PROD_ITEM",
							"quantity", BigDecimal.ONE,
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceItemOutMap)) {
			return invoiceItemOutMap;
		}

		// 3、创建 PaymentApplication 应用 发票 与  付款payment，  并修改发票状态为INVOICE_APPROVED
		try {
			//注： 将商家开的invoice和客户付款的payment进行应用，
			// 虽然后面修改发票状态为INVOICE_APPROVED会触发ECA去自动去找payment与invoice进行应用
			// 但是，既然这里能拿到paymentId和invoiceId，就直接创建好PaymentApplication，
			// 免得 setInvoiceStatus的后续的ECA去搜索payment，
			// 既费时，也还可能出现找到的paymentId 不是前面刚生成的withdrawalPaymentId的情况，导致自动创建paymentApplication失败
			Map<String, Object> paymentApplicationOutMap = dispatcher.runSync("createPaymentApplication",
					UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "amountApplied", amount, "paymentId",
							withdrawalPaymentId, "invoiceId", invoiceId));
			if (ServiceUtil.isError(paymentApplicationOutMap)) {
				return paymentApplicationOutMap;
			}

			Map<String, Object> setInvoiceStatusOutMap = dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap(
					"userLogin", systemUserLogin, "locale", locale, "invoiceId", invoiceId, "statusId", "INVOICE_APPROVED"));
			if (ServiceUtil.isError(setInvoiceStatusOutMap)) {
				return setInvoiceStatusOutMap;
			}
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		// 4、商家收款账户入账
		Map<String, Object> finAccountDepositOutMap;
		try {
			finAccountDepositOutMap = dispatcher.runSync("createFinAccountTrans",
					UtilMisc.toMap("userLogin", systemUserLogin, "locale",locale,
							"finAccountId",receiptAccountId,
							"partyId", customerPartyId,
							"amount",amount,
							"finAccountTransTypeId","DEPOSIT",
							"paymentId", withdrawalPaymentId,
							"reasonEnumId", "FATR_PURCHASE",
							"glAccountId", "111000",//TODO 待定
							"comments", "顾客消费-商家收款",
							"statusId", "FINACT_TRNS_APPROVED"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountDepositOutMap)) {
			return finAccountDepositOutMap;
		}

		// 5、如果不是跨店交易， 回冲卖卡限额  并修改支付状态为 PMNT_CONFIRMED
        if (isSameStore) {
            GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
            if (UtilValidate.isEmpty(creditLimitAccount)) {
                Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError",
                        UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
            }

            String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");

            // 卖卡限额回冲
            Map<String, Object> createFinAccountAuthOutMap;
            try {
                createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
                        UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
                                "finAccountId", creditLimitAccountId, "amount", amount.negate(),
                                //FIXME 奇怪的BUG，如果fromDate直接用当前时间戳，
                                // 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
                                // 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
                                // 所以，这里人为地把fromDate时间提前2秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
                                "fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
            } catch (GenericServiceException e1) {
                Debug.logError(e1, module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
            if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
                return createFinAccountAuthOutMap;
            }
            delegator.clearCacheLine(creditLimitAccount);

            // 设置Payment状态为PMNT_CONFIRMED
            Map<String, Object> setPaymentStatusOutMap;
            try {
                setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
                        UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "paymentId", withdrawalPaymentId, "statusId", "PMNT_CONFIRMED"));
            } catch (GenericServiceException e1) {
                Debug.logError(e1, module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
            if (ServiceUtil.isError(setPaymentStatusOutMap)) {
                return setPaymentStatusOutMap;
            }
        } else {
            // 如果是跨店消费，
            // 在双方（发卡的店 和 交易发生店 ）的 对账账户里面记账（发卡方记正数 表示应付、交易放生方记负数，表示应收）

            // 发卡店的 结算账户
            GenericValue distributorPartySettlementsAccount = CloudCardHelper.getSettlementAccount(delegator, distributorPartyId);
            if (null == distributorPartySettlementsAccount) {
                Debug.logError("发卡商家[" + distributorPartyId + "]用于平台对账结算的账户没有正确配置", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError",
                        UtilMisc.toMap("organizationPartyId", distributorPartyId), locale));
            }
            String distributorAccountId = distributorPartySettlementsAccount.getString("finAccountId");

            // 本店（交易发生店）的 结算账户
            GenericValue tradePartySettlementsAccount = CloudCardHelper.getSettlementAccount(delegator, organizationPartyId);
            if (null == tradePartySettlementsAccount) {
                Debug.logError("本店[" + organizationPartyId + "]用于平台对账结算的账户没有正确配置", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError",
                        UtilMisc.toMap("organizationPartyId", distributorPartyId), locale));
            }
            String tradePartyAccountId = tradePartySettlementsAccount.getString("finAccountId");

            try {
                Map<String, Object> finAccountDepoistOutMap = dispatcher.runSync("createFinAccountTrans",
                        UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "finAccountId", distributorAccountId, "partyId", customerPartyId,
                                "amount", amount, "finAccountTransTypeId", "DEPOSIT", "paymentId", withdrawalPaymentId, "reasonEnumId", "FATR_PURCHASE",
                                "glAccountId", "210000", "comments", "应付", "statusId", "FINACT_TRNS_APPROVED"));
                if (ServiceUtil.isError(finAccountDepoistOutMap)) {
                    return finAccountDepoistOutMap;
                }

                Map<String, Object> finAccountWithdrawOutMap = dispatcher.runSync("createFinAccountTrans",
                        UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "finAccountId", tradePartyAccountId, "partyId", customerPartyId,
                                "amount", amount, "finAccountTransTypeId", "WITHDRAWAL", "paymentId", withdrawalPaymentId, "reasonEnumId", "FATR_PURCHASE",
                                "glAccountId", "210000", "comments", "应收", "statusId", "FINACT_TRNS_APPROVED"));
                if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
                    return finAccountWithdrawOutMap;
                }

                // 在paymentAttribute里面记录 结算请求0次， 发卡商家id， 发卡商家名
                CloudCardHelper.setPaymentAttr(delegator, withdrawalPaymentId, "settlementReqCount", "0");
                CloudCardHelper.setPaymentAttr(delegator, withdrawalPaymentId, "cardSellerId", distributorPartyId);
                String distributorPartyName = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", distributorPartyId))
                        .getString("groupName");
                CloudCardHelper.setPaymentAttr(delegator, withdrawalPaymentId, "cardSellerName", distributorPartyName);

            } catch (GenericServiceException | GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
        }

        //查找商家名称
        String groupName = "";
		try {
			 GenericValue pg = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			 if(UtilValidate.isNotEmpty(pg)){
				 groupName = pg.getString("groupName");
			 }
		} catch (GenericEntityException e) {
		    Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("amount", amount);
		result.put("storeName", groupName);
		//只是显示一下余额，不用再去数据库查询了直接减掉
		result.put("cardBalance", cardBalance.subtract(amount).setScale(CloudCardHelper.decimals, CloudCardHelper.rounding));
		result.put("customerPartyId", customerPartyId);
		result.put("cardId", cardId);
		return result;
	}


	/**
	 * 充值、支付的公共参数检查，并根据传入的 cardCode 或 cardId 来获取 用户的云卡对象
	 * @param dctx
	 * @param context
	 * @return
	 */
	private static Map<String, Object> checkInputParam(DispatchContext dctx, Map<String, Object> context){
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String finAccountId = (String) context.get("finAccountId");
		String cardCode = (String) context.get("cardCode");
		String cardId = (String) context.get("cardId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		Map<String, Object> retMap = ServiceUtil.returnSuccess();

		// 金额必须为正数
		if (UtilValidate.isNotEmpty(amount) && amount.compareTo(BigDecimal.ZERO) <= 0) {
			Debug.logWarning("金额不合法，必须为正数", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceAccountingError, "AccountingFinAccountMustBePositive", locale));
		}

		// 起止时间的检查
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		Timestamp thruDate =(Timestamp)context.get("thruDate");
		if(null!=fromDate && null!=thruDate && fromDate.after(thruDate)){
			Debug.logWarning("起止日期不合法，开始日期必须小于结束日期", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardFromDateMustBeforeThruDate", locale));
		}
		if(null!=thruDate && thruDate.before(UtilDateTime.nowTimestamp())){
			Debug.logWarning("结束日期必须大于当前日期", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardThruDateMustAfterNow", locale));
		}

		// organizationPartyId 必须在数据库中存在
		GenericValue partyGroup = (GenericValue) context.get("partyGroup");
		if(null == partyGroup && UtilValidate.isNotEmpty(organizationPartyId)){
			try {
				partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
			if(null == partyGroup ){
				Debug.logWarning("商户："+organizationPartyId + "不存在", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError,
						"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
			}
			retMap.put("partyGroup", partyGroup);
		}

		// finAccountId 必须存在
		if(UtilValidate.isNotEmpty(finAccountId)){
			GenericValue finAccount;
			try {
				finAccount = delegator.findByPrimaryKey("FinAccount", UtilMisc.toMap("finAccountId", finAccountId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
			if (null == finAccount) {
				Debug.logWarning("金融账户："+finAccountId + "不存在", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardAccountNotFound", UtilMisc.toMap("finAccountId", finAccountId), locale));
			}
			retMap.put("finAccount", finAccount);
		}


		GenericValue cloudCard = (GenericValue) context.get("cloudCard");
		boolean needFindCard = false ;
	    // 卡ID
        if(null == cloudCard && UtilValidate.isNotEmpty(cardId)){
            needFindCard = true;
            try {
                cloudCard = CloudCardHelper.getCloudCardByCardId(cardId, delegator);
            } catch (GenericEntityException e2) {
                Debug.logError(e2.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
        }

        // 卡二维码 或用户付款码
        if (null == cloudCard && UtilValidate.isNotEmpty(cardCode)) {
            needFindCard = true;

            // 如果扫的码 cardCode字段是 个用户付款码，则根据付款码进行自动找卡
            if (cardCode.startsWith(CloudCardConstant.CODE_PREFIX_PAY_)) {

                cardCode = cardCode.substring(CloudCardConstant.CODE_PREFIX_PAY_.length());

                String iss = EntityUtilProperties.getPropertyValue("cloudcard", "qrCode.issuer", delegator);
                String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard", "qrCode.secret", delegator);
                String customerPartyId = null;
                try {
                    JWTVerifier verifier = new JWTVerifier(tokenSecret, null, iss);
                    Map<String, Object> claims = verifier.verify(cardCode);
                    customerPartyId = (String) claims.get("user");
                } catch (JWTExpiredException e1) {
                    // 用户付款码已过期
                    Debug.logWarning("付款码已经过期", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPaymentCodeExpired", locale));
                } catch (Exception e) {
                    // 非法的码
                    Debug.logError(e.getMessage(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPaymentCodeIllegal", locale));
                }

                // 优先取圈主卡，余额不够，再取余额够的圈子卡
                String groupOwnerId = null;
                List<GenericValue> cloudCards = null;
                try {
                    EntityCondition cond = CloudCardInfoUtil.createLookupMyStoreCardCondition(delegator, customerPartyId, organizationPartyId);
                    cloudCards = delegator.findList("CloudCardInfo", cond, null, UtilMisc.toList("-fromDate"), null, false);
                    groupOwnerId = CloudCardHelper.getGroupOwneIdByStoreId(delegator, organizationPartyId, true);
                } catch (GenericEntityException e) {
                    Debug.logError(e.getMessage(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
                }

                if (UtilValidate.isEmpty(cloudCards)) {
                    // 本店没有卡
                    Debug.logWarning("用户在本店没有卡", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNoCardInTheStore", locale));
                }
                for (GenericValue card : cloudCards) {
                    BigDecimal ba = CloudCardHelper.getCloudCardBalance(card);
                    if (ba.compareTo(amount) >= 0) {
                        cloudCard = card;
                        if (null == groupOwnerId || card.getString("distributorPartyId").equals(groupOwnerId)) {
                            break;
                        }
                    }
                }
                if (null == cloudCard) {
                    // 没有满足条件的卡
                    Debug.logWarning("没有满足条件的卡，可能是卡余额不足", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNoCardBalanceEnough", locale));
                }

            } else {
                // 卡二维码
                try {
                    cloudCard = CloudCardHelper.getCloudCardByCardCode(cardCode, delegator);
                } catch (GenericEntityException e2) {
                    Debug.logError(e2.getMessage(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
                }
            }
        }

		if(needFindCard && null == cloudCard){
			// 需要查找卡，却找不到卡
			Debug.logWarning("找不到云卡，cardCode[" + cardCode + "] or cardId[" + cardId + "]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotFound", locale));
		}

		if(null != cloudCard){
			// 如果找到卡，检查状态
			String statusId = cloudCard.getString("statusId");
			if("FNACT_CANCELLED".equals(statusId) || "FNACT_MANFROZEN".equals(statusId)){
				Debug.logWarning("此卡[finAccountId = " + cloudCard.get("finAccountId") + "]状态不可用，当前状态[" + statusId +"]", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenDisabled", locale));
			}

			retMap.put("cardCode", UtilFormatOut.checkNull(cardCode, cloudCard.getString("cardNumber"), cloudCard.getString("finAccountCode")));
			retMap.put("cardId", UtilFormatOut.checkNull(cardId, cloudCard.getString("paymentMethodId")));
			retMap.put("cloudCard", cloudCard);
		}

		 // 后续可能要用到 system用户操作
        GenericValue systemUserLogin = (GenericValue) context.get("systemUserLogin");
        try {
            systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
            retMap.put("systemUserLogin", systemUserLogin);
        } catch (GenericEntityException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
		return retMap;
	}

	/**
	 * 批量生成卡号
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> genNewCloudCardCode(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		//GenericValue userLogin = (GenericValue) context.get("userLogin");
		String quantity = (String) context.get("quantity");
		String currencyUomId = (String)context.get("currencyUomId");
		String finAccountName = (String) context.get("finAccountName");
		String organizationPartyId = (String) context.get("organizationPartyId");

		// 传入的organizationPartyId必须是一个存在的partyGroup
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if(null == partyGroup ){
			Debug.logWarning("商户："+organizationPartyId + "不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError,
					"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
		}

		for(int i = 0;i < Integer.valueOf(quantity);i++){
			try {
				//生成的卡号
				String newCardCode = CloudCardHelper.generateCloudCardCode(delegator);
				String finAccountId = delegator.getNextSeqId("FinAccount");
				Map<String, Object> finAccountMap = FastMap.newInstance();
				finAccountMap.put("finAccountId", finAccountId);
				finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT");
				finAccountMap.put("statusId", "FNACT_CREATED");
				finAccountMap.put("finAccountName", finAccountName);
				finAccountMap.put("finAccountCode", newCardCode);
				finAccountMap.put("organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID);
				finAccountMap.put("ownerPartyId", "_NA_");
				finAccountMap.put("currencyUomId", currencyUomId);
				finAccountMap.put("postToGlAccountId", "213200");
				finAccountMap.put("isRefundable", "Y");

				//保存finaccount数据
				GenericValue finAccount = delegator.makeValue("FinAccount", finAccountMap);
				finAccount.create();

				//保存finaccountRole数据
				GenericValue finAccountRole = delegator.makeValue("FinAccountRole", UtilMisc.toMap( "finAccountId", finAccountId, "partyId", organizationPartyId, "roleTypeId", "DISTRIBUTOR","fromDate", UtilDateTime.nowTimestamp()));
				finAccountRole.create();

			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardGenCardNumberError", locale));
			}
		}

		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		retMap.put("result", "SUCCESS");
		return retMap;
	}


	/**
	 * 执行商家结算
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> doSettlement(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		String partyId = (String) context.get("partyId");

		// 商家的 结算账户
		GenericValue partySettlementsAccount = CloudCardHelper.getSettlementAccount(delegator, partyId);
		if(null== partySettlementsAccount){
			Debug.logError("商家["+partyId+"]用于平台对账结算的账户没有正确配置", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", partyId), locale));
		}
		String  partyAccountId = partySettlementsAccount.getString("finAccountId");

		// 商家的跨店交易流水查询
		EntityCondition sentCond = EntityCondition.makeCondition("statusId", "PMNT_SENT");
		EntityCondition pNotSentButRcv = EntityCondition.makeCondition("statusId", "PMNT_P_NOT_SENT_RCV"); // 平台已收未付
		EntityCondition pNotRcvButSent = EntityCondition.makeCondition("statusId", "PMNT_P_NOT_RCV_SENT"); // 平台未收已付
		EntityCondition receivableCond =  EntityCondition.makeCondition("distributorPartyId", partyId);
		EntityCondition payableCond =  EntityCondition.makeCondition("tradePartyId", partyId);

		// 平台应从商家收款条件
		EntityCondition arCond = EntityCondition.makeCondition(receivableCond, EntityCondition.makeCondition(EntityJoinOperator.OR, sentCond, pNotRcvButSent));
		// 平台应付款给商家条件
		EntityCondition apCond = EntityCondition.makeCondition(payableCond, EntityCondition.makeCondition(EntityJoinOperator.OR, sentCond, pNotSentButRcv));

		// 平台应收商家收款流水
		List<GenericValue> arList = null;
		// 平台应付款给商家流水
		List<GenericValue> apList = null;
		try {
			arList = delegator.findList("CloudCardCrossStorePaymentView", arCond, null, null, null, false);
			apList = delegator.findList("CloudCardCrossStorePaymentView", apCond, null, null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		// 统计结算金额
		BigDecimal arAmount = CloudCardHelper.ZERO; // 平台应收金额
		BigDecimal apAmount = CloudCardHelper.ZERO; // 平台应付金额
		if(UtilValidate.isNotEmpty(arList)){
			for(GenericValue p: arList){
				arAmount = arAmount.add(p.getBigDecimal("amount"));
			}
		}
		if(UtilValidate.isNotEmpty(apList)){
			for(GenericValue p: apList){
				apAmount = apAmount.add(p.getBigDecimal("amount"));
			}
		}

		BigDecimal reconciliationAmount = arAmount.subtract(apAmount).setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);
		BigDecimal partySettlementsBalance = partySettlementsAccount.getBigDecimal("actualBalance");
		if(null != partySettlementsBalance){
			partySettlementsBalance = partySettlementsBalance.setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);
		}else{
			partySettlementsBalance = CloudCardHelper.ZERO;
		}

		if(!reconciliationAmount.equals(partySettlementsBalance)){
			// something worng？
			Debug.logWarning("reconciliationAmount:" + reconciliationAmount.toPlainString() + ",,partySettlementsBalance:" + partySettlementsBalance.toPlainString(), module);
		}


		// 进行结算，更新payment状态
		List<String> arPaymentIdList = EntityUtil.getFieldListFromEntityList(arList, "paymentId", false);
		List<String> apPaymentIdList = EntityUtil.getFieldListFromEntityList(apList, "paymentId", false);
		List<String> allPaymentIdList = FastList.newInstance();
		allPaymentIdList.addAll(arPaymentIdList);
		allPaymentIdList.addAll(apPaymentIdList);

		try {
			//  状态为：PMNT_P_NOT_SENT_RCV 或 PMNT_P_NOT_RCV_SENT 的ar ap payment都更新为 PMNT_CONFIRMED
			delegator.storeByCondition("Payment", UtilMisc.toMap("statusId", "PMNT_CONFIRMED"),
					EntityCondition.makeCondition(

								EntityCondition.makeCondition(EntityJoinOperator.OR, pNotSentButRcv, pNotRcvButSent),
								// paymentId条件
								EntityCondition.makeCondition("paymentId", EntityJoinOperator.IN, allPaymentIdList)
							)
				);

			// 状态为 PMNT_SENT 的应收payment 改为 PMNT_P_NOT_SENT_RCV
			delegator.storeByCondition("Payment", UtilMisc.toMap("statusId", "PMNT_P_NOT_SENT_RCV"),
					EntityCondition.makeCondition(
								// 状态条件：PMNT_SENT 更新为 PMNT_P_NOT_SENT_RCV
								sentCond,
								EntityCondition.makeCondition("paymentId", EntityJoinOperator.IN, arPaymentIdList)
							)
				);

			// 状态为 PMNT_SENT 的应付payment 改为 PMNT_P_NOT_RCV_SENT
			delegator.storeByCondition("Payment", UtilMisc.toMap("statusId", "PMNT_P_NOT_RCV_SENT"),
					EntityCondition.makeCondition(
								// 状态条件：PMNT_SENT 更新为 PMNT_P_NOT_SENT_RCV
								sentCond,
								EntityCondition.makeCondition("paymentId", EntityJoinOperator.IN, apPaymentIdList)
							)
				);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		// 调整商家结算账号余额
		// 结算历史记录查询这个finAccountTrans
		Map<String, Object> finAccountWithdrawOutMap=null;
		try {
			finAccountWithdrawOutMap = dispatcher.runSync("createFinAccountTrans",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"finAccountId", partyAccountId,
							"partyId", partyId,
							"amount", reconciliationAmount.negate(),
							"finAccountTransTypeId","ADJUSTMENT",
							//"reasonEnumId", "FATR_PURCHASE",//TODO
							"glAccountId", "210000",
							"comments", "对账结算",
							"statusId", "FINACT_TRNS_APPROVED"));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
			return finAccountWithdrawOutMap;
		}

		GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, partyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
        	Debug.logError("商家[" + partyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", partyId), locale));
        }

		String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");

		// 卖卡限额回冲
		Map<String, Object> createFinAccountAuthOutMap;
		try {
			createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
							"finAccountId", creditLimitAccountId,
							"amount", arAmount.negate(),
							"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
			return createFinAccountAuthOutMap;
		}
		delegator.clearCacheLine(creditLimitAccount);

		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		retMap.put("partyId", partyId);
		retMap.put("arAmount", arAmount);
		retMap.put("apAmount", apAmount);
		retMap.put("settlementAmount", reconciliationAmount);
		return retMap;
	}


	/**
	 * 注册设备ID
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> regJpushRegId(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String regId = (String) context.get("regId");
		String deviceType = (String) context.get("deviceType");
		String appType = (String) context.get("appType");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = userLogin.getString("partyId");

		String partyIdentificationTypeId = null;
		if( "android".equalsIgnoreCase(deviceType)){
			partyIdentificationTypeId = JPushServices.ANDROID_APPTYPE_PIFT_MAP.get(appType);
		}else if("ios".equalsIgnoreCase(deviceType)){
			partyIdentificationTypeId = JPushServices.IOS_APPTYPE_PIFT_MAP.get(appType);
		}

		if(null == partyIdentificationTypeId){
			Debug.logError("appType invalid", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardDeviceTypeOrAppTypeInvalid", locale));
		}

		//查询该用户是否存在regId
		GenericValue partyIdentification = null;
		//原app regId
		String oldRegId = "";
		String time = "";
		Map<String, Object> lookupFields = FastMap.newInstance();
		lookupFields.put("partyId", partyId);
		lookupFields.put("partyIdentificationTypeId", partyIdentificationTypeId);
		try {
			partyIdentification = delegator.findByPrimaryKey("PartyIdentification", lookupFields);
			//判断该用户是否存在regId,如果不存在，插入一条新数据，否则修改该partyId的regId
			if(UtilValidate.isEmpty(partyIdentification)){
				lookupFields.put("idValue", regId);
				delegator.makeValue("PartyIdentification", lookupFields).create();
			}else{
				oldRegId = partyIdentification.getString("idValue");
				Timestamp timestamp = UtilDateTime.nowTimestamp();
				int hours = timestamp.getHours();
				int minutes = timestamp.getMinutes();
				time = hours +":"+ minutes;
				partyIdentification.set("idValue", regId);
				partyIdentification.store();
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		retMap.put("appType", appType);
		retMap.put("regId", oldRegId);
		retMap.put("time", time );
		return retMap;
	}

	/**
	 * 退出登录删除设备ID
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> removeJpushRegId(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String regId = (String) context.get("regId");

		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = userLogin.getString("partyId");
		Map<String, Object> partyIdentificationMap = FastMap.newInstance();
		partyIdentificationMap.put("partyId", partyId);
		partyIdentificationMap.put("idValue", regId);

		try {
			delegator.removeByAnd("PartyIdentification", partyIdentificationMap);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		return retMap;
	}

	/**
	 * 卡转让接口- 入参验证
	 *  1、用户自己的、非他人授权给我的，并且当前也没有处于授权给他人使用的状态的卡才能转让
	 *  2、根据传入手机号码查询customerPartyId，没有则创建一个person
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> modifyCardOwnerInputValidate(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		String teleNumber = (String) context.get("teleNumber");
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");

		// 不是自己的卡不能操作
		String cardOwner = cloudCard.getString("ownerPartyId");
		String userPartyId = userLogin.getString("partyId");
		String cardCode = cloudCard.getString("cardNumber");

		if(cardOwner==null ||  !cardOwner.equals(userPartyId)){
			Debug.logWarning(
					"用户partyId: [" + userPartyId + "] 试图转让非本人所有的卡:cardCode [" + cardCode + "] 给 用户teleNumber [" + teleNumber + "]",
					module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotYourCard", locale));
		}

		// 检查是不是已经授权的卡
		// 卡号的前缀是带有 auth: 字样，说明是别人授权给我的卡，不能转让
		if(UtilValidate.isNotEmpty(cardCode)&& cardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX)){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenAuthorized", locale));
		}
		// 存在SHAREHOLDER 角色的，未过期的 finAccountRole，表示此卡已授权给他人，不能转让
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
		if(isAuthorized){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenAuthorizedToOthers", locale));
		}

		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行转让", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotBeTransferred ", locale));
		}

		//判断用户是否存在， 不存在则创建用户
		context.put("organizationPartyId", cloudCard.getString("distributorPartyId"));
		context.put("ensureCustomerRelationship", true);
		Map<String, Object> getOrCreateCustomerOut = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");

		if(customerPartyId.equals(userLogin.getString("partyId"))){
			Debug.logWarning("用户partyId: [" + customerPartyId + "] 试图转让卡:cardNumber [" + cardCode + "] 给自己", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotBeTransferredToYourself", locale));
		}

		Map<String, Object> ret = ServiceUtil.returnSuccess();
		ret.put("cloudCard", cloudCard);
		ret.put("customerPartyId", customerPartyId);
		ret.put("teleNumber", teleNumber);
		if(Debug.infoOn()){
			Debug.logInfo("modifyCardOwnerInputValidate return Success:" + ret, module);
		}
		return ret;
	}

	/**
	 * 卡转让接口
	 * <p>1、为原卡主创建一个FinAccountRole记录</p>
	 * <p>2、修改FinAccount的ownerPartyId字段为新partyId, 变更finAccountCode字段，将关联的giftCard设置为过期，并新增一个giftCard，parthId为新的partyId</p>
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> modifyCardOwner(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

		GenericValue cloudCard = (GenericValue) context.get("cloudCard");
		String customerPartyId = (String) context.get("customerPartyId");

		GenericValue systemUser = (GenericValue) context.get("systemUser");
		if(null == systemUser){
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
				context.put("systemUser", systemUser);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
		}

		//判断用户是否在本店有没有卡
		try {
        	EntityCondition thruDateEntityCondition = EntityCondition.makeCondition(EntityOperator.OR,EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp()),EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null));
        	EntityCondition cloudCardInfoEntityCondition = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition(UtilMisc.toMap("partyId", customerPartyId, "distributorPartyId", cloudCard.getString("distributorPartyId"))),thruDateEntityCondition);
			List<GenericValue> cloudCardInfoList = delegator.findList("CloudCardInfo", cloudCardInfoEntityCondition, null, null, null, false);

			//如果该用户存在本店授权卡，也可以继续接收本店的卡
			if(UtilValidate.isNotEmpty(cloudCardInfoList) || cloudCardInfoList.size() > 0){
				String oldCardCode = null;
				for (GenericValue cloudCardInfo : cloudCardInfoList){
					oldCardCode = cloudCardInfo.getString("cardNumber");
					if(UtilValidate.isNotEmpty(oldCardCode) && !oldCardCode.startsWith(CloudCardConstant.AUTH_CARD_CODE_PREFIX)){
						return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUsersHaveCardsInOurStore", locale));
					}
				}
			}
		} catch (GenericEntityException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//1、为原卡主创建一个FinAccountRole记录
		String oldOwner = cloudCard.getString("ownerPartyId");
		String finAccountId = cloudCard.getString("finAccountId");
		BigDecimal cardBalance = cloudCard.getBigDecimal("actualBalance"); // 这里一定是没有处于授权状态的卡

		Map<String, Object> createFinAccountRoleOutMap;
		try {
			createFinAccountRoleOutMap = dispatcher.runSync("createFinAccountRole", UtilMisc.toMap("userLogin", systemUser, "locale", locale,
					"finAccountId", finAccountId, "partyId", oldOwner, "roleTypeId", "OLD_CARD_OWNER"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if (ServiceUtil.isError(createFinAccountRoleOutMap)) {
			return createFinAccountRoleOutMap;
		}

		String cardId = cloudCard.getString("paymentMethodId");

		// 2、修改FinAccount的ownerPartyId字段为新partyId, 变更finAccountCode字段，将关联的giftCard设置为过期，并新增一个giftCard，partyId为新的partyId

		//创建giftCard
		String finAccountName = cloudCard.getString("finAccountName");
		String newCardCode;
		try {
			// 卡二维码需要变更下，否则转让卡后，原卡主还能用旧的二维码去消费
			newCardCode = CloudCardHelper.generateCloudCardCode(delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("finAccountId", finAccountId);
		giftCardInMap.put("cardNumber", newCardCode);
		giftCardInMap.put("description", finAccountName);
		giftCardInMap.put("customerPartyId", customerPartyId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}

		String newCardId = (String) giftCardOutMap.get("paymentMethodId");

		try{// 更新 二维码 和 卡主信息
			GenericValue finAccount = delegator.findByPrimaryKey("FinAccount", UtilMisc.toMap("finAccountId", finAccountId));
			finAccount.setString("ownerPartyId", customerPartyId);
			finAccount.setString("finAccountCode", newCardCode);
			finAccount.store();
			delegator.storeByCondition("PaymentMethod", UtilMisc.toMap("thruDate", UtilDateTime.nowTimestamp()), EntityCondition.makeCondition("paymentMethodId", cardId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//获取原卡主人电话号码
		String teleNumber = null;
		List<GenericValue> partyAndTelecomNumbers;
		try {
			EntityCondition partyIdCond = EntityCondition.makeCondition("partyId", oldOwner);
			EntityCondition leadAssignedCond = EntityCondition.makeCondition("statusId", "LEAD_ASSIGNED");
			EntityCondition partyEnabledCond = EntityCondition.makeCondition("statusId", "PARTY_ENABLED");
			EntityCondition statusIdCond = EntityCondition.makeCondition(leadAssignedCond, EntityOperator.OR, partyEnabledCond);
			EntityCondition telNumCond = EntityCondition.makeCondition(partyIdCond, EntityOperator.AND, statusIdCond);
			partyAndTelecomNumbers = delegator.findList("PartyAndTelecomNumber", telNumCond, null, null, null, true);
			if(UtilValidate.isNotEmpty(partyAndTelecomNumbers)){
	    		GenericValue partyAndTelecomNumber = partyAndTelecomNumbers.get(0);
	    		teleNumber = partyAndTelecomNumber.getString("contactNumber");
	    	}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//转卡短信通知
		context.put("smsType", CloudCardConstant.USER_MODIFY_CARD_OWNER_TYPE);
	    context.put("phone", context.get("teleNumber"));
	    context.put("teleNumber", teleNumber);
		context.put("storeName", cloudCard.getString("distributorPartyName"));
		context.put("cardBalance", cardBalance);
		SmsServices.sendMessage(dctx, context);

		// 服务返回成功
		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		retMap.put("newCardId", newCardId);
		retMap.put("customerPartyId", customerPartyId);
		retMap.put("cardBalance", cardBalance);
		return retMap;
	}

	/**
     * 获取省市级数据
     *
     * @param
     *
     * @return
     */
    public static Map<String, Object> getProvinceOrCityOrArea(DispatchContext dctx, Map<String, Object> context) {
		Map<String, Object> result = ServiceUtil.returnSuccess();
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String geoAssocTypeId = (String) context.get("geoAssocTypeId");
		String cityId = (String) context.get("cityId");
		String provinceId = (String) context.get("provinceId");
		try {
			if (geoAssocTypeId.equals("REGIONS")) {
				List<Map<String, Object>> provinceList = FastList.newInstance();
				List<GenericValue> provinceGvList = delegator.findByAnd("GeoAssocAndGeoTo",
						UtilMisc.toMap("geoIdFrom", "CHN", "geoAssocTypeId", geoAssocTypeId));
				for (GenericValue provinceGv : provinceGvList) {
					Map<String,Object> provinceMap = FastMap.newInstance();
					provinceMap.put("provinceGeoId", provinceGv.getString("geoId"));
					provinceMap.put("provinceName", provinceGv.getString("geoName"));
					provinceMap.put("geoType", provinceGv.getString("PROVINCE_CITY"));
					provinceList.add(provinceMap);
				}
				result.put("provinceList", provinceList);// 省列表
			}

			if (geoAssocTypeId.equals("PROVINCE_CITY")) {
				List<Map<String, Object>> cityList = FastList.newInstance();
				List<GenericValue> cityGvList = delegator.findByAnd("GeoAssocAndGeoTo",
						UtilMisc.toMap("geoIdFrom", provinceId, "geoAssocTypeId", geoAssocTypeId));
				for (GenericValue cityGv : cityGvList) {
					Map<String, Object> cityMap = FastMap.newInstance();
					cityMap.put("cityGeoId", cityGv.getString("geoId"));
					cityMap.put("cityName", cityGv.getString("geoName"));
					cityMap.put("geoType", "CITY_COUNTY");
					cityList.add(cityMap);
				}
				result.put("cityList", cityList);// 市列表
			}
			if (geoAssocTypeId.equals("CITY_COUNTY")) {
				List<Map<String, Object>> countyList = FastList.newInstance();
				List<GenericValue> countyGvList = delegator.findByAnd("GeoAssocAndGeoTo",
						UtilMisc.toMap("geoIdFrom", cityId, "geoAssocTypeId", geoAssocTypeId));
				for (GenericValue countyGv : countyGvList) {
					Map<String, Object> countyMap = FastMap.newInstance();
					countyMap.put("countyGeoId", countyGv.getString("geoId"));
					countyMap.put("countyName", countyGv.getString("geoName"));
					countyMap.put("geoType", "COUNTY");
					countyList.add(countyMap);
				}
				result.put("countyList", countyList);// 县列表
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		return result;
	}

    /**
     * 结算请求定时任务
     *
     * @param
     *
     * @return
     */
    public static Map<String, Object> bizSettlementReqJob(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
    	Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

    	long week = -1;
    	long hour = -1;
    	com.ibm.icu.util.Calendar calendar = UtilDateTime.toCalendar(UtilDateTime.nowTimestamp());
    	week = calendar.get(Calendar.DAY_OF_WEEK)-1;
    	hour = calendar.get(Calendar.HOUR_OF_DAY);

    	if(-1 == week && -1 == hour){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
    	}

    	//查询该时间段需要结算的店家
    	List<GenericValue> cloudcardSettlementPeriodList = FastList.newInstance();
    	try {
    		cloudcardSettlementPeriodList = delegator.findByAnd("CloudcardSettlementPeriod", UtilMisc.toMap("week", week, "hour", hour));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

    	//查询该时间段需要结算的店家
    	for(GenericValue cloudcardSettlementPeriod : cloudcardSettlementPeriodList){
    		//系统用户
        	GenericValue systemUser;
    		try {
    			systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
    		} catch (GenericEntityException e1) {
    			Debug.logError(e1, module);
    			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
    		}
    		String partyIdFrom = cloudcardSettlementPeriod.getString("partyIdFrom");
    		String partyIdTo = cloudcardSettlementPeriod.getString("partyIdTo");


    		List<GenericValue> ettlementList = null;
    		try {
    			EntityCondition lookupConditions = EntityCondition.makeCondition(UtilMisc.toMap("tradePartyId", partyIdFrom,"cardSellerId",partyIdTo));
    			ettlementList = delegator.findList("CloudCardNeedSettlementPaymentView", lookupConditions, null, null, null, false);

			} catch (GenericEntityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

    		for(GenericValue bizListNeedSettlement : ettlementList){
    			String paymentId = bizListNeedSettlement.getString("paymentId");
            	String payerPartyId = bizListNeedSettlement.getString("tradePartyId");
            	String payeePartyId = bizListNeedSettlement.getString("cardSellerId");
            	String amount = bizListNeedSettlement.getString("amount");

    			//发送结算请求服务
            	try {
        			dispatcher.runSync("initiateSettlement", UtilMisc.toMap("userLogin",systemUser,"paymentId", paymentId, "payerPartyId", payerPartyId, "payeePartyId", payeePartyId, "amount", amount));
        		} catch (GenericServiceException e) {
        			Debug.logError(e.getMessage(), module);
        			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        		}
    		}

    		try {
    			EntityCondition lookupConditions = EntityCondition.makeCondition(UtilMisc.toMap("tradePartyId", partyIdTo,"cardSellerId", partyIdFrom));
    			ettlementList = delegator.findList("CloudCardNeedSettlementPaymentView", lookupConditions, null, null, null, false);
			} catch (GenericEntityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

    		for(GenericValue bizListNeedSettlement : ettlementList){
    			String paymentId = bizListNeedSettlement.getString("paymentId");
            	String payerPartyId = bizListNeedSettlement.getString("tradePartyId");
            	String payeePartyId = bizListNeedSettlement.getString("cardSellerId");
            	String amount = bizListNeedSettlement.getString("amount");

    			//发送结算请求服务
            	try {
        			dispatcher.runSync("initiateSettlement", UtilMisc.toMap("userLogin",systemUser,"paymentId", paymentId, "payerPartyId", payerPartyId, "payeePartyId", payeePartyId, "amount", amount));
        		} catch (GenericServiceException e) {
        			Debug.logError(e.getMessage(), module);
        			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        		}
    		}

    	}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
    }

    /**
     * 退出登录
     *
     * @param
     *
     * @return
     */
    public static Map<String, Object> userLogout(DispatchContext dctx, Map<String, Object> context) {
    	Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = userLogin.getString("partyId");

		try {
			GenericValue partyIdentification = delegator.findByPrimaryKey("PartyIdentification", UtilMisc.toMap("partyId", partyId));
			if(UtilValidate.isNotEmpty(partyIdentification)){
				delegator.removeValue(partyIdentification);
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

    	Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
    }

}
