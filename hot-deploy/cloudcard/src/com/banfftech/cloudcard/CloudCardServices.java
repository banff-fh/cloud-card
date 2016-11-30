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
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityJoinOperator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * @author subenkun
 *
 */
public class CloudCardServices {
	
	public static final String module = CloudCardServices.class.getName();
	
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY";
	
	public static final String resourceError = "cloudcardErrorUiLabels";
	public static final String resourceAccountingError = "AccountingErrorUiLabels";
	
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
		BigDecimal balance = cloudCard.getBigDecimal("availableBalance");// actualBalance
		if(null == amount){
			amount = balance;
		}
		if(balance.compareTo(amount)<0 || balance.compareTo(CloudCardHelper.ZERO)<=0){
			Debug.logError("This card balance is Not Enough, can NOT authorize", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBalanceIsNotEnough", 
					UtilMisc.toMap("balance", balance.toPlainString()), locale));
		}
		
		// 检查是不是已经授权的卡
		// 卡号的前缀是带有 auth: 字样，说明是别人授权给我的卡，不能再次授权
		String cardCode = cloudCard.getString("cardNumber");
		if(UtilValidate.isNotEmpty(cardCode)&& cardCode.startsWith(CloudCardHelper.AUTH_CARD_CODE_PREFIX)){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenAuthorized", locale));
		}
		// 存在SHAREHOLDER 角色的，未过期的 finAccountRole，表示此卡已授权给他人，不能再次授权
		Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
		boolean isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
		if(isAuthorized){
			Debug.logError("This card has been authorized", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenAuthorized", locale));
		}

		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行授权", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCanNotBeAuthorized", locale)); 
		}

		// 用户自己的userLogin没有 诸如 创建partyContact之类的权限，需要用到system权限
		GenericValue systemUser;
		try {
			systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
		} catch (GenericEntityException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCanNotBeAuthorizedToYourself", locale));
		}
		
		//创建giftCard
		String finAccountName = cloudCard.getString("finAccountName");
		Random rand = new Random();
		String newCardCode = new StringBuilder(CloudCardHelper.AUTH_CARD_CODE_PREFIX)
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createCloudCardOutMap)) {
			return createCloudCardOutMap;
		}
		
		// 授权金额的处理
		Map<String, Object> createFinAccountAuthOutMap;
		try {
			createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
							"currencyUomId", DEFAULT_CURRENCY_UOM_ID,  
							"finAccountId", finAccountId,
							"amount", amount,
							"fromDate", fromDate,
							"thruDate", thruDate)
					);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
			return createFinAccountAuthOutMap;
		}
		
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
		// 删除以前的finAccountRole，而不是更新thruDate使之失效 因为 finAccountRole中 fromDate是主键，如果回收授权后再次授权，fromDate一样的情况下会逐渐冲突
		Map<String, Object> updateFinAccountRoleOut;
		try {
			updateFinAccountRoleOut = dispatcher.runSync("deleteFinAccountRole", UtilMisc.toMap("userLogin", userLogin, "finAccountId", finAccountId, 
					"fromDate", authFromDate, "partyId", toPartyId, "roleTypeId", roleTypeId));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if (ServiceUtil.isError(updateFinAccountRoleOut)) {
			return updateFinAccountRoleOut;
		}
		
		
		// 2、使用 finAccountId 和 toPartyId 查找所有未过期PaymentMethod，置为过期
		EntityCondition  filterByDateCond =  EntityUtil.getFilterByDateExpr();
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
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
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNeedTelenumber", locale)); 
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
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
				if (ServiceUtil.isError(createCloudCardOutMap)) {
					return createCloudCardOutMap;
				}
				
				customerPartyId = (String) createCloudCardOutMap.get("customerPartyId");
				cardId = (String)createCloudCardOutMap.get("paymentMethodId");
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(rechargeCloudCardOutMap)) {
			return rechargeCloudCardOutMap;
		}
		
		
		//3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("amount", amount);
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCanNotBeActivated", locale)); 
		}
		// 不是本商家的卡
		if(!organizationPartyId.equals(cloudCard.getString("distributorPartyId"))){
			Debug.logInfo("此卡[" + cardCode + "]不是商家[" +organizationPartyId + "]发行的卡，不能进行激活", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotOurCardCanNotBeActivated", locale)); 
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
		result.put("paymentMethodId", giftCardOutMap.get("paymentMethodId"));
		return result;
	}

	
	/**
	 *	充值服务
	 *   1、扣商户开卡余额
	 *   2、商户账户收款
	 *   3、商户账户将充值款项转入用户账户
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> rechargeCloudCard(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardId = (String) context.get("cardId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		Timestamp nowTimestamp = UtilDateTime.nowTimestamp();

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
//		GenericValue partyGroup = (GenericValue) checkParamOut.get("partyGroup");
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		String finAccountId = cloudCard.getString("finAccountId");
		
		
		// 不是本商家的卡
		if(!organizationPartyId.equals(cloudCard.getString("distributorPartyId"))){
			Debug.logInfo("此卡[" + cloudCard.getString("cardNumber") + "]不是商家[" +organizationPartyId + "]发行的卡，不能进行充值", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotOurCardCanNotRecharge", locale)); 
		}
		
		// 获取商户用于管理卖卡限额的金融账户
		GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
            Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }
        
        // 获取商户用于收款的金融账户
        GenericValue receiptAccount = CloudCardHelper.getReceiptAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(receiptAccount)) {
            Debug.logError("商家[" + organizationPartyId + "]未配置收款账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCreditIsNotEnough", UtilMisc.toMap("balance", creditLimit.toPlainString()), locale));
		}

		// 商户扣减开卡余额
		Map<String, Object> createFinAccountAuthOutMap;
		try {
			createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
							"currencyUomId", DEFAULT_CURRENCY_UOM_ID,  
							"finAccountId", creditLimitAccountId,
							"amount", amount,
							//FIXME 奇怪的BUG，如果fromDate直接用当前时间戳，
							// 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
							// 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
							// 所以，这里人为地把fromDate时间提前2秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
							"fromDate", UtilDateTime.adjustTimestamp(nowTimestamp, Calendar.SECOND, -2)));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
			return createFinAccountAuthOutMap;
		}
		delegator.clearCacheLine(creditLimitAccount);
		
		// 2、商户收款 用户--》商家
		String receiptAccountId = receiptAccount.getString("finAccountId");
		String customerPartyId = cloudCard.getString("ownerPartyId");
		Map<String, Object> receiptAccountDepositOutMap;
		try {
			receiptAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, "statusId", "PMNT_RECEIVED", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT", 
							"paymentTypeId","CUSTOMER_DEPOSIT",
							"paymentMethodTypeId", "CASH", "finAccountId", receiptAccountId,
							"partyIdFrom", customerPartyId, "partyIdTo", organizationPartyId
							, "amount", amount, "comments", "充值，商家收款",
							"reasonEnumId", "FATR_REPLENISH"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(receiptAccountDepositOutMap)) {
			return receiptAccountDepositOutMap;
		}
		String receiptPaymentId = (String) receiptAccountDepositOutMap.get("paymentId");
		
		// 3、用户账户存入
		// 商家收款账户---》用户账户
		Map<String, Object> finAccountWithdrawOutMap;
		try {
			finAccountWithdrawOutMap = dispatcher.runSync("createFinAccountTrans",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
			return finAccountWithdrawOutMap;
		}
		
		Map<String, Object> finAccountDepositOutMap;
		try {
			finAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, "statusId", "PMNT_RECEIVED", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT", 
							"paymentTypeId","GC_DEPOSIT",
							"finAccountId", finAccountId,
							"paymentMethodId", cardId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
							customerPartyId, "amount", amount, "comments", "充值，存入用户账户",
							"reasonEnumId", "FATR_REPLENISH"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountDepositOutMap)) {
			return finAccountDepositOutMap;
		}
		
		String depositPaymentId = (String) finAccountDepositOutMap.get("paymentId");
		
		
		//应用用户支付给商家，和商家存入用户账户 的两个payment
		Map<String, Object> paymentApplicationOutMap;
		try {
			paymentApplicationOutMap = dispatcher.runSync("createPaymentApplication",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"amountApplied", amount,
							"paymentId", receiptPaymentId,
							"toPaymentId", depositPaymentId));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(paymentApplicationOutMap)) {
			return paymentApplicationOutMap;
		}
		
		// 修改这两个payment的状态为PMNT_CONFIRMED
		try {
			Map<String, Object> setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus", UtilMisc.toMap("userLogin", userLogin,
					"locale", locale, "paymentId", depositPaymentId, "statusId", "PMNT_CONFIRMED"));
			
			if (ServiceUtil.isError(setPaymentStatusOutMap)) {
				return setPaymentStatusOutMap;
			}

			setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus", UtilMisc.toMap("userLogin", userLogin,
					"locale", locale, "paymentId", receiptPaymentId, "statusId", "PMNT_CONFIRMED"));
			
			if (ServiceUtil.isError(setPaymentStatusOutMap)) {
				return setPaymentStatusOutMap;
			}
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
	 *	云卡支付服务
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
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String organizationPartyId = (String) context.get("organizationPartyId");
		BigDecimal amount = (BigDecimal) context.get("amount");
		String cardCode = (String) context.get("cardCode");

		// 数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行扫码消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
		}
		
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		
		// 检查商家收款帐号
		GenericValue receiptAccount = CloudCardHelper.getReceiptAccount(delegator, organizationPartyId);
		if(null==receiptAccount){
			Debug.logError("商家["+organizationPartyId+"]用于收款的账户没有正确配置", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, 
					"CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
		}
		String receiptAccountId = receiptAccount.getString("finAccountId");
		
		
		// 根据二维码获取用户用于支付的帐号
		GenericValue cloudCard = (GenericValue) checkParamOut.get("cloudCard");
		// 没有激活的账户，不能用于付款
		if(!"FNACT_ACTIVE".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行付款", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenDisabled", locale)); 
		}
		// 是别人授权给我的卡
		boolean isAuth2me =  cardCode.startsWith(CloudCardHelper.AUTH_CARD_CODE_PREFIX);
		boolean isAuthorizedToOthers = false;
		if(!isAuth2me){
			Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
			isAuthorizedToOthers = (boolean) cardAuthorizeInfo.get("isAuthorized");
			// 此卡已经授权给别人
//			if(isAuthorizedToOthers){
//				Debug.logError("此卡["+cardCode+"]已经授权给[" + cardAuthorizeInfo.get("toPartyId") + "],自己不能再使用", module);
//				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenAuthorizedToOthers", locale));
//			}
		}
		
		
		String finAccountId = cloudCard.getString("finAccountId");
		String customerPartyId = cloudCard.getString("partyId");// ownerPartyId 是原卡主， partyId 才是持卡人
		String paymentMethodId = cloudCard.getString("paymentMethodId");
		String distributorPartyId = cloudCard.getString("distributorPartyId");
		boolean isSameStore = false; //是否 店内消费（本店卖出去的卡在本店消费）
		if(distributorPartyId.equals(organizationPartyId)){
			 isSameStore = true;
		}

		// 1、扣除用户余额
		// 检查余额是否够用, 如果卡已经授权给别人，而自己支付的时候，以availableBalance为准，避免把授权出去那部分金额给用掉了
		BigDecimal actualBalance = isAuthorizedToOthers ? cloudCard.getBigDecimal("availableBalance"): cloudCard.getBigDecimal("actualBalance") ; //actualBalance
		if(null==actualBalance){
			actualBalance = BigDecimal.ZERO;
		}
		if (actualBalance.compareTo(amount) < 0) {
			Debug.logError("余额不足, 余额：" + actualBalance.toPlainString(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBalanceIsNotEnough", 
					UtilMisc.toMap("balance", actualBalance.toPlainString()), locale));
		}
		
		// 如果是授权给我的卡，还要检查授权金额余额是否足够, 并且扣减本次消费的余额
		if(isAuth2me){
			BigDecimal authAmount = CloudCardHelper.getCloudCardAuthBalance(finAccountId, delegator);
			if (authAmount.compareTo(amount) < 0) {
				Debug.logError("授权可用余额不足, 余额：" + authAmount.toPlainString(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBalanceIsNotEnough", 
						UtilMisc.toMap("balance", authAmount.toPlainString()), locale));
			}
			// 用被人授权的卡支付，授权剩余额度需要扣减，扣减方法：为此账户创建负数金额的finAccountAuth
			Map<String, Object> createFinAccountAuthOutMap;
			try {
				createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
						UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
								"currencyUomId", DEFAULT_CURRENCY_UOM_ID,  
								"finAccountId", finAccountId,
								"amount", amount.negate(),
								"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2),
								"thruDate", cloudCard.getTimestamp("thruDate")));
			} catch (GenericServiceException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
				return createFinAccountAuthOutMap;
			}
		}
		
		// 扣款
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"statusId", "PMNT_SENT", 
							"currencyUomId",DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "WITHDRAWAL", 
							"paymentTypeId","GC_WITHDRAWAL",
							"finAccountId", finAccountId,
							"paymentMethodId",paymentMethodId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", customerPartyId, "partyIdTo",
							organizationPartyId, "amount", amount, "comments", "顾客消费",
							"reasonEnumId", "FATR_PURCHASE"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"statusId", "INVOICE_IN_PROCESS", 
							"invoiceTypeId", "SALES_INVOICE",
							"currencyUomId", DEFAULT_CURRENCY_UOM_ID, 
							"description", description,
							"partyId", customerPartyId,
							"partyIdFrom", organizationPartyId));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceOutMap)) {
			return invoiceOutMap;
		}
		String invoiceId = (String) invoiceOutMap.get("invoiceId");
		
		Map<String, Object> invoiceItemOutMap;
		try {
			invoiceItemOutMap = dispatcher.runSync("createInvoiceItem",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"invoiceId",invoiceId,
							"description", description,
							"invoiceItemTypeId", "INV_PROD_ITEM",
							"quantity", BigDecimal.ONE,
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceItemOutMap)) {
			return invoiceItemOutMap;
		}
		
		// 3、创建 PaymentApplication 应用 发票 与  付款payment，  并修改发票状态为INVOICE_APPROVED
		try {
			//TODO, NOTE 将商家开的invoice和客户付款的payment进行应用，
			// 虽然后面修改发票状态为INVOICE_APPROVED会触发ECA去自动去找payment与invoice进行应用
			// 但是，既然这里能拿到paymentId和invoiceId，就直接创建好PaymentApplication，
			// 免得 setInvoiceStatus的后续的ECA去搜索payment，
			// 既费时，也还可能出现找到的paymentId 不是前面刚生成的withdrawalPaymentId的情况，导致自动创建paymentApplication失败
			Map<String, Object> paymentApplicationOutMap = dispatcher.runSync("createPaymentApplication",
					UtilMisc.toMap("userLogin", userLogin, "locale", locale, "amountApplied", amount, "paymentId",
							withdrawalPaymentId, "invoiceId", invoiceId));
			if (ServiceUtil.isError(paymentApplicationOutMap)) {
				return paymentApplicationOutMap;
			}
			
			Map<String, Object> setInvoiceStatusOutMap = dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap(
					"userLogin", userLogin, "locale", locale, "invoiceId", invoiceId, "statusId", "INVOICE_APPROVED"));
			if (ServiceUtil.isError(setInvoiceStatusOutMap)) {
				return setInvoiceStatusOutMap;
			}
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		// 4、商家收款账户入账
		Map<String, Object> finAccountDepositOutMap;
		try {
			finAccountDepositOutMap = dispatcher.runSync("createFinAccountTrans",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountDepositOutMap)) {
			return finAccountDepositOutMap;
		}
		
		// 5、如果同店消费，直接回冲卖卡限额
		if(isSameStore){
			GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
	        if (UtilValidate.isEmpty(creditLimitAccount)) {
	        	Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
	        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
	        }

			String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");
			
			// 卖卡限额回冲
			Map<String, Object> createFinAccountAuthOutMap;
			try {
				createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
						UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
								"currencyUomId", DEFAULT_CURRENCY_UOM_ID,  
								"finAccountId", creditLimitAccountId,
								"amount", amount.negate(),
								//FIXME 奇怪的BUG，如果fromDate直接用当前时间戳，
								// 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
								// 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
								// 所以，这里人为地把fromDate时间提前2秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
								"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
			} catch (GenericServiceException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
				return createFinAccountAuthOutMap;
			}
			delegator.clearCacheLine(creditLimitAccount);
			
			// 设置Payment状态为PMNT_CONFIRMED
			Map<String, Object> setPaymentStatusOutMap;
			try {
				setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
						UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
								"paymentId", withdrawalPaymentId,  
								"statusId", "PMNT_CONFIRMED"
								));
			} catch (GenericServiceException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(setPaymentStatusOutMap)) {
				return setPaymentStatusOutMap;
			}
		}else{
			// 如果是跨店消费，
			// 在双方（发卡的店 和 交易发生店 ）的 对账账户里面记账（发卡方记正数 表示应付、交易放生方记负数，表示应收）
			
			// 发卡店的 结算账户 
			GenericValue distributorPartySettlementsAccount = CloudCardHelper.getSettlementAccount(delegator, distributorPartyId);
			if(null==distributorPartySettlementsAccount){
				Debug.logError("发卡商家["+distributorPartyId+"]用于平台对账结算的账户没有正确配置", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, 
						"CloudCardConfigError", UtilMisc.toMap("organizationPartyId", distributorPartyId), locale));
			}
			String  distributorAccountId = distributorPartySettlementsAccount.getString("finAccountId");
			
			// 本店（交易发生店）的 结算账户 
			GenericValue tradePartySettlementsAccount = CloudCardHelper.getSettlementAccount(delegator, organizationPartyId);
			if(null== tradePartySettlementsAccount){
				Debug.logError("本店["+organizationPartyId+"]用于平台对账结算的账户没有正确配置", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, 
						"CloudCardConfigError", UtilMisc.toMap("organizationPartyId", distributorPartyId), locale));
			}
			String  tradePartyAccountId = tradePartySettlementsAccount.getString("finAccountId");
			
			try {
				Map<String, Object> finAccountDepoistOutMap = dispatcher.runSync("createFinAccountTrans",
						UtilMisc.toMap("userLogin", userLogin, "locale",locale,
								"finAccountId", distributorAccountId,
								"partyId", customerPartyId,
								"amount",amount,
								"finAccountTransTypeId","DEPOSIT",
								"paymentId", withdrawalPaymentId,
								"reasonEnumId", "FATR_PURCHASE",
								"glAccountId", "210000",
								"comments", "应付",
								"statusId", "FINACT_TRNS_APPROVED"));
				if (ServiceUtil.isError(finAccountDepoistOutMap)) {
					return finAccountDepoistOutMap;
				}
				
				Map<String, Object> finAccountWithdrawOutMap = dispatcher.runSync("createFinAccountTrans",
						UtilMisc.toMap("userLogin", userLogin, "locale",locale,
								"finAccountId", tradePartyAccountId,
								"partyId", customerPartyId,
								"amount",amount,
								"finAccountTransTypeId","WITHDRAWAL",
								"paymentId", withdrawalPaymentId,
								"reasonEnumId", "FATR_PURCHASE",
								"glAccountId", "210000",
								"comments", "应收",
								"statusId", "FINACT_TRNS_APPROVED"));
				if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
					return finAccountWithdrawOutMap;
				}
			} catch (GenericServiceException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}

		}
		
		try {
			cloudCard.refresh();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("amount", amount);
		result.put("cardBalance", cloudCard.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("cardId", paymentMethodId);
		return result;
	}
	
	
	/**
	 * 充值、支付的公共参数检查
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
			Debug.logInfo("金额不合法，必须为正数", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceAccountingError, "AccountingFinAccountMustBePositive", locale));
		}
		
		// 起止时间的检查
		Timestamp fromDate =(Timestamp)context.get("fromDate");
		Timestamp thruDate =(Timestamp)context.get("thruDate");
		if(null!=fromDate && null!=thruDate && fromDate.after(thruDate)){
			Debug.logInfo("起止日期不合法，开始日期必须小于结束日期", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardFromDateMustBeforeThruDate", locale));
		}
		if(null!=thruDate && thruDate.before(UtilDateTime.nowTimestamp())){
			Debug.logInfo("结束日期必须大于当前日期", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardThruDateMustAfterNow", locale));
		}
		
		// organizationPartyId 必须在数据库中存在
		if(UtilValidate.isNotEmpty(organizationPartyId)){
			GenericValue partyGroup;
			try {
				partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if(null == partyGroup ){
				Debug.logInfo("商户："+organizationPartyId + "不存在", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, 
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
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (null == finAccount) {
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardAccountNotFound", UtilMisc.toMap("finAccountId", finAccountId), locale));
			}
			retMap.put("finAccount", finAccount);
		}
		
		// 卡二维码
		GenericValue cloudCard = null;
		if(UtilValidate.isNotEmpty(cardCode)){
			try {
				cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
			} catch (GenericEntityException e2) {
				Debug.logError(e2.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
			}
			if(null == cloudCard){
				Debug.logInfo("找不到云卡，cardCode[" + cardCode + "]", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotFound", locale)); 
			}
			
			if("FNACT_CANCELLED".equals(cloudCard.getString("statusId")) || "FNACT_MANFROZEN".equals(cloudCard.getString("statusId"))){
				Debug.logInfo("此卡[" + cloudCard.get("finAccountId") + "]状态不可用，当前状态[" + cloudCard.get("statusId") +"]", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenDisabled", locale)); 
			}
			retMap.put("cloudCard", cloudCard);
		}
		
		// 卡ID
		if(null == cloudCard && UtilValidate.isNotEmpty(cardId)){
			try {
				cloudCard = CloudCardHelper.getCloudCardAccountFromPaymentMethodId(cardId, delegator);
			} catch (GenericEntityException e2) {
				Debug.logError(e2.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
			}
			if(null == cloudCard){
				Debug.logInfo("找不到云卡，cardId[" + cardId + "]", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotFound", locale)); 
			}

			String statusId = cloudCard.getString("statusId");
			if("FNACT_CANCELLED".equals(statusId) || "FNACT_MANFROZEN".equals(statusId)){
				Debug.logInfo("此卡[finAccountId = " + cloudCard.get("finAccountId") + "]状态不可用，当前状态[" + statusId +"]", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenDisabled", locale)); 
			}
			retMap.put("cloudCard", cloudCard);
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
        GenericValue userLogin = (GenericValue) context.get("userLogin");
		String quantity = (String) context.get("quantity");
		String currencyUomId = (String)context.get("currencyUomId");
		String finAccountName = (String) context.get("finAccountName");
		String organizationPartyId = (String) context.get("organizationPartyId");
		
		
		//TODO， 这个可以优化，不应该也不必在每次生成卡号前来“确保/创建”商家的DISTRIBUTOR角色，
		// 应该在创建商家的时候就应该加上这个角色
		Map<String, Object> ensurePartyRoleOutMap;
		try {
			ensurePartyRoleOutMap = dispatcher.runSync("ensurePartyRole", 
					UtilMisc.toMap("userLogin", userLogin, "partyId", organizationPartyId, "roleTypeId", "DISTRIBUTOR"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		
		if (ServiceUtil.isError(ensurePartyRoleOutMap)) {
			return ensurePartyRoleOutMap;
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
				finAccountMap.put("organizationPartyId", "Company");
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
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGenCardNumberError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", partyId), locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawOutMap)) {
			return finAccountWithdrawOutMap;
		}
		
		GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, partyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
        	Debug.logError("商家[" + partyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", partyId), locale));
        }

		String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");
		
		// 卖卡限额回冲
		Map<String, Object> createFinAccountAuthOutMap;
		try {
			createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, 
							"currencyUomId", DEFAULT_CURRENCY_UOM_ID,  
							"finAccountId", creditLimitAccountId,
							"amount", arAmount.negate(),
							"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
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
}
