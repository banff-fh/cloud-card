package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

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
	 */
	public static Map<String, Object> createCardAuth(DispatchContext dctx, Map<String, Object> context) {
//		LocalDispatcher dispatcher = dctx.getDispatcher();
//		Delegator delegator = dispatcher.getDelegator();
//		Locale locale = (Locale) context.get("locale");
		String finAccountId = (String) context.get("finAccountId");
//		String amount = (String) context.get("amount");//TODO, 授权金额还未处理
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		GenericValue finAccount = (GenericValue) checkParamOut.get("finAccount");

		String cardCode = finAccount.getString("finAccountCode");
		
		// 授权时判断用户是否存在， 不存在则创建用户
		Map<String, Object> getOrCreateCustomerOut = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}		
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");
		
		//创建paymentMethod和giftCard
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("cardNumber", cardCode);
		giftCardInMap.put("description", "将账户" + finAccountId + "授权给" + customerPartyId);
		giftCardInMap.put("customerPartyId", customerPartyId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}		
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("customerPartyId", customerPartyId);
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
	public static Map<String, Object> createCloudCardAndRecharge(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		String teleNumber = (String) context.get("teleNumber");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		// TODO，数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能进行开卡、充值操作", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
		}

		
		//1、根据二维码获取卡信息
		GenericValue cloudCard;
		String finAccountId;
		String customerPartyId;
		try {
			cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		if(null == cloudCard ){
			// 没有激活的卡，调用开卡服务
			if(UtilValidate.isEmpty(teleNumber)){
				Debug.logInfo("激活新卡需要输入客户手机号码", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNeedTelenumber", locale)); 
			}else{
				Map<String, Object> createCloudCardOutMap;
				try {
					createCloudCardOutMap = dispatcher.runSync("activeCloudCard",
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
				
				finAccountId = (String) createCloudCardOutMap.get("finAccountId");
				customerPartyId = (String) createCloudCardOutMap.get("customerPartyId");
			}
		}else{
			finAccountId = cloudCard.getString("finAccountId");
			// ownerPartyId 如果卡已授权给别人，partyId是被授权人，ownerPartyId是原主人
			customerPartyId = cloudCard.getString("partyId");
		}
		
		//2、充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"organizationPartyId", organizationPartyId, 
							"finAccountId", finAccountId,
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
		result.put("actualBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("finAccountId", finAccountId);
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
	public static Map<String, Object> activeCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		
		

		// 检查是否系统生成的卡
		GenericValue cloudCard;
		try {
			cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e2) {
			Debug.logError(e2.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		if(null == cloudCard){
			Debug.logInfo("此卡[" + cardCode + "]不存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotFound", locale)); 
		}

		if(!"FNACT_PUBLISHED".equals(cloudCard.getString("statusId"))){
			Debug.logInfo("此卡[" + cardCode + "]状态为[" + cloudCard.getString("statusId") + "]不能进行激活", module);
			//TODO
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCanNotActive", locale)); 
		}
		
		//if()  TODO, 检查 卖卡商家与 organizationPartyId 是否匹配
		
		String finAccountName = cloudCard.getString("finAccountName");
		
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
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("cardNumber", cardCode);
		giftCardInMap.put("description", finAccountName);
		giftCardInMap.put("customerPartyId", customerPartyId);
		giftCardInMap.put("finAccountId", finAccountId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}	
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
	 *   2、存入用户账户
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
		String finAccountId = (String) context.get("finAccountId");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Timestamp nowTimestamp = UtilDateTime.nowTimestamp();
		

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
//		GenericValue partyGroup = (GenericValue) checkParamOut.get("partyGroup");
		GenericValue finAccount = (GenericValue) checkParamOut.get("finAccount");

		// 1、扣商户开卡余额
		// 获取商户用于管理卖卡限额的金融账户
		GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
        	Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }

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
							//TODO 奇怪的BUG，如果fromDate直接用当前时间戳，
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
		

		// 用户账户存入
		Map<String, Object> finAccountDepositOutMap;
		try {
			finAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, "statusId", "PMNT_RECEIVED", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT", 
							"paymentTypeId","GC_DEPOSIT",// paymentType待定
							"finAccountId", finAccountId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
							finAccount.get("ownerPartyId"), "amount", amount, "comments", "充值",
							"reasonEnumId", "FATR_REPLENISH"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountDepositOutMap)) {
			return finAccountDepositOutMap;
		}
		
//		String depositPaymentId = (String) finAccountDepositOutMap.get("paymentId");
		
		try {
			finAccount.refresh();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		BigDecimal actualBalance = (BigDecimal) finAccount.get("actualBalance");
		if(null == actualBalance){
			actualBalance = BigDecimal.ZERO;
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("actualBalance", actualBalance);
		result.put("customerPartyId", finAccount.get("ownerPartyId"));
		result.put("amount", amount);
		result.put("finAccountId", finAccountId);
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

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		// TODO，数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行扫码消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
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
		GenericValue FinAccountAndPaymentMethodAndGiftCard;
		try {
			FinAccountAndPaymentMethodAndGiftCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e2) {
			Debug.logError(e2.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		if(null == FinAccountAndPaymentMethodAndGiftCard){
			Debug.logInfo("未找到卡信息：" + cardCode, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotFound", locale)); 
		}
		
		String finAccountId = FinAccountAndPaymentMethodAndGiftCard.getString("finAccountId");
		String customerPartyId = FinAccountAndPaymentMethodAndGiftCard.getString("ownerPartyId");
		String paymentMethodId = FinAccountAndPaymentMethodAndGiftCard.getString("paymentMethodId");
		String finAccountOrganizationPartyId = FinAccountAndPaymentMethodAndGiftCard.getString("organizationPartyId");
		boolean isSameStore = false; //是否 店内消费（本店卖出去的卡在本店消费）
		if(finAccountOrganizationPartyId.equals(organizationPartyId)){
			 isSameStore = true;
		}

		
		// 1、扣除用户余额
		// 检查余额是否够用
		BigDecimal actualBalance = FinAccountAndPaymentMethodAndGiftCard.getBigDecimal("actualBalance");
		if(null==actualBalance){
			actualBalance = BigDecimal.ZERO;
		}
		if (actualBalance.compareTo(amount) < 0) {
			Debug.logError("余额不足, 余额：" + actualBalance.toPlainString(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBalanceIsNotEnough", 
					UtilMisc.toMap("finAccountId", finAccountId, "balance", actualBalance.toPlainString()), locale));
		}
		
		
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale, "statusId", "PMNT_SENT", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "WITHDRAWAL", 
							"paymentTypeId","GC_WITHDRAWAL",// TODO paymentType待定 
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
		
		// 3、修改发票状态为INVOICE_APPROVED时，会自动创建 PaymentApplication 
		Map<String, Object> setInvoiceStatusOutMap;
		try {
			setInvoiceStatusOutMap = dispatcher.runSync("setInvoiceStatus",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"invoiceId",invoiceId,
							"statusId", "INVOICE_APPROVED"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(setInvoiceStatusOutMap)) {
			return setInvoiceStatusOutMap;
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
								//TODO 奇怪的BUG，如果fromDate直接用当前时间戳，
								// 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
								// 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
								// 所以，这里人为地把fromDate时间提前100毫秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
								"fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.MILLISECOND, -100)));
			} catch (GenericServiceException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
				return createFinAccountAuthOutMap;
			}
			delegator.clearCacheLine(creditLimitAccount);
		}
		
		try {
			FinAccountAndPaymentMethodAndGiftCard.refresh();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("amount", amount);
		result.put("actualBalance", FinAccountAndPaymentMethodAndGiftCard.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("finAccountId", finAccountId);
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
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		
		// 金额必须为正数
		if (UtilValidate.isNotEmpty(amount) && amount.compareTo(BigDecimal.ZERO) <= 0) {
			Debug.logInfo("金额不合法，必须为正数", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceAccountingError, "AccountingFinAccountMustBePositive", locale));
		}

		// organizationPartyId 必须存在
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
		
		return retMap;
	}
}
